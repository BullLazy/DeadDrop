package com.bulllazy.deaddrop.transfer;

import android.content.ContentResolver;

import com.bulllazy.deaddrop.model.SelectedFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Creates a resumable, chunked AES-GCM package without buffering the source file. */
public final class EncryptedDropWriter {
    public static final int CHUNK_SIZE = 64 * 1024;
    static final int HEADER_SIZE = 20;
    static final int NONCE_SIZE = 12;
    static final byte[] MAGIC = "DDROP1\0\0".getBytes(StandardCharsets.US_ASCII);

    public interface ProgressListener {
        void onProgress(long bytesProcessed);
    }

    private final ContentResolver contentResolver;
    private final SelectedFile selectedFile;
    private final File targetFile;
    private final byte[] key;
    private final ProgressListener progressListener;

    public EncryptedDropWriter(ContentResolver contentResolver, SelectedFile selectedFile,
                               File targetFile, byte[] key, ProgressListener progressListener) {
        this.contentResolver = contentResolver;
        this.selectedFile = selectedFile;
        this.targetFile = targetFile;
        this.key = key.clone();
        this.progressListener = progressListener;
    }

    public long write() throws IOException, GeneralSecurityException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create private transfer directory");
        }
        long alreadyProcessed = repairAndMeasureExistingPackage();
        try (InputStream source = new BufferedInputStream(selectedFile.openInputStream(contentResolver), CHUNK_SIZE);
             InputStream sanitized = MetadataSanitizer.forFile(source, selectedFile.getMimeType(), selectedFile.getDisplayName())) {
            skipSource(sanitized, alreadyProcessed);
            try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(targetFile, true), CHUNK_SIZE)) {
                byte[] plain = new byte[CHUNK_SIZE];
                SecureRandom random = new SecureRandom();
                long processed = alreadyProcessed;
                int read;
                while ((read = readChunk(sanitized, plain)) > 0) {
                    byte[] nonce = new byte[NONCE_SIZE];
                    random.nextBytes(nonce);
                    byte[] encrypted = encrypt(plain, read, nonce);
                    writeInt(output, read);
                    output.write(nonce);
                    output.write(encrypted);
                    output.flush();
                    processed += read;
                    if (progressListener != null) {
                        progressListener.onProgress(processed);
                    }
                }
            }
        }
        long actualSize = countPlainBytes(targetFile);
        updateOriginalSize(targetFile, actualSize);
        return actualSize;
    }

    private byte[] encrypt(byte[] plain, int length, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plain, 0, length);
    }

    private long repairAndMeasureExistingPackage() throws IOException {
        if (!targetFile.exists() || targetFile.length() == 0L) {
            try (FileOutputStream output = new FileOutputStream(targetFile)) {
                output.write(MAGIC);
                writeInt(output, CHUNK_SIZE);
                writeLong(output, selectedFile.getSizeBytes());
            }
            return 0L;
        }
        try (RandomAccessFile packageFile = new RandomAccessFile(targetFile, "rw")) {
            if (packageFile.length() < HEADER_SIZE) {
                packageFile.setLength(0L);
                packageFile.write(MAGIC);
                packageFile.writeInt(CHUNK_SIZE);
                packageFile.writeLong(selectedFile.getSizeBytes());
                return 0L;
            }
            byte[] magic = new byte[MAGIC.length];
            packageFile.readFully(magic);
            if (!java.util.Arrays.equals(MAGIC, magic) || packageFile.readInt() != CHUNK_SIZE) {
                throw new IOException("Transfer package format does not match");
            }
            packageFile.readLong();
            long boundary = HEADER_SIZE;
            long processed = 0L;
            while (boundary < packageFile.length()) {
                packageFile.seek(boundary);
                int length;
                try {
                    length = packageFile.readInt();
                } catch (EOFException end) {
                    break;
                }
                long recordEnd = boundary + 4L + NONCE_SIZE + length + 16L;
                if (length < 0 || length > CHUNK_SIZE || recordEnd > packageFile.length()) {
                    break;
                }
                processed += length;
                boundary = recordEnd;
            }
            packageFile.setLength(boundary);
            return processed;
        }
    }

    private void updateOriginalSize(File packageFile, long actualSize) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(packageFile, "rw")) {
            output.seek(MAGIC.length + 4L);
            output.writeLong(actualSize);
        }
    }

    private long countPlainBytes(File packageFile) throws IOException {
        long processed = 0L;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(packageFile)))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            input.readInt();
            input.readLong();
            while (true) {
                try {
                    int length = input.readInt();
                    if (length < 0 || length > CHUNK_SIZE) {
                        throw new IOException("Invalid encrypted chunk");
                    }
                    skipFully(input, NONCE_SIZE);
                    skipFully(input, length + 16L);
                    processed += length;
                } catch (EOFException end) {
                    return processed;
                }
            }
        }
    }

    private void skipSource(InputStream input, long bytes) throws IOException {
        byte[] buffer = new byte[CHUNK_SIZE];
        long remaining = bytes;
        while (remaining > 0L) {
            int wanted = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, wanted);
            if (read < 0) {
                throw new IOException("Selected file changed before resume");
            }
            remaining -= read;
        }
    }

    private int readChunk(InputStream input, byte[] buffer) throws IOException {
        int count = 0;
        while (count < buffer.length) {
            int read = input.read(buffer, count, buffer.length - count);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            count += read;
        }
        return count;
    }

    static void writeInt(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    static void writeLong(OutputStream output, long value) throws IOException {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    static void skipFully(InputStream input, long bytes) throws IOException {
        byte[] buffer = new byte[CHUNK_SIZE];
        long remaining = bytes;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("Truncated encrypted package");
            }
            remaining -= read;
        }
    }
}
