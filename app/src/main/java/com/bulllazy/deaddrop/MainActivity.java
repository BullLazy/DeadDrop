package com.bulllazy.deaddrop;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bulllazy.deaddrop.model.SelectedFile;
import com.bulllazy.deaddrop.transfer.BackendConfig;
import com.bulllazy.deaddrop.transfer.DropClient;
import com.bulllazy.deaddrop.transfer.DropToken;
import com.bulllazy.deaddrop.transfer.DropUrl;
import com.bulllazy.deaddrop.transfer.EncryptedDropReader;
import com.bulllazy.deaddrop.transfer.EncryptedDropWriter;
import com.bulllazy.deaddrop.transfer.RemoteDrop;
import com.bulllazy.deaddrop.transfer.SecureTransferKeyStore;
import com.bulllazy.deaddrop.transfer.Transfer;
import com.bulllazy.deaddrop.transfer.TransferManager;
import com.bulllazy.deaddrop.transfer.TransferState;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Entry point for the DeadDrop application. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_DOCUMENT = 1001;
    private static final int REQUEST_CREATE_DOCUMENT = 1002;
    private static final String SENDER_KEY_PREFIX = "sender_";
    private static final String SENDER_UPLOAD_PREFIX = "sender_upload_";
    private static final String SENDER_DOWNLOAD_PREFIX = "sender_download_";
    private static final String RECEIVER_KEY_PREFIX = "receiver_";
    private static final String RECEIVER_ACCESS_PREFIX = "receiver_access_";

    private final TransferManager transferManager = new TransferManager();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "deaddrop-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText backendUrlInput;
    private TextView selectedFileNameView;
    private TextView selectedFileSizeView;
    private TextView transferStatusView;
    private TextView progressPercentView;
    private ProgressBar progressBar;
    private Button sendButton;
    private Button selectFileButton;
    private Button shareButton;
    private Button copyButton;
    private Button stopButton;
    private EditText receiveUrlInput;
    private TextView receiveStatusView;
    private TextView receiveNameView;
    private TextView receivePercentView;
    private ProgressBar receiveProgressBar;
    private Button receiveButton;
    private TextView shareUrlView;

    private SelectedFile selectedFile;
    private Transfer currentTransfer;
    private DropToken senderToken;
    private File senderPackageFile;
    private RemoteDrop senderRemoteDrop;
    private String senderBaseUrl;
    private boolean senderKeyPersisted;
    private String shareUrl;

    private String receiverEndpoint;
    private String receiverTransferId;
    private String receiverAccessToken;
    private DropToken receiverToken;
    private DropClient.Metadata receiverMetadata;
    private File receiverPackageFile;
    private Uri receiverDestination;
    private boolean receiverKeyPersisted;
    private boolean receiverAccessPersisted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        backendUrlInput = findViewById(R.id.backend_url_input);
        selectedFileNameView = findViewById(R.id.selected_file_name);
        selectedFileSizeView = findViewById(R.id.selected_file_size);
        transferStatusView = findViewById(R.id.transfer_status);
        progressPercentView = findViewById(R.id.transfer_percent);
        progressBar = findViewById(R.id.transfer_progress);
        sendButton = findViewById(R.id.send_button);
        shareButton = findViewById(R.id.share_button);
        copyButton = findViewById(R.id.copy_button);
        stopButton = findViewById(R.id.stop_button);
        shareUrlView = findViewById(R.id.share_url);
        receiveUrlInput = findViewById(R.id.receive_url_input);
        receiveStatusView = findViewById(R.id.receive_status);
        receiveNameView = findViewById(R.id.receive_name);
        receivePercentView = findViewById(R.id.receive_percent);
        receiveProgressBar = findViewById(R.id.receive_progress);
        receiveButton = findViewById(R.id.receive_button);

        Button saveBackendUrlButton = findViewById(R.id.save_backend_url_button);
        backendUrlInput.setText(BackendConfig.getConfiguredBaseUrl(this));
        saveBackendUrlButton.setOnClickListener(view -> saveBackendUrl());
        selectFileButton = findViewById(R.id.select_file_button);
        selectFileButton.setOnClickListener(view -> openDocumentPicker());
        sendButton.setOnClickListener(view -> prepareDrop());
        shareButton.setOnClickListener(view -> shareDrop());
        copyButton.setOnClickListener(view -> copyShareUrl());
        stopButton.setOnClickListener(view -> stopSharing());
        receiveButton.setOnClickListener(view -> prepareReceive());

        restoreSenderSession();
        restoreReceiverSession();
    }

    private void saveBackendUrl() {
        try {
            BackendConfig.saveBaseUrl(this, backendUrlInput.getText().toString());
            backendUrlInput.setText(BackendConfig.getConfiguredBaseUrl(this));
            Toast.makeText(this, R.string.backend_url_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, R.string.backend_url_invalid, Toast.LENGTH_LONG).show();
        }
    }

    private void discardCurrentSenderSession() {
        RemoteDrop remoteDrop = senderRemoteDrop;
        String accessToken = remoteDrop == null ? null : remoteDrop.getDownloadToken();
        String endpoint = remoteDrop == null ? null : remoteDrop.getDownloadPath();
        clearSenderSession(true);
        shareUrl = null;
        deleteRemoteDropAsync(endpoint, accessToken);
    }

    private void deleteRemoteDropAsync(String endpoint, String accessToken) {
        if (endpoint == null || accessToken == null) {
            return;
        }
        executor.execute(() -> {
            try {
                DropClient.deleteDrop(endpoint, accessToken);
            } catch (IOException ignored) {
                // The backend TTL remains the cleanup fallback when the connection is unavailable.
            }
        });
    }

    private void openDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_OPEN_DOCUMENT) {
            handleSelectedDocument(data);
        } else if (requestCode == REQUEST_CREATE_DOCUMENT) {
            handleDestinationDocument(data);
        }
    }

    private void handleSelectedDocument(Intent data) {
        discardCurrentSenderSession();
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        int grantedFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, grantedFlags);
        } catch (SecurityException ignored) {
            // Some providers offer a transient grant only; it remains usable for this session.
        }
        selectedFile = readSelectedFile(uri);
        currentTransfer = transferManager.createTransfer(selectedFile);
        senderToken = null;
        senderPackageFile = null;
        senderRemoteDrop = null;
        senderBaseUrl = null;
        senderKeyPersisted = false;
        shareUrl = null;
        showSelectedTransfer(currentTransfer);
        shareButton.setEnabled(false);
        copyButton.setEnabled(false);
        stopButton.setEnabled(false);
        shareUrlView.setText(R.string.share_url_initial);
    }

    private SelectedFile readSelectedFile(Uri uri) {
        String displayName = getString(R.string.unknown_file_name);
        long sizeBytes = 0L;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    long providerSize = cursor.getLong(sizeIndex);
                    if (providerSize >= 0L) {
                        sizeBytes = providerSize;
                    }
                }
            }
        }
        String mimeType = getContentResolver().getType(uri);
        return new SelectedFile(displayName, uri, mimeType, sizeBytes, System.currentTimeMillis());
    }

    private void showSelectedTransfer(Transfer transfer) {
        selectedFileNameView.setText(transfer.getFileName());
        selectedFileSizeView.setText(getString(R.string.file_size_value,
                formatFileSize(transfer.getTotalBytes())));
        transferStatusView.setText(getString(R.string.transfer_status_value,
                transfer.getState().getDisplayName()));
        progressBar.setProgress(transfer.getProgressPercent());
        progressPercentView.setText(String.format(Locale.getDefault(), "%d%%",
                transfer.getProgressPercent()));
        sendButton.setEnabled(true);
        if (transfer.getState() == TransferState.COMPLETED) {
            sendButton.setText(R.string.open_sharing);
        } else if (transfer.getState() == TransferState.PAUSED) {
            sendButton.setText(R.string.resume_transfer);
        } else {
            sendButton.setText(R.string.send_file);
        }
    }

    private void prepareDrop() {
        if (currentTransfer == null || selectedFile == null) {
            Toast.makeText(this, R.string.select_file_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentTransfer.getState() == TransferState.COMPLETED) {
            if (shareUrl != null) {
                shareDrop();
            }
            return;
        }
        if (currentTransfer.getState() == TransferState.FAILED) {
            SelectedFile file = selectedFile;
            clearSenderSession(true);
            selectedFile = file;
            currentTransfer = transferManager.createTransfer(file);
        }
        selectFileButton.setEnabled(false);
        sendButton.setEnabled(false);
        shareButton.setEnabled(false);
        copyButton.setEnabled(false);
        executor.execute(this::prepareDropInBackground);
    }

    private void prepareDropInBackground() {
        try {
            if (senderToken == null) {
                senderToken = DropToken.create(currentTransfer.getTransferId());
            }
            if (senderPackageFile == null) {
                File directory = new File(getFilesDir(), "drops");
                senderPackageFile = new File(directory, currentTransfer.getTransferId() + ".dd.part");
            }
            persistSenderSession();
            if (currentTransfer.getState() == TransferState.QUEUED
                    || currentTransfer.getState() == TransferState.PAUSED) {
                currentTransfer.transitionTo(TransferState.PREPARING, System.currentTimeMillis());
            }
            if (currentTransfer.getState() == TransferState.PREPARING) {
                currentTransfer.transitionTo(TransferState.TRANSFERRING, System.currentTimeMillis());
            }
            persistSenderSession();
            EncryptedDropWriter writer = new EncryptedDropWriter(getContentResolver(), selectedFile,
                    senderPackageFile, senderToken.copyKey(), bytes -> {
                        if (currentTransfer.getTotalBytes() > 0L) {
                            currentTransfer.updateProgress(Math.min(bytes, currentTransfer.getTotalBytes()));
                        }
                        persistSenderSession();
                        postToUi(() -> showSelectedTransfer(currentTransfer));
                    });
            long originalSize = writer.write();
            File completeFile = new File(senderPackageFile.getParentFile(),
                    currentTransfer.getTransferId() + ".dd");
            if (!senderPackageFile.equals(completeFile)) {
                if (completeFile.exists() && !completeFile.delete()) {
                    throw new IOException("Unable to replace old encrypted package");
                }
                if (!senderPackageFile.renameTo(completeFile)) {
                    throw new IOException("Unable to finalize encrypted package");
                }
                senderPackageFile = completeFile;
            }
            if (senderRemoteDrop == null) {
                senderBaseUrl = BackendConfig.requireBaseUrl(this);
                senderRemoteDrop = DropClient.createDrop(senderBaseUrl, currentTransfer.getFileName(),
                        originalSize, senderPackageFile.length());
                persistSenderSession();
            }
            postToUi(() -> {
                transferStatusView.setText(R.string.uploading_drop);
                progressBar.setProgress(0);
                progressPercentView.setText("0%");
            });
            DropClient.upload(senderRemoteDrop, senderPackageFile, (uploaded, total) -> {
                persistSenderSession();
                int percent = total <= 0L ? 0 : (int) ((uploaded * 100d) / total);
                postToUi(() -> {
                    progressBar.setProgress(percent);
                    progressPercentView.setText(String.format(Locale.getDefault(), "%d%%", percent));
                    transferStatusView.setText(R.string.uploading_drop);
                });
            });
            currentTransfer.transitionTo(TransferState.COMPLETED, System.currentTimeMillis());
            shareUrl = buildShareUrl(senderRemoteDrop, senderToken.asToken());
            persistSenderSession();
            postToUi(() -> showSenderReady(false));
        } catch (Exception exception) {
            try {
                if (currentTransfer != null && !currentTransfer.getState().isTerminal()) {
                    currentTransfer.pause(getString(R.string.transfer_interrupted), System.currentTimeMillis());
                    persistSenderSession();
                }
            } catch (IllegalStateException ignored) {
                // Preserve the original operation failure for the user.
            }
            postToUi(() -> {
                sendButton.setEnabled(true);
                selectFileButton.setEnabled(true);
                showSelectedTransfer(currentTransfer);
                Toast.makeText(this, getString(R.string.transfer_failed, safeMessage(exception)),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void showSenderReady(boolean notify) {
        if (shareUrl == null || shareUrl.isEmpty()) {
            return;
        }
        shareButton.setEnabled(true);
        copyButton.setEnabled(true);
        stopButton.setEnabled(true);
        selectFileButton.setEnabled(false);
        sendButton.setEnabled(true);
        sendButton.setText(R.string.open_sharing);
        transferStatusView.setText(getString(R.string.share_ready, shareUrl));
        shareUrlView.setText(getString(R.string.share_url_value, shareUrl));
        if (notify) {
            Toast.makeText(this, R.string.sharing_restarted, Toast.LENGTH_SHORT).show();
        }
    }

    private String buildShareUrl(RemoteDrop remoteDrop, String encryptionKey) throws IOException {
        if (senderBaseUrl == null) {
            senderBaseUrl = BackendConfig.requireBaseUrl(this);
        }
        String path = remoteDrop.getDownloadPath();
        String endpoint = path.startsWith("http://") || path.startsWith("https://")
                ? path : senderBaseUrl + (path.startsWith("/") ? path : "/" + path);
        return endpoint + "?token=" + Uri.encode(remoteDrop.getDownloadToken())
                + "#key=" + Uri.encode(encryptionKey);
    }

    private void shareDrop() {
        if (shareUrl == null || shareUrl.isEmpty()) {
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, shareUrl);
        startActivity(Intent.createChooser(share, getString(R.string.share_drop)));
    }

    private void copyShareUrl() {
        if (shareUrl == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("DeadDrop", shareUrl));
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
    }

    private void stopSharing() {
        RemoteDrop remoteDrop = senderRemoteDrop;
        String endpoint = remoteDrop == null ? null : remoteDrop.getDownloadPath();
        String accessToken = remoteDrop == null ? null : remoteDrop.getDownloadToken();
        clearSenderSession(true);
        shareUrl = null;
        shareButton.setEnabled(false);
        copyButton.setEnabled(false);
        stopButton.setEnabled(false);
        selectFileButton.setEnabled(true);
        sendButton.setEnabled(false);
        shareUrlView.setText(R.string.share_url_initial);
        Toast.makeText(this, R.string.sharing_stopped, Toast.LENGTH_SHORT).show();
        deleteRemoteDropAsync(endpoint, accessToken);
    }

    private void prepareReceive() {
        if (receiverToken != null && receiverDestination != null && receiverMetadata != null) {
            startReceiveDownload();
            return;
        }
        final String input = receiveUrlInput.getText().toString().trim();
        final DropUrl dropUrl;
        try {
            dropUrl = DropUrl.parse(input);
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.invalid_drop_url, Toast.LENGTH_LONG).show();
            return;
        }
        receiveButton.setEnabled(false);
        receiveStatusView.setText(R.string.checking_drop);
        executor.execute(() -> {
            try {
                DropClient.Metadata metadata = DropClient.fetchMetadata(dropUrl.getEndpoint(),
                        dropUrl.getAccessToken());
                receiverEndpoint = dropUrl.getEndpoint();
                receiverTransferId = dropUrl.getTransferId();
                receiverAccessToken = dropUrl.getAccessToken();
                DropToken parsedKey = dropUrl.getEncryptionToken();
                receiverToken = DropToken.fromKey(receiverTransferId, parsedKey.copyKey());
                parsedKey.clear();
                receiverMetadata = metadata;
                receiverPackageFile = new File(new File(getFilesDir(), "drops"),
                        "receive_" + receiverTransferId + ".part");
                persistReceiverSession();
                postToUi(() -> requestDestination(metadata));
            } catch (Exception exception) {
                postToUi(() -> {
                    receiveButton.setEnabled(true);
                    receiveStatusView.setText(getString(R.string.transfer_failed, safeMessage(exception)));
                });
            }
        });
    }

    private void requestDestination(DropClient.Metadata metadata) {
        receiveNameView.setText(metadata.getFileName());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, metadata.getFileName());
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
    }

    private void handleDestinationDocument(Intent data) {
        receiverDestination = data.getData();
        if (receiverDestination == null) {
            receiveButton.setEnabled(true);
            return;
        }
        int grantedFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(receiverDestination, grantedFlags);
        } catch (SecurityException ignored) {
            // The one-time grant is sufficient for this foreground operation.
        }
        persistReceiverSession();
        startReceiveDownload();
    }

    private void startReceiveDownload() {
        if (receiverEndpoint == null || receiverAccessToken == null || receiverToken == null
                || receiverMetadata == null || receiverDestination == null) {
            receiveButton.setEnabled(true);
            return;
        }
        receiveButton.setEnabled(false);
        receiveStatusView.setText(R.string.downloading_drop);
        executor.execute(() -> {
            boolean packageVerified = false;
            try {
                if (receiverMetadata.getPackageSize() <= 0L) {
                    receiverMetadata = DropClient.fetchMetadata(receiverEndpoint, receiverAccessToken);
                    persistReceiverSession();
                }
                DropClient.download(receiverEndpoint, receiverAccessToken, receiverPackageFile,
                        receiverMetadata, (downloaded, total) -> postToUi(() -> {
                            int percent = total <= 0L ? 0 : (int) ((downloaded * 100d) / total);
                            receiveProgressBar.setProgress(percent);
                            receivePercentView.setText(String.format(Locale.getDefault(), "%d%%", percent));
                            persistReceiverSession();
                        }));
                EncryptedDropReader.verify(receiverPackageFile, receiverToken.copyKey());
                packageVerified = true;
                try (OutputStream output = getContentResolver().openOutputStream(receiverDestination, "w")) {
                    if (output == null) {
                        throw new IOException("Unable to open destination document");
                    }
                    EncryptedDropReader.decryptTo(receiverPackageFile, receiverToken.copyKey(), output);
                }
                try {
                    DropClient.deleteDrop(receiverEndpoint, receiverAccessToken);
                } catch (IOException ignored) {
                    // Expiration cleanup removes the encrypted object if immediate cleanup fails.
                }
                if (receiverPackageFile.isFile() && !receiverPackageFile.delete()) {
                    // The package is already consumed; keep the user-visible file successful.
                }
                clearReceiverSession();
                postToUi(() -> {
                    receiveStatusView.setText(R.string.download_complete);
                    receiveProgressBar.setProgress(100);
                    receivePercentView.setText(R.string.percent_100);
                    receiveButton.setEnabled(true);
                });
            } catch (Exception exception) {
                if (!packageVerified && receiverPackageFile != null && receiverMetadata != null
                        && receiverPackageFile.length() >= receiverMetadata.getPackageSize()) {
                    receiverPackageFile.delete();
                }
                postToUi(() -> {
                    receiveButton.setEnabled(true);
                    receiveStatusView.setText(getString(R.string.transfer_failed, safeMessage(exception)));
                });
            }
        });
    }

    private void persistSenderSession() {
        if (currentTransfer == null || selectedFile == null || senderPackageFile == null || senderToken == null) {
            return;
        }
        try {
            if (!senderKeyPersisted) {
                SecureTransferKeyStore.save(this, SENDER_KEY_PREFIX + currentTransfer.getTransferId(),
                        senderToken.copyKey());
                senderKeyPersisted = true;
            }
            if (senderRemoteDrop != null) {
                SecureTransferKeyStore.save(this, SENDER_UPLOAD_PREFIX + currentTransfer.getTransferId(),
                        senderRemoteDrop.getUploadToken().getBytes(StandardCharsets.UTF_8));
                SecureTransferKeyStore.save(this, SENDER_DOWNLOAD_PREFIX + currentTransfer.getTransferId(),
                        senderRemoteDrop.getDownloadToken().getBytes(StandardCharsets.UTF_8));
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Secure key storage unavailable", exception);
        }
        getPreferences(Context.MODE_PRIVATE).edit()
                .putString("sender_id", currentTransfer.getTransferId())
                .putString("sender_uri", selectedFile.getUri().toString())
                .putString("sender_name", selectedFile.getDisplayName())
                .putString("sender_mime", selectedFile.getMimeType())
                .putLong("sender_size", selectedFile.getSizeBytes())
                .putLong("sender_created", selectedFile.getSelectedAtMillis())
                .putString("sender_artifact", senderPackageFile.getAbsolutePath())
                .putString("sender_state", currentTransfer.getState().name())
                .putLong("sender_transferred", currentTransfer.getTransferredBytes())
                .putString("sender_remote_id", senderRemoteDrop == null ? null : senderRemoteDrop.getId())
                .putString("sender_remote_path", senderRemoteDrop == null ? null : senderRemoteDrop.getDownloadPath())
                .putString("sender_remote_expires", senderRemoteDrop == null ? null : senderRemoteDrop.getExpiresAt())
                .putString("sender_base_url", senderBaseUrl)
                .commit();
    }

    private void restoreSenderSession() {
        android.content.SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
        String id = preferences.getString("sender_id", null);
        String uriValue = preferences.getString("sender_uri", null);
        String artifactValue = preferences.getString("sender_artifact", null);
        if (id == null || uriValue == null || artifactValue == null) {
            return;
        }
        try {
            TransferState state = TransferState.valueOf(preferences.getString("sender_state",
                    TransferState.QUEUED.name()));
            if (state == TransferState.PREPARING || state == TransferState.TRANSFERRING) {
                state = TransferState.PAUSED;
            }
            selectedFile = new SelectedFile(preferences.getString("sender_name", "unnamed-file"),
                    Uri.parse(uriValue), preferences.getString("sender_mime", null),
                    preferences.getLong("sender_size", 0L), preferences.getLong("sender_created", 0L));
            currentTransfer = Transfer.restore(id, selectedFile.getDisplayName(), selectedFile.getSizeBytes(),
                    selectedFile.getSelectedAtMillis(), state, preferences.getLong("sender_transferred", 0L),
                    null, 0L, 0L);
            senderToken = DropToken.fromKey(id, SecureTransferKeyStore.load(this, SENDER_KEY_PREFIX + id));
            senderKeyPersisted = true;
            senderPackageFile = new File(artifactValue);
            senderBaseUrl = preferences.getString("sender_base_url", null);
            String remoteId = preferences.getString("sender_remote_id", null);
            String remotePath = preferences.getString("sender_remote_path", null);
            if (remoteId != null && remotePath != null) {
                String upload = new String(SecureTransferKeyStore.load(this,
                        SENDER_UPLOAD_PREFIX + id), StandardCharsets.UTF_8);
                String download = new String(SecureTransferKeyStore.load(this,
                        SENDER_DOWNLOAD_PREFIX + id), StandardCharsets.UTF_8);
                senderRemoteDrop = new RemoteDrop(remoteId, remotePath, upload, download,
                        preferences.getString("sender_remote_expires", ""));
            }
            showSelectedTransfer(currentTransfer);
            if (state == TransferState.COMPLETED && senderRemoteDrop != null && senderPackageFile.isFile()) {
                shareUrl = buildShareUrl(senderRemoteDrop, senderToken.asToken());
                showSenderReady(true);
            }
        } catch (Exception exception) {
            clearSenderSession(false);
        }
    }

    private void persistReceiverSession() {
        if (receiverEndpoint == null || receiverTransferId == null || receiverAccessToken == null
                || receiverToken == null || receiverMetadata == null || receiverPackageFile == null) {
            return;
        }
        try {
            if (!receiverKeyPersisted) {
                SecureTransferKeyStore.save(this, RECEIVER_KEY_PREFIX + receiverTransferId,
                        receiverToken.copyKey());
                receiverKeyPersisted = true;
            }
            if (!receiverAccessPersisted) {
                SecureTransferKeyStore.save(this, RECEIVER_ACCESS_PREFIX + receiverTransferId,
                        receiverAccessToken.getBytes(StandardCharsets.UTF_8));
                receiverAccessPersisted = true;
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Secure key storage unavailable", exception);
        }
        getPreferences(Context.MODE_PRIVATE).edit()
                .putString("receiver_endpoint", receiverEndpoint)
                .putString("receiver_id", receiverTransferId)
                .putString("receiver_name", receiverMetadata.getFileName())
                .putLong("receiver_original_size", receiverMetadata.getOriginalSize())
                .putLong("receiver_package_size", receiverMetadata.getPackageSize())
                .putString("receiver_expires", receiverMetadata.getExpiresAt())
                .putString("receiver_artifact", receiverPackageFile.getAbsolutePath())
                .putString("receiver_destination", receiverDestination == null
                        ? null : receiverDestination.toString())
                .commit();
    }

    private void restoreReceiverSession() {
        android.content.SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
        receiverEndpoint = preferences.getString("receiver_endpoint", null);
        receiverTransferId = preferences.getString("receiver_id", null);
        String artifact = preferences.getString("receiver_artifact", null);
        if (receiverEndpoint == null || receiverTransferId == null || artifact == null) {
            return;
        }
        try {
            byte[] key = SecureTransferKeyStore.load(this, RECEIVER_KEY_PREFIX + receiverTransferId);
            receiverToken = DropToken.fromKey(receiverTransferId, key);
            receiverKeyPersisted = true;
            receiverAccessToken = new String(SecureTransferKeyStore.load(this,
                    RECEIVER_ACCESS_PREFIX + receiverTransferId), StandardCharsets.UTF_8);
            receiverAccessPersisted = true;
            receiverMetadata = new DropClient.Metadata(preferences.getString("receiver_name", "downloaded-file"),
                    preferences.getLong("receiver_original_size", 0L),
                    preferences.getLong("receiver_package_size", -1L),
                    preferences.getString("receiver_expires", ""));
            receiverPackageFile = new File(artifact);
            String destination = preferences.getString("receiver_destination", null);
            receiverDestination = destination == null ? null : Uri.parse(destination);
            receiveNameView.setText(receiverMetadata.getFileName());
            receiveStatusView.setText(R.string.download_resume_available);
            receiveButton.setEnabled(receiverDestination != null);
        } catch (Exception exception) {
            clearReceiverSession();
        }
    }

    private void clearSenderSession(boolean deleteArtifact) {
        if (currentTransfer != null) {
            String id = currentTransfer.getTransferId();
            SecureTransferKeyStore.remove(this, SENDER_KEY_PREFIX + id);
            SecureTransferKeyStore.remove(this, SENDER_UPLOAD_PREFIX + id);
            SecureTransferKeyStore.remove(this, SENDER_DOWNLOAD_PREFIX + id);
        }
        if (deleteArtifact && senderPackageFile != null && senderPackageFile.isFile()) {
            senderPackageFile.delete();
        }
        getPreferences(Context.MODE_PRIVATE).edit()
                .remove("sender_id").remove("sender_uri").remove("sender_name")
                .remove("sender_mime").remove("sender_size").remove("sender_created")
                .remove("sender_artifact").remove("sender_state").remove("sender_transferred")
                .remove("sender_remote_id").remove("sender_remote_path")
                .remove("sender_remote_expires").remove("sender_base_url").commit();
        currentTransfer = null;
        selectedFile = null;
        if (senderToken != null) {
            senderToken.clear();
        }
        senderToken = null;
        senderPackageFile = null;
        senderRemoteDrop = null;
        senderBaseUrl = null;
        senderKeyPersisted = false;
    }

    private void clearReceiverSession() {
        if (receiverTransferId != null) {
            SecureTransferKeyStore.remove(this, RECEIVER_KEY_PREFIX + receiverTransferId);
            SecureTransferKeyStore.remove(this, RECEIVER_ACCESS_PREFIX + receiverTransferId);
        }
        getPreferences(Context.MODE_PRIVATE).edit()
                .remove("receiver_endpoint").remove("receiver_id").remove("receiver_name")
                .remove("receiver_original_size").remove("receiver_package_size")
                .remove("receiver_expires").remove("receiver_artifact")
                .remove("receiver_destination").commit();
        receiverEndpoint = null;
        receiverTransferId = null;
        receiverAccessToken = null;
        if (receiverToken != null) {
            receiverToken.clear();
        }
        receiverToken = null;
        receiverMetadata = null;
        receiverPackageFile = null;
        receiverDestination = null;
        receiverKeyPersisted = false;
        receiverAccessPersisted = false;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? getString(R.string.generic_error) : message;
    }

    private void postToUi(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private String formatFileSize(long sizeBytes) {
        if (sizeBytes < 1024L) {
            return getString(R.string.file_size_bytes, sizeBytes);
        }
        if (sizeBytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024f);
        }
        if (sizeBytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", sizeBytes / (1024f * 1024f));
        }
        return String.format(Locale.getDefault(), "%.1f GB", sizeBytes / (1024f * 1024f * 1024f));
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
