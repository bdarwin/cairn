package io.github.bdarwin.cairn.internal.checksum;

import java.util.zip.Checksum;

/**
 * CRC-64/NVME, the checksum the {@code aws} CLI sends by default ({@code x-amz-checksum-crc64nvme}).
 * Not in the JDK. Parameters: polynomial {@code 0xAD93D23594C93659}, reflected in and out, initial
 * value and final xor all ones; the check value for {@code "123456789"} is {@code 0xAE8B14860A799888}.
 * Table-driven, eight bytes at a time.
 */
public final class Crc64Nvme implements Checksum {

    private static final long POLY_REFLECTED = Long.reverse(0xAD93D23594C93659L);
    private static final long[][] TABLES = new long[8][256];

    static {
        for (int n = 0; n < 256; n++) {
            long c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ POLY_REFLECTED : c >>> 1;
            TABLES[0][n] = c;
        }
        for (int n = 0; n < 256; n++) {
            long c = TABLES[0][n];
            for (int t = 1; t < 8; t++) {
                c = TABLES[0][(int) (c & 0xff)] ^ (c >>> 8);
                TABLES[t][n] = c;
            }
        }
    }

    private long crc = -1L;

    @Override
    public void update(int b) {
        crc = TABLES[0][(int) ((crc ^ b) & 0xff)] ^ (crc >>> 8);
    }

    @Override
    public void update(byte[] b, int off, int len) {
        long c = crc;
        int end = off + len;
        long[] t0 = TABLES[0], t1 = TABLES[1], t2 = TABLES[2], t3 = TABLES[3];
        long[] t4 = TABLES[4], t5 = TABLES[5], t6 = TABLES[6], t7 = TABLES[7];
        while (end - off >= 8) {
            c ^= (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8 | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24
                    | (b[off + 4] & 0xffL) << 32 | (b[off + 5] & 0xffL) << 40 | (b[off + 6] & 0xffL) << 48 | (b[off + 7] & 0xffL) << 56;
            c = t7[(int) (c & 0xff)] ^ t6[(int) ((c >>> 8) & 0xff)] ^ t5[(int) ((c >>> 16) & 0xff)] ^ t4[(int) ((c >>> 24) & 0xff)]
                    ^ t3[(int) ((c >>> 32) & 0xff)] ^ t2[(int) ((c >>> 40) & 0xff)] ^ t1[(int) ((c >>> 48) & 0xff)] ^ t0[(int) (c >>> 56)];
            off += 8;
        }
        while (off < end) c = t0[(int) ((c ^ b[off++]) & 0xff)] ^ (c >>> 8);
        crc = c;
    }

    @Override
    public long getValue() {
        return ~crc;
    }

    @Override
    public void reset() {
        crc = -1L;
    }
}
