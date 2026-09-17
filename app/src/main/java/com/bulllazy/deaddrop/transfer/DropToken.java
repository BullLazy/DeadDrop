package com.bulllazy.deaddrop.transfer;

import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** A client-side encryption key for one encrypted drop. */
public final class DropToken {
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String transferId;
    private final byte[] key;

    private DropToken(String transferId, byte[] key) {
        this.transferId = transferId;
        this.key = key.clone();
    }

    public static DropToken create(String transferId) {
        byte[] key = new byte[KEY_LENGTH];
        RANDOM.nextBytes(key);
        return new DropToken(transferId, key);
    }

    public static DropToken fromKey(String transferId, byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("Encryption key has an invalid length");
        }
        return new DropToken(transferId, key);
    }

    public static DropToken fromToken(String transferId, String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token must not be empty");
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Token is not valid", exception);
        }
        return fromKey(transferId, decoded);
    }

    public String getTransferId() { return transferId; }

    public String asToken() {
        return Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public byte[] copyKey() { return key.clone(); }

    public boolean matches(String candidate) {
        try {
            return MessageDigest.isEqual(key, fromToken(transferId, candidate).key);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public void clear() {
        Arrays.fill(key, (byte) 0);
    }
}
