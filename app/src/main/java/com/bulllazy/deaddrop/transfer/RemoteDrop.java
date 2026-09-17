package com.bulllazy.deaddrop.transfer;

/** Backend identifiers and bearer credentials for one remote drop. */
public final class RemoteDrop {
    private final String id;
    private final String downloadPath;
    private final String uploadToken;
    private final String downloadToken;
    private final String expiresAt;

    public RemoteDrop(String id, String downloadPath, String uploadToken,
                      String downloadToken, String expiresAt) {
        if (id == null || id.trim().isEmpty() || downloadPath == null
                || downloadPath.trim().isEmpty() || uploadToken == null
                || uploadToken.trim().isEmpty() || downloadToken == null
                || downloadToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote drop fields must not be empty");
        }
        this.id = id;
        this.downloadPath = downloadPath;
        this.uploadToken = uploadToken;
        this.downloadToken = downloadToken;
        this.expiresAt = expiresAt == null ? "" : expiresAt;
    }

    public String getId() { return id; }
    public String getDownloadPath() { return downloadPath; }
    public String getUploadToken() { return uploadToken; }
    public String getDownloadToken() { return downloadToken; }
    public String getExpiresAt() { return expiresAt; }
}
