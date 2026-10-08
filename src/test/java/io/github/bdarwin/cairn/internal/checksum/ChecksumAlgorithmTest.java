package io.github.bdarwin.cairn.internal.checksum;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumAlgorithmTest {

    @Test
    void crc64NvmeCheckValue() {
        Crc64Nvme c = new Crc64Nvme();
        byte[] b = "123456789".getBytes(StandardCharsets.US_ASCII);
        c.update(b, 0, b.length);
        assertEquals(0xAE8B14860A799888L, c.getValue());
    }

    @Test
    void crc64NvmeEightAtATimeMatchesByteAtATime() {
        Random rnd = new Random(3);
        for (int len = 0; len < 300; len++) {
            byte[] b = new byte[len];
            rnd.nextBytes(b);
            Crc64Nvme bulk = new Crc64Nvme(), single = new Crc64Nvme();
            bulk.update(b, 0, b.length);
            for (byte x : b) single.update(x);
            assertEquals(single.getValue(), bulk.getValue(), "length " + len);
        }
    }

    @Test
    void knownValues() {
        // Reference values for "hello", computed with Python's zlib and hashlib, and the aws CLI's awscrt for CRC32C and CRC64NVME.
        byte[] hello = "hello".getBytes(StandardCharsets.US_ASCII);
        assertEquals("NhCmhg==", digest(ChecksumAlgorithm.CRC32, hello));
        assertEquals("mnG7TA==", digest(ChecksumAlgorithm.CRC32C, hello));
        assertEquals("M3eFcAZSQlc=", digest(ChecksumAlgorithm.CRC64NVME, hello));
        assertEquals("qvTGHdzF6KLavt4PO0gs2a6pQ00=", digest(ChecksumAlgorithm.SHA1, hello));
        assertEquals("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=", digest(ChecksumAlgorithm.SHA256, hello));
    }

    private static String digest(ChecksumAlgorithm a, byte[] b) {
        ChecksumAlgorithm.Hasher h = a.newHasher();
        h.update(b, 0, b.length);
        return h.base64();
    }
}
