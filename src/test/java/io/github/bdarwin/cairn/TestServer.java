package io.github.bdarwin.cairn;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** A cairn server on a free port over a temporary directory, with S3 clients for it. */
public final class TestServer implements AutoCloseable {

    public static final String ACCESS_KEY = "cairn-test-key";
    public static final String SECRET_KEY = "cairn-test-secret";

    public final CairnServer server;

    public TestServer(Path dataDir) throws IOException {
        this(dataDir, true);
    }

    /** {@code durable} false skips forces: for tests that write thousands of objects and are not about durability. */
    public TestServer(Path dataDir, boolean durable) throws IOException {
        server = CairnServer.builder(dataDir).bindAddress("127.0.0.1").port(0).credentials(ACCESS_KEY, SECRET_KEY).durable(durable).start();
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    public S3Client client() {
        return client(RequestChecksumCalculation.WHEN_SUPPORTED, SECRET_KEY);
    }

    public S3Client client(RequestChecksumCalculation checksums, String secret) {
        return client(endpoint(), checksums, secret);
    }

    public static S3Client client(URI endpoint, RequestChecksumCalculation checksums, String secret) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .requestChecksumCalculation(checksums)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, secret)))
                .build();
    }

    @Override
    public void close() {
        server.close();
    }
}
