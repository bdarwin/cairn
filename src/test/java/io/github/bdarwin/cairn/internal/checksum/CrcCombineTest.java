package io.github.bdarwin.cairn.internal.checksum;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrcCombineTest {

    @Test
    void combinedEqualsTheCrcOfTheJoinedBytes() {
        Random rnd = new Random(8);
        for (ChecksumAlgorithm alg : new ChecksumAlgorithm[] {ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.CRC64NVME}) {
            CrcCombine c = CrcCombine.of(alg);
            for (int i = 0; i < 300; i++) {
                byte[] a = new byte[rnd.nextInt(3000)], b = new byte[rnd.nextInt(3000)];
                rnd.nextBytes(a);
                rnd.nextBytes(b);
                byte[] ab = Arrays.copyOf(a, a.length + b.length);
                System.arraycopy(b, 0, ab, a.length, b.length);
                assertEquals(value(alg, ab, c.width()), c.combine(value(alg, a, c.width()), value(alg, b, c.width()), b.length),
                        alg + " " + a.length + "+" + b.length);
            }
        }
    }

    static long value(ChecksumAlgorithm alg, byte[] b, int width) {
        ChecksumAlgorithm.Hasher h = alg.newHasher();
        h.update(b, 0, b.length);
        byte[] d = h.digest();
        long v = 0;
        for (byte x : d) v = (v << 8) | (x & 0xff);
        return v;
    }
}
