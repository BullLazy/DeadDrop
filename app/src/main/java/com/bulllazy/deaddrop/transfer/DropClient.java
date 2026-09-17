package com.bulllazy.deaddrop.transfer;

import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS client for the Render drop API. Bodies are always streamed in bounded chunks. */
public final class DropClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int NETWORK_CHUNK_SIZE = EncryptedDropWriter.CHUNK_SIZE;

    private DropClient() { }

    public interface ProgressListener {
        void onProgress(long bytesTransferred, long totalBytes);
    }

    public static final class Metadata {
        private final String fileName;
        private final long originalSize;
        private final long packageSize;
        private final String expiresAt;

        public Metadata(String fileName, long originalSize, long packageSize) {
            this(fileName, originalSize, packageSize, "");
        }

        public Metadata(String fileName, long originalSize, long packageSize, String expiresAt) {
            this.fileName = fileName;
            this.originalSize = originalSize;
            this.packageSize = packageSize;
            this.expiresAt = expiresAt == null ? "" : expiresAt;
        }

        public String getFileName() { return fileName; }
        public long getOriginalSize() { return originalSize; }
        public long getPackageSize() { return packageSize; }
        public String getExpiresAt() { return expiresAt; }
    }

    public static RemoteDrop createDrop(String baseUrl, String fileName, long originalSize,
                                         long packageSize) throws IOException {
        String body = "{\"file_name\":\"" + jsonEscape(fileName)
                + "\",\"original_size\":" + originalSize
                + ",\"package_size\":" + packageSize + "}";
        HttpURLConnection connection = open(new URL(BackendConfig.normalize(baseUrl) + "/v1/drops"));
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_CREATED) {
                throw httpError(status);
            }
            String response = readBounded(connection.getInputStream(), 16 * 1024);
            String id = jsonString(response, "id");
            String uploadToken = jsonString(response, "upload_token");
            String downloadToken = jsonString(response, "download_token");
            String downloadPath = jsonString(response, "download_path");
            String expiresAt = jsonString(response, "expires_at");
            if (downloadPath.startsWith("/")) {
                downloadPath = BackendConfig.normalize(baseUrl) + downloadPath;
            }
            return new RemoteDrop(id, downloadPath, uploadToken, downloadToken, expiresAt);
        } finally {
            connection.disconnect();
        }
    }

    public static Metadata fetchMetadata(String endpoint, String accessToken) throws IOException {
        HttpURLConnection connection = open(new URL(endpoint));
        try {
            connection.setRequestMethod("HEAD");
            authorize(connection, accessToken);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw httpError(status);
            }
            String encodedName = connection.getHeaderField("X-DeadDrop-Name");
            String fileName = "downloaded-file";
            if (encodedName != null) {
                try {
                    fileName = new String(Base64.decode(encodedName,
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                            StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    // The backend still supplies a safe fallback name.
                }
            }
            return new Metadata(fileName,
                    headerLong(connection, "X-DeadDrop-Original-Size", 0L),
                    headerLong(connection, "X-DeadDrop-Package-Size",
                            headerLong(connection, "Content-Length", -1L)),
                    connection.getHeaderField("X-DeadDrop-Expires"));
        } finally {
            connection.disconnect();
        }
    }

    public static void upload(RemoteDrop drop, File packageFile,
                               ProgressListener listener) throws IOException {
        if (drop == null || packageFile == null || !packageFile.isFile()) {
            throw new IOException("Encrypted package is not available");
        }
        long total = packageFile.length();
        if (total <= 0L) {
            throw new IOException("Encrypted package is empty");
        }
        long offset = queryUploadOffset(drop.getDownloadPath(), drop.getUploadToken());
        if (offset > total) {
            throw new IOException("Remote upload state is invalid");
        }
        byte[] buffer = new byte[NETWORK_CHUNK_SIZE];
        try (RandomAccessFile input = new RandomAccessFile(packageFile, "r")) {
            input.seek(offset);
            while (offset < total) {
                int length = input.read(buffer, 0, (int) Math.min(buffer.length, total - offset));
                if (length <= 0) {
                    throw new IOException("Encrypted package changed during upload");
                }
                HttpURLConnection connection = open(new URL(drop.getDownloadPath()));
                try {
                    connection.setRequestMethod("PUT");
                    connection.setDoOutput(true);
                    authorize(connection, drop.getUploadToken());
                    connection.setRequestProperty("Content-Type", "application/octet-stream");
                    connection.setRequestProperty("Content-Range", "bytes " + offset + "-"
                            + (offset + length - 1L) + "/" + total);
                    connection.setFixedLengthStreamingMode(length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(buffer, 0, length);
                    }
                    int status = connection.getResponseCode();
                    if (status == HttpURLConnection.HTTP_CONFLICT) {
                        long remoteOffset = headerLong(connection, "X-Upload-Offset", -1L);
                        if (remoteOffset < 0L || remoteOffset > total) {
                            throw httpError(status);
                        }
                        if (remoteOffset == offset) {
                            throw new IOException("Remote upload rejected the current chunk");
                        }
                        offset = remoteOffset;
                        input.seek(offset);
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_NO_CONTENT) {
                        throw httpError(status);
                    }
                    long remoteOffset = headerLong(connection, "X-Upload-Offset", offset + length);
                    if (remoteOffset < offset + length || remoteOffset > total) {
                        throw new IOException("Remote upload state is invalid");
                    }
                    offset = remoteOffset;
                    input.seek(offset);
                    if (listener != null) {
                        listener.onProgress(offset, total);
                    }
                } finally {
                    connection.disconnect();
                }
            }
        }
    }

    private static long queryUploadOffset(String endpoint, String token) throws IOException {
        HttpURLConnection connection = open(new URL(endpoint));
        try {
            connection.setRequestMethod("HEAD");
            authorize(connection, token);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw httpError(status);
            }
            return headerLong(connection, "X-Upload-Offset", 0L);
        } finally {
            connection.disconnect();
        }
    }

    public static void download(String endpoint, String accessToken, File targetFile,
                                Metadata metadata, ProgressListener listener) throws IOException {
        if (targetFile == null || metadata == null || metadata.getPackageSize() <= 0L) {
            throw new IOException("Download metadata is invalid");
        }
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create download directory");
        }
        long total = metadata.getPackageSize();
        try (RandomAccessFile output = new RandomAccessFile(targetFile, "rw")) {
            long offset = output.length();
            if (offset > total) {
                output.setLength(0L);
                offset = 0L;
            }
            while (offset < total) {
                HttpURLConnection connection = open(new URL(endpoint));
                try {
                    connection.setRequestMethod("GET");
                    authorize(connection, accessToken);
                    connection.setRequestProperty("Range", "bytes=" + offset + "-");
                    int status = connection.getResponseCode();
                    if (status != HttpURLConnection.HTTP_PARTIAL
                            && !(status == HttpURLConnection.HTTP_OK && offset == 0L)) {
                        throw httpError(status);
                    }
                    output.seek(offset);
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[NETWORK_CHUNK_SIZE];
                        long expected = total - offset;
                        long received = 0L;
                        while (received < expected) {
                            int read = input.read(buffer, 0,
                                    (int) Math.min(buffer.length, expected - received));
                            if (read < 0) {
                                throw new IOException("Download interrupted");
                            }
                            if (read == 0) {
                                continue;
                            }
                            output.write(buffer, 0, read);
                            received += read;
                            offset += read;
                            if (listener != null) {
                                listener.onProgress(offset, total);
                            }
                        }
                        output.getFD().sync();
                    }
                } finally {
                    connection.disconnect();
                }
            }
            output.setLength(total);
        }
    }

    public static void deleteDrop(String endpoint, String accessToken) throws IOException {
        HttpURLConnection connection = open(new URL(endpoint));
        try {
            connection.setRequestMethod("DELETE");
            authorize(connection, accessToken);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_NO_CONTENT
                    && status != HttpURLConnection.HTTP_NOT_FOUND) {
                throw httpError(status);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        return connection;
    }

    private static void authorize(HttpURLConnection connection, String token) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Drop access token is missing");
        }
        connection.setRequestProperty("Authorization", "Bearer " + token);
    }

    private static IOException httpError(int status) {
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return new IOException("Drop access token is invalid");
        }
        if (status == HttpURLConnection.HTTP_GONE) {
            return new IOException("Drop has expired");
        }
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            return new IOException("Drop was not found");
        }
        return new IOException("Drop server returned HTTP " + status);
    }

    private static long headerLong(HttpURLConnection connection, String name, long fallback) {
        String value = connection.getHeaderField(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readBounded(InputStream input, int maximum) throws IOException {
        byte[] buffer = new byte[4096];
        StringBuilder result = new StringBuilder();
        try (InputStream stream = input) {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (result.length() + read > maximum) {
                    throw new IOException("Backend response is too large");
                }
                result.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static String jsonString(String json, String name) throws IOException {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IOException("Backend response is incomplete");
        }
        start += marker.length();
        if (start >= json.length() || json.charAt(start) != '\"') {
            throw new IOException("Backend response is invalid");
        }
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = start + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaped) {
                if (character == '"' || character == '\\' || character == '/') {
                    result.append(character);
                } else if (character == 'n') {
                    result.append('\n');
                } else if (character == 'r') {
                    result.append('\r');
                } else if (character == 't') {
                    result.append('\t');
                } else {
                    throw new IOException("Backend response is invalid");
                }
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return result.toString();
            } else {
                result.append(character);
            }
        }
        throw new IOException("Backend response is invalid");
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder();
        if (value == null) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character < 32) {
                result.append('_');
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }
}
