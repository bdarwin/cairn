package io.github.bdarwin.cairn.internal.checksum;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** The {@code x-amz-checksum-*} algorithms S3 accepts. Values travel as base64 of the big-endian digest. */
public enum ChecksumAlgorithm {
    CRC32, CRC32C, CRC64NVME, SHA1, SHA256;

    /** The header (and trailer) name carrying this checksum, e.g. {@code x-amz-checksum-crc32}. */
    public String headerName() {
        return "x-amz-checksum-" + name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code CRC32}, {@code crc32}, {@code CRC64NVME} as sent in {@code x-amz-sdk-checksum-algorithm}. */
    public static ChecksumAlgorithm parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The algorithm whose header is {@code name} (any case), or null. */
    public static ChecksumAlgorithm fromHeaderName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.startsWith("x-amz-checksum-")) return null;
        return parse(n.substring("x-amz-checksum-".length()));
    }

    public Hasher newHasher() {
        return switch (this) {
            case CRC32 -> new ChecksumHasher(new CRC32(), 4);
            case CRC32C -> new ChecksumHasher(new CRC32C(), 4);
            case CRC64NVME -> new ChecksumHasher(new Crc64Nvme(), 8);
            case SHA1 -> new DigestHasher("SHA-1");
            case SHA256 -> new DigestHasher("SHA-256");
        };
    }

    /** A running checksum or digest. */
    public interface Hasher {
        void update(byte[] b, int off, int len);

        byte[] digest();

        default String base64() {
            return Base64.getEncoder().encodeToString(digest());
        }
    }

    private record ChecksumHasher(Checksum c, int bytes) implements Hasher {
        public void update(byte[] b, int off, int len) {
            c.update(b, off, len);
        }

        public byte[] digest() {
            byte[] all = ByteBuffer.allocate(8).putLong(c.getValue()).array();
            return java.util.Arrays.copyOfRange(all, 8 - bytes, 8);
        }
    }

    private static final class DigestHasher implements Hasher {
        private final MessageDigest md;

        DigestHasher(String alg) {
            try {
                md = MessageDigest.getInstance(alg);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public void update(byte[] b, int off, int len) {
            md.update(b, off, len);
        }

        public byte[] digest() {
            return md.digest();
        }
    }
}
