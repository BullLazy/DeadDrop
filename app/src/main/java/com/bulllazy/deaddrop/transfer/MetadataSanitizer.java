package com.bulllazy.deaddrop.transfer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Removes JPEG APP1 metadata while keeping file processing streaming. */
final class MetadataSanitizer {
    private MetadataSanitizer() { }

    static InputStream forFile(InputStream input, String mimeType, String fileName) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if ("image/jpeg".equals(mime) || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return new JpegMetadataInputStream(input);
        }
        return input;
    }

    private static final class JpegMetadataInputStream extends FilterInputStream {
        private byte[] pending;
        private int pendingOffset;
        private boolean scanData;
        private boolean scanDataAfterPending;

        JpegMetadataInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            if (pending != null && pendingOffset < pending.length) {
                return pending[pendingOffset++] & 0xff;
            }
            if (pending != null) {
                pending = null;
                pendingOffset = 0;
                if (scanDataAfterPending) {
                    scanData = true;
                    scanDataAfterPending = false;
                }
            }
            if (scanData) {
                return in.read();
            }

            int first = in.read();
            if (first < 0) {
                return -1;
            }
            if (first != 0xff) {
                return first;
            }
            int marker = in.read();
            if (marker < 0) {
                return first;
            }
            if (marker == 0xe1) {
                int high = in.read();
                int low = in.read();
                if (high < 0 || low < 0) {
                    throw new IOException("Truncated JPEG metadata segment");
                }
                int length = (high << 8) | low;
                if (length < 2) {
                    throw new IOException("Invalid JPEG metadata segment");
                }
                skipFully(length - 2L);
                return read();
            }

            if (hasLength(marker)) {
                int high = in.read();
                int low = in.read();
                if (high < 0 || low < 0) {
                    throw new IOException("Truncated JPEG segment");
                }
                int length = (high << 8) | low;
                if (length < 2) {
                    throw new IOException("Invalid JPEG segment");
                }
                byte[] segment = new byte[length + 2];
                segment[0] = (byte) first;
                segment[1] = (byte) marker;
                segment[2] = (byte) high;
                segment[3] = (byte) low;
                readFully(segment, 4, length - 2);
                pending = segment;
                scanDataAfterPending = marker == 0xda;
                return read();
            }

            pending = new byte[] {(byte) first, (byte) marker};
            return read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (scanData) {
                return in.read(buffer, offset, length);
            }
            int count = 0;
            while (count < length) {
                int value = read();
                if (value < 0) {
                    return count == 0 ? -1 : count;
                }
                buffer[offset + count++] = (byte) value;
            }
            return count;
        }

        private boolean hasLength(int marker) {
            return marker != 0xd8 && marker != 0xd9
                    && !(marker >= 0xd0 && marker <= 0xd7) && marker != 0x01;
        }

        private void readFully(byte[] buffer, int offset, int length) throws IOException {
            int read = 0;
            while (read < length) {
                int count = in.read(buffer, offset + read, length - read);
                if (count < 0) {
                    throw new IOException("Truncated JPEG segment");
                }
                read += count;
            }
        }

        private void skipFully(long bytes) throws IOException {
            long remaining = bytes;
            while (remaining > 0L) {
                long skipped = in.skip(remaining);
                if (skipped > 0L) {
                    remaining -= skipped;
                    continue;
                }
                if (in.read() < 0) {
                    throw new IOException("Truncated JPEG metadata segment");
                }
                remaining--;
            }
        }
    }
}
