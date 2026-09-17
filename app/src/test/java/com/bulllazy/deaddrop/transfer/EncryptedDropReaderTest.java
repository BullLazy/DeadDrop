package com.bulllazy.deaddrop.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

public final class EncryptedDropReaderTest {
    @Test
    public void verifiesAndDecryptsChunkedPackage() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] plain = "streamed test payload".getBytes(StandardCharsets.UTF_8);
        File packageFile = createPackage(key, plain, false);
        try {
            assertEquals(plain.length, EncryptedDropReader.verify(packageFile, key));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            EncryptedDropReader.decryptTo(packageFile, key, output);
            assertArrayEquals(plain, output.toByteArray());
        } finally {
            packageFile.delete();
        }
    }

    @Test(expected = java.io.EOFException.class)
    public void rejectsTruncatedRecord() throws Exception {
        byte[] key = new byte[32];
        File packageFile = createPackage(key, new byte[] {1, 2, 3}, true);
        try {
            EncryptedDropReader.verify(packageFile, key);
        } finally {
            packageFile.delete();
        }
    }

    private File createPackage(byte[] key, byte[] plain, boolean truncate) throws Exception {
        File file = File.createTempFile("deaddrop-test-", ".dd");
        byte[] nonce = new byte[EncryptedDropWriter.NONCE_SIZE];
        new SecureRandom().nextBytes(nonce);
        byte[] encrypted = encrypt(key, nonce, plain);
        try (DataOutputStream output = new DataOutputStream(new java.io.FileOutputStream(file))) {
            output.write(EncryptedDropWriter.MAGIC);
            output.writeInt(EncryptedDropWriter.CHUNK_SIZE);
            output.writeLong(plain.length);
            output.writeInt(plain.length);
            output.write(nonce);
            output.write(encrypted, 0, truncate ? encrypted.length - 1 : encrypted.length);
        }
        return file;
    }

    private byte[] encrypt(byte[] key, byte[] nonce, byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plain);
    }
}
