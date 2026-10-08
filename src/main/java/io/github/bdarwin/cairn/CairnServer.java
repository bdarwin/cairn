package io.github.bdarwin.cairn;

import com.sun.net.httpserver.HttpServer;
import io.github.bdarwin.cairn.internal.auth.Authenticator;
import io.github.bdarwin.cairn.internal.http.S3Handler;
import io.github.bdarwin.cairn.internal.store.LocalObjectStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An S3-compatible object server over a local data directory.
 *
 * <pre>{@code
 * try (CairnServer server = CairnServer.builder(Path.of("/srv/cairn"))
 *         .port(9000)
 *         .credentials("AKEXAMPLE", "secret-example")
 *         .start()) {
 *     System.out.println(server.endpoint());
 *     ...
 * }
 * }</pre>
 */
public final class CairnServer implements AutoCloseable {

    private final HttpServer http;
    private final ExecutorService executor;
    private final LocalObjectStore store;

    private CairnServer(HttpServer http, ExecutorService executor, LocalObjectStore store) {
        this.http = http;
        this.executor = executor;
        this.store = store;
    }

    public static Builder builder(Path dataDirectory) {
        return new Builder(dataDirectory);
    }

    /** The port the server listens on (useful when it was started on port 0). */
    public int port() {
        return http.getAddress().getPort();
    }

    /** {@code http://host:port}, the endpoint to give S3 clients. */
    public URI endpoint() {
        String host = http.getAddress().getAddress().isAnyLocalAddress() ? "localhost" : http.getAddress().getHostString();
        return URI.create("http://" + host + ":" + port());
    }

    public Path dataDirectory() {
        return store.root();
    }

    /** Stops accepting requests and closes connections at once. */
    @Override
    public void close() {
        http.stop(0);
        executor.close();
    }

    public static final class Builder {
        private final Path dataDirectory;
        private String bindAddress = "0.0.0.0";
        private int port = 9000;
        private String region = "us-east-1";
        private final Map<String, String> credentials = new LinkedHashMap<>();
        private Clock clock = Clock.systemUTC();

        private Builder(Path dataDirectory) {
            this.dataDirectory = Objects.requireNonNull(dataDirectory);
        }

        /** Address to listen on; default all interfaces. */
        public Builder bindAddress(String address) {
            this.bindAddress = Objects.requireNonNull(address);
            return this;
        }

        /** Port to listen on; default 9000, 0 for any free port. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** The region reported to clients ({@code GetBucketLocation}); default {@code us-east-1}. Requests signed for any region are accepted. */
        public Builder region(String region) {
            this.region = Objects.requireNonNull(region);
            return this;
        }

        /** Adds an access key and its secret. At least one is required. */
        public Builder credentials(String accessKey, String secretKey) {
            credentials.put(Objects.requireNonNull(accessKey), Objects.requireNonNull(secretKey));
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public CairnServer start() throws IOException {
            if (credentials.isEmpty()) throw new IllegalStateException("no credentials configured");
            LocalObjectStore store = new LocalObjectStore(dataDirectory, clock);
            HttpServer http = HttpServer.create(new InetSocketAddress(bindAddress, port), 1024);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            http.setExecutor(executor);
            http.createContext("/", new S3Handler(store, new Authenticator(credentials, clock), region));
            http.start();
            return new CairnServer(http, executor, store);
        }
    }
}
