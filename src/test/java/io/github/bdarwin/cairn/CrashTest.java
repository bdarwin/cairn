package io.github.bdarwin.cairn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kills the server while it writes an object, restarts it on the same data directory, and checks
 * the object is whole: the old one if the crash came before the commit (the rename of {@code .meta}),
 * the new one after. The server runs in a child JVM so that the kill is real. A killed process
 * leaves the page cache to the kernel, so this proves the write order, not survival of power loss.
 */
class CrashTest {

    @TempDir
    Path data;

    @Test
    void killedWhileStreamingTheBodyLeavesTheOldObject() throws Exception {
        byte[] v1 = random(3_000_000, 1);
        try (Child c = Child.start(data, null); S3Client s3 = c.client()) {
            s3.createBucket(b -> b.bucket("items"));
            s3.putObject(b -> b.bucket("items").key("record"), RequestBody.fromBytes(v1));
        }
        AtomicLong sent = new AtomicLong();
        try (Child c = Child.start(data, null); S3Client s3 = c.client()) {
            // A body that sends 4 MB and then waits forever: the server is mid-stream when it dies.
            InputStream slow = new InputStream() {
                public int read() {
                    return read(new byte[1], 0, 1) < 0 ? -1 : 0;
                }

                public int read(byte[] b, int off, int len) {
                    if (sent.get() >= 4_000_000) {
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return -1;
                    }
                    int n = (int) Math.min(len, 4_000_000 - sent.get());
                    java.util.Arrays.fill(b, off, off + n, (byte) 7);
                    sent.addAndGet(n);
                    return n;
                }
            };
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    s3.putObject(b -> b.bucket("items").key("record"),
                            RequestBody.fromContentProvider(ContentStreamProvider.fromInputStream(slow), 10_000_000, "application/octet-stream"));
                } catch (RuntimeException expected) {
                    // the server died
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (largestDataFile(data) < 2_000_000) {
                assertTrue(System.nanoTime() < deadline, "server never received the new body");
                Thread.sleep(20);
            }
            c.kill();
            writer.interrupt();
        }
        try (Child c = Child.start(data, null); S3Client s3 = c.client()) {
            assertArrayEquals(v1, s3.getObjectAsBytes(b -> b.bucket("items").key("record")).asByteArray());
            byte[] v3 = random(500_000, 3);
            s3.putObject(b -> b.bucket("items").key("record"), RequestBody.fromBytes(v3));
            assertArrayEquals(v3, s3.getObjectAsBytes(b -> b.bucket("items").key("record")).asByteArray());
        }
    }

    @ParameterizedTest(name = "crash at {0}, {1} bytes")
    @CsvSource({
            "data-written, 3000000, old",
            "data-forced, 3000000, old",
            "meta-forced, 3000000, old",
            "meta-forced, 1000, old",
            "renamed, 3000000, new",
            "renamed, 1000, new",
    })
    void crashAtEachStepLeavesAWholeObject(String point, int size, String survivor) throws Exception {
        byte[] v1 = random(size, 1);
        byte[] v2 = random(size, 2);
        try (Child c = Child.start(data, null); S3Client s3 = c.client()) {
            s3.createBucket(b -> b.bucket("items"));
            s3.putObject(b -> b.bucket("items").key("a/record"), RequestBody.fromBytes(v1));
        }
        try (Child c = Child.start(data, point); S3Client s3 = c.client()) {
            assertThrows(RuntimeException.class, () -> s3.putObject(b -> b.bucket("items").key("a/record"), RequestBody.fromBytes(v2)));
            assertEquals(99, c.waitForExit(), "the server died at the crash point");
        }
        try (Child c = Child.start(data, null); S3Client s3 = c.client()) {
            byte[] expected = survivor.equals("old") ? v1 : v2;
            assertArrayEquals(expected, s3.getObjectAsBytes(b -> b.bucket("items").key("a/record")).asByteArray());
            byte[] v3 = random(size, 3);
            s3.putObject(b -> b.bucket("items").key("a/record"), RequestBody.fromBytes(v3));
            assertArrayEquals(v3, s3.getObjectAsBytes(b -> b.bucket("items").key("a/record")).asByteArray());
        }
    }

    private static long largestDataFile(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().startsWith(".data-")).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).max().orElse(0);
        }
    }

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** cairn's {@link Main} in its own JVM. */
    static final class Child implements AutoCloseable {
        final Process process;
        final URI endpoint;

        private Child(Process process, URI endpoint) {
            this.process = process;
            this.endpoint = endpoint;
        }

        static Child start(Path data, String crashAt) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            if (crashAt != null) cmd.add("-Dcairn.test.crashAt=" + crashAt);
            cmd.addAll(List.of(Main.class.getName(), "--data", data.toString(), "--port", "0", "--bind", "127.0.0.1"));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("CAIRN_ACCESS_KEY", TestServer.ACCESS_KEY);
            pb.environment().put("CAIRN_SECRET_KEY", TestServer.SECRET_KEY);
            Process p = pb.start();
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line = out.readLine();
            if (line == null || !line.startsWith("cairn listening on ")) {
                p.destroyForcibly();
                throw new IOException("server did not start: " + line);
            }
            Thread.ofVirtual().start(() -> out.lines().forEach(l -> { }));
            String url = line.substring("cairn listening on ".length(), line.indexOf(','));
            return new Child(p, URI.create(url));
        }

        S3Client client() {
            return S3Client.builder()
                    .endpointOverride(endpoint)
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                    .forcePathStyle(true)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
                    .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()))
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(TestServer.ACCESS_KEY, TestServer.SECRET_KEY)))
                    .build();
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }

        int waitForExit() throws InterruptedException {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            return process.exitValue();
        }

        @Override
        public void close() throws InterruptedException {
            if (process.isAlive()) kill();
        }
    }
}
