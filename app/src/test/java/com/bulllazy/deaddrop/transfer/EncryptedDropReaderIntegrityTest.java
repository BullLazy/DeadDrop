package com.bulllazy.deaddrop.transfer;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import org.junit.Test;

public final class EncryptedDropReaderIntegrityTest {
    @Test
    public void rejectsTrailingPartialChunkLength() throws Exception {
        File packageFile = Files.createTempFile("deaddrop-integrity", ".dd").toFile();
        try {
            try (FileOutputStream output = new FileOutputStream(packageFile)) {
                output.write(EncryptedDropWriter.MAGIC);
                EncryptedDropWriter.writeInt(output, EncryptedDropWriter.CHUNK_SIZE);
                EncryptedDropWriter.writeLong(output, 0L);
                output.write(1);
            }
            try {
                EncryptedDropReader.verify(packageFile, new byte[32]);
                fail("trailing partial chunk must not verify");
            } catch (java.io.IOException expected) {
                // A package must end exactly at a chunk boundary.
            }
        } finally {
            packageFile.delete();
        }
    }
}
