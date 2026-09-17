package com.bulllazy.deaddrop.transfer;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.junit.Test;

public final class MetadataSanitizerTest {
    @Test
    public void removesJpegApp1MetadataBeforeImageData() throws Exception {
        byte[] jpeg = new byte[] {
                (byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xe1, 0x00, 0x06, 'E', 'X', 'I', 'F',
                (byte) 0xff, (byte) 0xda, 0x00, 0x02,
                0x01, 0x02, (byte) 0xff, (byte) 0xd9
        };
        byte[] expected = new byte[] {
                (byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xda, 0x00, 0x02,
                0x01, 0x02, (byte) 0xff, (byte) 0xd9
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream sanitized = MetadataSanitizer.forFile(new ByteArrayInputStream(jpeg),
                "image/jpeg", "photo.jpg")) {
            byte[] buffer = new byte[8];
            int read;
            while ((read = sanitized.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
        }
        assertArrayEquals(expected, output.toByteArray());
    }
}
