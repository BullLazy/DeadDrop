package com.bulllazy.deaddrop.transfer;

import android.net.Uri;

import java.net.URI;
import java.net.URISyntaxException;

/** Parses a remote drop URL; the encryption key remains in the URI fragment. */
public final class DropUrl {
    private final String transferId;
    private final String endpoint;
    private final String accessToken;
    private final DropToken encryptionToken;

    private DropUrl(String transferId, String endpoint, String accessToken,
                    DropToken encryptionToken) {
        this.transferId = transferId;
        this.endpoint = endpoint;
        this.accessToken = accessToken;
        this.encryptionToken = encryptionToken;
    }

    public static DropUrl parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Drop URL must not be empty");
        }
        String trimmed = value.trim();
        try {
            URI uri = new URI(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getRawPath() == null) {
                throw new IllegalArgumentException("Drop URL is not valid");
            }
            String path = uri.getRawPath();
            String marker = "/v1/drops/";
            if (!path.startsWith(marker) || !path.endsWith("/content")) {
                throw new IllegalArgumentException("Drop URL is not a remote drop");
            }
            String transferId = path.substring(marker.length(), path.length() - "/content".length());
            if (transferId.length() != 32 || !isLowerHex(transferId)) {
                throw new IllegalArgumentException("Drop URL has no transfer id");
            }
            String accessToken = Uri.parse(trimmed).getQueryParameter("token");
            if (accessToken == null || accessToken.trim().isEmpty()) {
                throw new IllegalArgumentException("Drop URL has no access token");
            }
            String fragment = uri.getRawFragment();
            String keyValue = null;
            if (fragment != null) {
                for (String part : fragment.split("&")) {
                    int separator = part.indexOf('=');
                    String name = separator < 0 ? part : part.substring(0, separator);
                    if ("key".equals(name)) {
                        keyValue = separator < 0 ? "" : part.substring(separator + 1);
                        break;
                    }
                }
            }
            DropToken encryptionToken = DropToken.fromToken(transferId, keyValue);
            String endpoint = trimmed;
            int queryStart = endpoint.indexOf('?');
            if (queryStart >= 0) {
                endpoint = endpoint.substring(0, queryStart);
            }
            return new DropUrl(transferId, endpoint, accessToken, encryptionToken);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Drop URL is not valid", exception);
        }
    }

    private static boolean isLowerHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    public String getTransferId() { return transferId; }
    public String getEndpoint() { return endpoint; }
    public String getAccessToken() { return accessToken; }
    public DropToken getEncryptionToken() { return encryptionToken; }
    /** @deprecated Use getAccessToken() and getEncryptionToken() separately. */
    @Deprecated public DropToken getToken() { return encryptionToken; }

    /** @deprecated Builds the new remote link without exposing its key to HTTP. */
    @Deprecated public String withToken() {
        return endpoint + "?token=" + Uri.encode(accessToken) + "#key=" + Uri.encode(encryptionToken.asToken());
    }
}
