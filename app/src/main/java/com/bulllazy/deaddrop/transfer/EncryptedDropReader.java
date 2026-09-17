package com.bulllazy.deaddrop.transfer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Verifies and decrypts a completed drop one chunk at a time. */
public final class EncryptedDropReader {
    private EncryptedDropReader() { }

    public static final class Header {
        private final int chunkSize;
        private final long originalSize;

        Header(int chunkSize, long originalSize) {
            this.chunkSize = chunkSize;
            this.originalSize = originalSize;
        }

        public int getChunkSize() { return chunkSize; }
        public long getOriginalSize() { return originalSize; }
    }

    public static Header readHeader(File packageFile) throws IOException {
        try (DataInputStream input = open(packageFile)) {
            return readHeader(input);
        }
    }

    public static long verify(File packageFile, byte[] key) throws IOException, GeneralSecurityException {
        long processed = 0L;
        try (DataInputStream input = open(packageFile)) {
            Header header = readHeader(input);
            while (true) {
                int length = readLength(input);
                if (length < 0) {
                    break;
                }
                byte[] nonce = readRecordNonce(input, length, header.chunkSize);
                byte[] encrypted = new byte[length + 16];
                input.readFully(encrypted);
                decrypt(encrypted, nonce, key);
                processed += length;
            }
            if (processed != header.originalSize) {
                throw new IOException("Encrypted package is incomplete");
            }
        }
        return processed;
    }

    public static Header decryptTo(File packageFile, byte[] key, OutputStream output)
            throws IOException, GeneralSecurityException {
        try (DataInputStream input = open(packageFile)) {
            Header header = readHeader(input);
            long processed = 0L;
            while (true) {
                int length = readLength(input);
                if (length < 0) {
                    break;
                }
                byte[] nonce = readRecordNonce(input, length, header.chunkSize);
                byte[] encrypted = new byte[length + 16];
                input.readFully(encrypted);
                output.write(decrypt(encrypted, nonce, key));
                processed += length;
            }
            if (processed != header.originalSize) {
                throw new IOException("Encrypted package is incomplete");
            }
            return header;
        }
    }

    private static DataInputStream open(File file) throws IOException {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(file),
                EncryptedDropWriter.CHUNK_SIZE));
    }

    private static Header readHeader(DataInputStream input) throws IOException {
        byte[] magic = new byte[EncryptedDropWriter.MAGIC.length];
        input.readFully(magic);
        if (!java.util.Arrays.equals(magic, EncryptedDropWriter.MAGIC)) {
            throw new IOException("Unknown encrypted package");
        }
        int chunkSize = input.readInt();
        long originalSize = input.readLong();
        if (chunkSize <= 0 || chunkSize > 1024 * 1024 || originalSize < 0L) {
            throw new IOException("Invalid encrypted package header");
        }
        return new Header(chunkSize, originalSize);
    }

    private static int readLength(DataInputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return -1;
        }
        int second = input.read();
        int third = input.read();
        int fourth = input.read();
        if (second < 0 || third < 0 || fourth < 0) {
            throw new EOFException("Truncated encrypted chunk length");
        }
        return (first << 24) | (second << 16) | (third << 8) | fourth;
    }

    private static byte[] readRecordNonce(DataInputStream input, int length, int chunkSize)
            throws IOException {
        if (length < 0 || length > chunkSize) {
            throw new IOException("Invalid encrypted chunk");
        }
        byte[] nonce = new byte[EncryptedDropWriter.NONCE_SIZE];
        input.readFully(nonce);
        return nonce;
    }

    private static byte[] decrypt(byte[] encrypted, byte[] nonce, byte[] key)
            throws GeneralSecurityException {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new GeneralSecurityException("Invalid encryption key");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(encrypted);
    }
}
