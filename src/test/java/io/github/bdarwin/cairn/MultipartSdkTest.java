package io.github.bdarwin.cairn;

import io.github.bdarwin.cairn.internal.checksum.ChecksumAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartSdkTest {

    static final int MIB = 1 << 20;

    @TempDir
    Path dir;
    TestServer server;
    S3Client s3;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(dir, false);
        s3 = server.client();
        s3.createBucket(b -> b.bucket("items"));
    }

    @AfterEach
    void stop() {
        s3.close();
        server.close();
    }

    @Test
    void partsBecomeOneObjectWithS3sETag() throws Exception {
        byte[][] parts = {random(5 * MIB, 1), random(5 * MIB, 2), random(MIB + 3, 3)};
        String id = s3.createMultipartUpload(b -> b.bucket("items").key("big/record").contentType("application/x-record")).uploadId();
        List<CompletedPart> done = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            int n = i + 1;
            UploadPartResponse r = s3.uploadPart(b -> b.bucket("items").key("big/record").uploadId(id).partNumber(n), RequestBody.fromBytes(parts[n - 1]));
            assertEquals("\"" + md5Hex(parts[i]) + "\"", r.eTag());
            done.add(CompletedPart.builder().partNumber(n).eTag(r.eTag()).build());
        }
        ListPartsResponse listed = s3.listParts(b -> b.bucket("items").key("big/record").uploadId(id));
        assertEquals(List.of(1, 2, 3), listed.parts().stream().map(Part::partNumber).toList());
        assertEquals(5L * MIB, listed.parts().getFirst().size());
        assertEquals(1, s3.listMultipartUploads(b -> b.bucket("items")).uploads().size());

        CompleteMultipartUploadResponse c = s3.completeMultipartUpload(b -> b.bucket("items").key("big/record").uploadId(id)
                .multipartUpload(m -> m.parts(done)));
        // S3's multipart ETag: MD5 of the parts' binary MD5s, then "-" and the part count.
        MessageDigest md = MessageDigest.getInstance("MD5");
        for (byte[] p : parts) md.update(MessageDigest.getInstance("MD5").digest(p));
        assertEquals("\"" + HexFormat.of().formatHex(md.digest()) + "-3\"", c.eTag());

        byte[] whole = concat(parts);
        assertArrayEquals(whole, s3.getObjectAsBytes(b -> b.bucket("items").key("big/record")).asByteArray());
        HeadObjectResponse h = s3.headObject(b -> b.bucket("items").key("big/record"));
        assertEquals(whole.length, h.contentLength());
        assertEquals("application/x-record", h.contentType());
        assertEquals(c.eTag(), h.eTag());
        // Ranges that cross part boundaries.
        for (int[] range : new int[][] {{5 * MIB - 10, 5 * MIB + 10}, {0, whole.length - 1}, {10 * MIB - 1, 10 * MIB}, {whole.length - 5, whole.length - 1}}) {
            byte[] got = s3.getObjectAsBytes(b -> b.bucket("items").key("big/record").range("bytes=" + range[0] + "-" + range[1])).asByteArray();
            assertArrayEquals(Arrays.copyOfRange(whole, range[0], range[1] + 1), got);
        }
        assertTrue(s3.listMultipartUploads(b -> b.bucket("items")).uploads().isEmpty());
        assertEquals(List.of(), files(".cairn/uploads"), "nothing left of the upload");
        assertEquals(3, files("items").stream().filter(f -> f.startsWith(".data-")).count());

        s3.putObject(b -> b.bucket("items").key("big/record"), RequestBody.fromString("small now"));
        assertEquals(0, files("items").stream().filter(f -> f.startsWith(".data-")).count(), "the parts go when the object is replaced");
    }

    @Test
    void abortRemovesEverything() throws Exception {
        String id = s3.createMultipartUpload(b -> b.bucket("items").key("k")).uploadId();
        s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(1), RequestBody.fromBytes(random(MIB, 1)));
        s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(2), RequestBody.fromBytes(random(MIB, 2)));
        assertFalse(files(".cairn/uploads").isEmpty());
        s3.abortMultipartUpload(b -> b.bucket("items").key("k").uploadId(id));
        assertEquals(List.of(), files(".cairn/uploads"));
        assertTrue(s3.listMultipartUploads(b -> b.bucket("items")).uploads().isEmpty());
        assertThrows(NoSuchUploadException.class, () -> s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(3),
                RequestBody.fromBytes(new byte[10])));
        assertThrows(NoSuchUploadException.class, () -> s3.abortMultipartUpload(b -> b.bucket("items").key("k").uploadId(id)));
        assertThrows(NoSuchUploadException.class, () -> s3.listParts(b -> b.bucket("items").key("other").uploadId(id)));
    }

    @Test
    void completionIsValidated() {
        String id = s3.createMultipartUpload(b -> b.bucket("items").key("k")).uploadId();
        String e1 = s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(1), RequestBody.fromBytes(random(MIB, 1))).eTag();
        String e2 = s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(2), RequestBody.fromBytes(random(MIB, 2))).eTag();
        assertEquals("EntityTooSmall", code(() -> complete(id, part(1, e1), part(2, e2))));
        assertEquals("InvalidPartOrder", code(() -> complete(id, part(2, e2), part(1, e1))));
        assertEquals("InvalidPart", code(() -> complete(id, part(1, e2))));
        assertEquals("InvalidPart", code(() -> complete(id, part(7, e1))));
        assertEquals("InvalidArgument", code(() -> s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(10001),
                RequestBody.fromBytes(new byte[1]))));
        // A single small part is fine: only parts before the last must be 5 MiB.
        complete(id, part(1, e1));
        assertEquals(MIB, s3.headObject(b -> b.bucket("items").key("k")).contentLength());
    }

    @Test
    void reuploadingAPartReplacesIt() throws Exception {
        String id = s3.createMultipartUpload(b -> b.bucket("items").key("k")).uploadId();
        s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(1), RequestBody.fromBytes(random(MIB, 1)));
        byte[] second = random(MIB, 9);
        String e = s3.uploadPart(b -> b.bucket("items").key("k").uploadId(id).partNumber(1), RequestBody.fromBytes(second)).eTag();
        assertEquals(1, files(".cairn/uploads").stream().filter(f -> f.startsWith("data-")).count());
        complete(id, part(1, e));
        assertArrayEquals(second, s3.getObjectAsBytes(b -> b.bucket("items").key("k")).asByteArray());
    }

    @Test
    void checksumsCompositeAndFullObject() throws Exception {
        byte[][] parts = {random(5 * MIB, 4), random(5 * MIB, 5), random(777, 6)};
        byte[] whole = concat(parts);
        record Case(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm sdk, ChecksumAlgorithm ours, ChecksumType type) {
        }
        for (Case c : List.of(new Case(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.CRC64_NVME, ChecksumAlgorithm.CRC64NVME, ChecksumType.FULL_OBJECT),
                new Case(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32, ChecksumType.FULL_OBJECT),
                new Case(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.CRC32_C, ChecksumAlgorithm.CRC32C, ChecksumType.COMPOSITE),
                new Case(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.SHA256, ChecksumAlgorithm.SHA256, ChecksumType.COMPOSITE))) {
            String key = "sum-" + c.ours();
            String id = s3.createMultipartUpload(b -> b.bucket("items").key(key).checksumAlgorithm(c.sdk()).checksumType(c.type())).uploadId();
            List<CompletedPart> done = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                int n = i + 1;
                UploadPartResponse r = s3.uploadPart(b -> b.bucket("items").key(key).uploadId(id).partNumber(n).checksumAlgorithm(c.sdk()),
                        RequestBody.fromBytes(parts[n - 1]));
                done.add(CompletedPart.builder().partNumber(n).eTag(r.eTag()).checksumCRC32(r.checksumCRC32()).checksumCRC32C(r.checksumCRC32C())
                        .checksumCRC64NVME(r.checksumCRC64NVME()).checksumSHA256(r.checksumSHA256()).build());
            }
            s3.completeMultipartUpload(b -> b.bucket("items").key(key).uploadId(id).multipartUpload(m -> m.parts(done)));
            HeadObjectResponse h = s3.headObject(b -> b.bucket("items").key(key).checksumMode(ChecksumMode.ENABLED));
            String value = switch (c.ours()) {
                case CRC64NVME -> h.checksumCRC64NVME();
                case CRC32 -> h.checksumCRC32();
                case CRC32C -> h.checksumCRC32C();
                default -> h.checksumSHA256();
            };
            if (c.type() == ChecksumType.FULL_OBJECT) {
                ChecksumAlgorithm.Hasher hasher = c.ours().newHasher();
                hasher.update(whole, 0, whole.length);
                assertEquals(hasher.base64(), value, "the full-object " + c.ours() + " of every byte");
            } else {
                assertTrue(value.endsWith("-3"), value);
            }
            assertEquals(c.type(), h.checksumType());
            // The SDK checks a full-object checksum against the bytes it reads.
            assertArrayEquals(whole, s3.getObjectAsBytes(b -> b.bucket("items").key(key).checksumMode(ChecksumMode.ENABLED)).asByteArray());
        }
    }

    /** The SDK's own multipart path: an async client that splits uploads itself. */
    @Test
    void sdkMultipartClient() {
        byte[] data = random(23 * MIB + 5, 7);
        try (S3AsyncClient async = S3AsyncClient.builder().endpointOverride(server.endpoint()).region(Region.US_EAST_1).forcePathStyle(true)
                .multipartEnabled(true)
                .multipartConfiguration(m -> m.minimumPartSizeInBytes(8L * MIB).thresholdInBytes(8L * MIB))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(TestServer.ACCESS_KEY, TestServer.SECRET_KEY)))
                .build()) {
            String etag = async.putObject(b -> b.bucket("items").key("async"), AsyncRequestBody.fromBytes(data)).join().eTag();
            assertTrue(etag.endsWith("-3\""), etag);
        }
        assertArrayEquals(data, s3.getObjectAsBytes(b -> b.bucket("items").key("async")).asByteArray());
    }

    @Test
    void deletingTheBucketTakesItsUploads() throws Exception {
        s3.createBucket(b -> b.bucket("other"));
        s3.createMultipartUpload(b -> b.bucket("other").key("k"));
        s3.deleteBucket(b -> b.bucket("other"));
        assertFalse(Files.exists(dir.resolve(".cairn/uploads/other")));
    }

    private void complete(String id, CompletedPart... parts) {
        s3.completeMultipartUpload(b -> b.bucket("items").key("k").uploadId(id).multipartUpload(m -> m.parts(parts)));
    }

    private static CompletedPart part(int n, String etag) {
        return CompletedPart.builder().partNumber(n).eTag(etag).build();
    }

    private List<String> files(String under) throws Exception {
        Path p = dir.resolve(under);
        if (!Files.exists(p)) return List.of();
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).map(f -> f.getFileName().toString()).filter(f -> !f.equals(".bucket")).toList();
        }
    }

    static String code(Runnable r) {
        return assertThrows(S3Exception.class, r::run).awsErrorDetails().errorCode();
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    static String md5Hex(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
    }
}
