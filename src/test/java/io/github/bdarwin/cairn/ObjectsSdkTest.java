package io.github.bdarwin.cairn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectsSdkTest {

    @TempDir
    Path dir;
    TestServer server;
    S3Client s3;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(dir);
        s3 = server.client();
        s3.createBucket(b -> b.bucket("items"));
    }

    @AfterEach
    void stop() {
        s3.close();
        server.close();
    }

    @Test
    void buckets() {
        s3.createBucket(b -> b.bucket("notes"));
        assertEquals(List.of("items", "notes"), s3.listBuckets().buckets().stream().map(Bucket::name).toList());
        s3.headBucket(b -> b.bucket("notes"));
        assertThrows(BucketAlreadyOwnedByYouException.class, () -> s3.createBucket(b -> b.bucket("notes")));
        assertThrows(NoSuchBucketException.class, () -> s3.headBucket(b -> b.bucket("absent")));
        s3.putObject(b -> b.bucket("notes").key("n"), RequestBody.fromString("x"));
        assertEquals("BucketNotEmpty", code(() -> s3.deleteBucket(b -> b.bucket("notes"))));
        s3.deleteObject(b -> b.bucket("notes").key("n"));
        s3.deleteBucket(b -> b.bucket("notes"));
        assertThrows(NoSuchBucketException.class, () -> s3.headBucket(b -> b.bucket("notes")));
        assertThrows(NoSuchBucketException.class, () -> s3.deleteBucket(b -> b.bucket("notes")));
    }

    @Test
    void smallAndLargeObjectsRoundTripByteForByte() throws Exception {
        Random rnd = new Random(5);
        for (int size : new int[] {0, 1, 1000, 128 * 1024 - 1, 128 * 1024, 128 * 1024 + 1, 3 * 1024 * 1024 + 7}) {
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            PutObjectResponse put = s3.putObject(b -> b.bucket("items").key("file-" + size), RequestBody.fromBytes(data));
            assertEquals("\"" + md5(data) + "\"", put.eTag());
            ResponseBytes<GetObjectResponse> got = s3.getObjectAsBytes(b -> b.bucket("items").key("file-" + size));
            assertArrayEquals(data, got.asByteArray(), "size " + size);
            assertEquals(put.eTag(), got.response().eTag());
            assertEquals(size, got.response().contentLength());
        }
    }

    @Test
    void streamingBodiesAndLegacySigning() {
        byte[] data = new byte[700_000];
        new Random(1).nextBytes(data);
        s3.putObject(b -> b.bucket("items").key("stream"), RequestBody.fromInputStream(new ByteArrayInputStream(data), data.length));
        assertArrayEquals(data, s3.getObjectAsBytes(b -> b.bucket("items").key("stream")).asByteArray());
        try (S3Client legacy = server.client(RequestChecksumCalculation.WHEN_REQUIRED, TestServer.SECRET_KEY)) {
            legacy.putObject(b -> b.bucket("items").key("legacy"), RequestBody.fromBytes(data));
            assertArrayEquals(data, legacy.getObjectAsBytes(b -> b.bucket("items").key("legacy")).asByteArray());
        }
    }

    @Test
    void everyChecksumAlgorithmIsVerifiedAndReturned() {
        byte[] data = "a reading worth keeping".getBytes(StandardCharsets.UTF_8);
        for (ChecksumAlgorithm alg : List.of(ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32_C, ChecksumAlgorithm.SHA1,
                ChecksumAlgorithm.SHA256, ChecksumAlgorithm.CRC64_NVME)) {
            String key = "sum-" + alg;
            s3.putObject(b -> b.bucket("items").key(key).checksumAlgorithm(alg), RequestBody.fromBytes(data));
            HeadObjectResponse head = s3.headObject(b -> b.bucket("items").key(key).checksumMode(ChecksumMode.ENABLED));
            String value = switch (alg) {
                case CRC32 -> head.checksumCRC32();
                case CRC32_C -> head.checksumCRC32C();
                case SHA1 -> head.checksumSHA1();
                case SHA256 -> head.checksumSHA256();
                case CRC64_NVME -> head.checksumCRC64NVME();
                default -> null;
            };
            assertNotNull(value, alg.toString());
            // A GET with checksum mode makes the SDK validate the bytes against the returned checksum.
            assertArrayEquals(data, s3.getObjectAsBytes(b -> b.bucket("items").key(key).checksumMode(ChecksumMode.ENABLED)).asByteArray());
        }
    }

    @Test
    void wrongChecksumOrContentMd5IsRefused() {
        byte[] data = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
        assertEquals("BadDigest", code(() -> s3.putObject(b -> b.bucket("items").key("bad").checksumCRC32("AAAAAA=="), RequestBody.fromBytes(data))));
        assertEquals("BadDigest", code(() -> s3.putObject(b -> b.bucket("items").key("bad")
                .contentMD5(Base64.getEncoder().encodeToString(new byte[16])), RequestBody.fromBytes(data))));
        assertThrows(NoSuchKeyException.class, () -> s3.headObject(b -> b.bucket("items").key("bad")));
    }

    @Test
    void rangesServeExactlyTheBytesAsked() {
        byte[] data = new byte[300_000];
        new Random(9).nextBytes(data);
        s3.putObject(b -> b.bucket("items").key("ranged"), RequestBody.fromBytes(data));
        Object[][] cases = {{"bytes=0-0", 0, 0}, {"bytes=0-9", 0, 9}, {"bytes=299990-", 299990, 299999}, {"bytes=-10", 299990, 299999},
                {"bytes=100-299999", 100, 299999}, {"bytes=100-999999", 100, 299999}, {"bytes=-999999", 0, 299999}};
        for (Object[] c : cases) {
            ResponseBytes<GetObjectResponse> r = s3.getObjectAsBytes(b -> b.bucket("items").key("ranged").range((String) c[0]));
            int from = (int) c[1], to = (int) c[2];
            assertArrayEquals(java.util.Arrays.copyOfRange(data, from, to + 1), r.asByteArray(), (String) c[0]);
            assertEquals("bytes " + from + "-" + to + "/300000", r.response().contentRange());
        }
        assertEquals("InvalidRange", code(() -> s3.getObjectAsBytes(b -> b.bucket("items").key("ranged").range("bytes=300000-"))));
        // A range S3 would ignore: the whole object.
        assertEquals(300000, s3.getObjectAsBytes(b -> b.bucket("items").key("ranged").range("bytes=5-1")).asByteArray().length);
        // A small (inline) object too.
        s3.putObject(b -> b.bucket("items").key("tiny"), RequestBody.fromString("0123456789"));
        assertEquals("345", s3.getObjectAsBytes(b -> b.bucket("items").key("tiny").range("bytes=3-5")).asUtf8String());
    }

    @Test
    void metadataIsStoredAndReturned() {
        s3.putObject(b -> b.bucket("items").key("note.txt").contentType("text/plain; charset=utf-8")
                        .metadata(Map.of("Owner", "someone", "kind", "reading")).cacheControl("max-age=60").contentDisposition("inline"),
                RequestBody.fromString("hello"));
        HeadObjectResponse h = s3.headObject(b -> b.bucket("items").key("note.txt"));
        assertEquals("text/plain; charset=utf-8", h.contentType());
        assertEquals(Map.of("owner", "someone", "kind", "reading"), h.metadata());
        assertEquals("max-age=60", h.cacheControl());
        assertEquals("inline", h.contentDisposition());
        assertEquals(5, h.contentLength());
        assertNotNull(h.lastModified());
        assertEquals("binary/octet-stream", s3.headObject(b -> b.bucket("items").key("note.txt")).contentType().equals("text/plain; charset=utf-8")
                ? "binary/octet-stream" : "");
        s3.putObject(b -> b.bucket("items").key("untyped"), RequestBody.fromBytes(new byte[3]));
        assertNull(s3.headObject(b -> b.bucket("items").key("untyped")).contentEncoding(), "aws-chunked is not stored as the object's encoding");
    }

    @Test
    void overwriteAndDelete() throws Exception {
        s3.putObject(b -> b.bucket("items").key("k"), RequestBody.fromBytes(new byte[200_000]));
        s3.putObject(b -> b.bucket("items").key("k"), RequestBody.fromString("short now"));
        assertEquals("short now", s3.getObjectAsBytes(b -> b.bucket("items").key("k")).asUtf8String());
        try (var files = Files.walk(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().startsWith(".data-")).count(), "old data file removed");
        }
        s3.deleteObject(b -> b.bucket("items").key("k"));
        assertThrows(NoSuchKeyException.class, () -> s3.getObjectAsBytes(b -> b.bucket("items").key("k")));
        assertThrows(NoSuchKeyException.class, () -> s3.headObject(b -> b.bucket("items").key("k")));
        s3.deleteObject(b -> b.bucket("items").key("k"));
        assertThrows(NoSuchBucketException.class, () -> s3.getObjectAsBytes(b -> b.bucket("absent").key("k")));
    }

    @Test
    void keysThatAFilesystemCannotHoldAsTheyAreRoundTrip() throws Exception {
        List<String> keys = List.of("note", "Note", "NOTE", Normalizer.normalize("café", Normalizer.Form.NFC),
                Normalizer.normalize("café", Normalizer.Form.NFD), "a", "a/", "a//", "a/b", "a//b", "a/./b", "a/../b", ".", "..", "./x", "../x",
                "x/.", "x/..", "/lead", "//lead", "tab\tand\nnewline", "ctl\u0001\u007f", "%", "%41", "+plus +", "sp ace", "q?uery&x=y#frag",
                "日本語/キー", "emoji/😀", "x".repeat(1024), ("seg/").repeat(255) + "end", "A".repeat(300) + "/b", "a".repeat(250) + "/" + "b".repeat(250));
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String body = "item " + i;
            s3.putObject(b -> b.bucket("items").key(key), RequestBody.fromString(body));
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            ResponseBytes<GetObjectResponse> got = s3.getObjectAsBytes(b -> b.bucket("items").key(key));
            assertEquals("item " + i, got.asUtf8String(), () -> "key " + key);
        }
        assertEquals("KeyTooLongError", code(() -> s3.putObject(b -> b.bucket("items").key("y".repeat(1025)), RequestBody.fromString("x"))));
        for (String key : keys) s3.deleteObject(b -> b.bucket("items").key(key));
        try (var files = Files.walk(dir.resolve("items"))) {
            List<Path> left = files.filter(p -> !p.equals(dir.resolve("items")) && !p.getFileName().toString().equals(".bucket")).toList();
            assertTrue(left.isEmpty(), "deleting every key leaves no directories: " + left);
        }
    }

    @Test
    void wrongSecretIsSignatureDoesNotMatch() {
        try (S3Client bad = server.client(RequestChecksumCalculation.WHEN_SUPPORTED, "not-the-secret")) {
            S3Exception e = assertThrows(S3Exception.class, () -> bad.putObject(b -> b.bucket("items").key("k"), RequestBody.fromString("x")));
            assertEquals(403, e.statusCode());
            assertEquals("SignatureDoesNotMatch", e.awsErrorDetails().errorCode());
            assertEquals("SignatureDoesNotMatch", code(() -> bad.getObjectAsBytes(b -> b.bucket("items").key("k"))));
        }
        assertFalse(Files.exists(dir.resolve("items").resolve("k")));
    }

    static String code(Runnable r) {
        return assertThrows(S3Exception.class, r::run).awsErrorDetails().errorCode();
    }

    static void assertNull(Object o, String message) {
        org.junit.jupiter.api.Assertions.assertNull(o, message);
    }

    static String md5(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
    }
}
