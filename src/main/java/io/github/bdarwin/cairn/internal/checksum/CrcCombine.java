package io.github.bdarwin.cairn.internal.checksum;

/**
 * The CRC of two byte strings joined, from the CRC of each and the length of the second, without
 * reading the bytes again (the method of zlib's {@code crc32_combine}). Holds for the reflected CRCs
 * whose initial value and final xor are all ones: CRC32, CRC32C and CRC64/NVME. A completed multipart
 * upload's full-object checksum is built this way from its parts' checksums.
 */
public final class CrcCombine {

    private final long poly;
    private final int width;
    private final long one;

    private CrcCombine(long reflectedPoly, int width) {
        this.poly = reflectedPoly;
        this.width = width;
        this.one = 1L << (width - 1);
    }

    public static final CrcCombine CRC32 = new CrcCombine(0xEDB88320L, 32);
    public static final CrcCombine CRC32C = new CrcCombine(0x82F63B78L, 32);
    public static final CrcCombine CRC64NVME = new CrcCombine(Long.reverse(0xAD93D23594C93659L), 64);

    public static CrcCombine of(ChecksumAlgorithm a) {
        return switch (a) {
            case CRC32 -> CRC32;
            case CRC32C -> CRC32C;
            case CRC64NVME -> CRC64NVME;
            default -> null;
        };
    }

    /** CRC of A followed by B, given CRC(A), CRC(B) and the length of B in bytes. */
    public long combine(long crcA, long crcB, long lengthB) {
        if (lengthB == 0) return crcA;
        return multiply(xPow8n(lengthB), crcA) ^ crcB;
    }

    /** Multiplies two polynomials modulo the CRC polynomial, in reflected bit order. */
    private long multiply(long a, long b) {
        long m = one, p = 0;
        while (true) {
            if ((a & m) != 0) {
                p ^= b;
                if ((a & (m - 1)) == 0) break;
            }
            m >>>= 1;
            b = (b & 1) != 0 ? (b >>> 1) ^ poly : b >>> 1;
        }
        return p;
    }

    /** x^(8n) modulo the polynomial: the effect of n zero bytes. */
    private long xPow8n(long n) {
        long result = one;
        long base = one >>> 8; // x^8
        while (n > 0) {
            if ((n & 1) != 0) result = multiply(result, base);
            base = multiply(base, base);
            n >>>= 1;
        }
        return result;
    }

    public int width() {
        return width;
    }
}
