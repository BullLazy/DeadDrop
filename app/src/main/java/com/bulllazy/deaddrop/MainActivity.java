package com.bulllazy.deaddrop;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bulllazy.deaddrop.model.SelectedFile;
import com.bulllazy.deaddrop.transfer.Transfer;
import com.bulllazy.deaddrop.transfer.TransferManager;

import java.util.Locale;

/** Entry point for the DeadDrop application. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_DOCUMENT = 1001;
    private final TransferManager transferManager = new TransferManager();

    private TextView selectedFileNameView;
    private TextView selectedFileSizeView;
    private TextView transferStatusView;
    private TextView progressPercentView;
    private ProgressBar progressBar;
    private Button sendButton;
    private Transfer currentTransfer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        selectedFileNameView = findViewById(R.id.selected_file_name);
        selectedFileSizeView = findViewById(R.id.selected_file_size);
        transferStatusView = findViewById(R.id.transfer_status);
        progressPercentView = findViewById(R.id.transfer_percent);
        progressBar = findViewById(R.id.transfer_progress);
        sendButton = findViewById(R.id.send_button);

        findViewById(R.id.select_file_button).setOnClickListener(view -> openDocumentPicker());
        sendButton.setOnClickListener(view -> showTransferUnavailableMessage());
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
        if (requestCode != REQUEST_OPEN_DOCUMENT || resultCode != RESULT_OK || data == null) {
            return;
        }
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

        currentTransfer = transferManager.createTransfer(readSelectedFile(uri));
        showSelectedTransfer(currentTransfer);
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
                    sizeBytes = cursor.getLong(sizeIndex);
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

    private void showTransferUnavailableMessage() {
        if (currentTransfer != null) {
            Toast.makeText(this, R.string.transfer_not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
