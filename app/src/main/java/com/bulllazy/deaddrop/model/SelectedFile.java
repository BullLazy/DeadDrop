package com.bulllazy.deaddrop.model;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;

/** Immutable metadata for a document selected through the Storage Access Framework. */
public final class SelectedFile {
    private final String displayName;
    private final Uri uri;
    private final String mimeType;
    private final long sizeBytes;
    private final long selectedAtMillis;

    public SelectedFile(String displayName, Uri uri, String mimeType, long sizeBytes, long selectedAtMillis) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("displayName must not be empty");
        }
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        this.displayName = FileNameSanitizer.sanitize(displayName);
        this.uri = Objects.requireNonNull(uri, "uri");
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.sizeBytes = sizeBytes;
        this.selectedAtMillis = selectedAtMillis;
    }

    public String getDisplayName() { return displayName; }
    public Uri getUri() { return uri; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public long getSelectedAtMillis() { return selectedAtMillis; }

    /** Opens a stream on demand; callers must close it and must not buffer the document in memory. */
    public InputStream openInputStream(ContentResolver contentResolver) throws FileNotFoundException {
        InputStream inputStream = contentResolver.openInputStream(uri);
        if (inputStream == null) {
            throw new FileNotFoundException("Unable to open selected document");
        }
        return inputStream;
    }
}
