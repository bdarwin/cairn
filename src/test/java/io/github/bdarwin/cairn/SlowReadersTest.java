package io.github.bdarwin.cairn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Clients that read slowly must not stop cairn serving anyone else. On JDK 21 a virtual thread
 * blocked writing a response holds its carrier thread (the JDK HttpServer writes under a monitor), so
 * as many stalled downloads as there are cores once froze the whole server. DuckDB found it: its
 * prefetching reader opens dozens of ranged GETs at once.
 */
class SlowReadersTest {

    @TempDir
    Path dir;

    @Test
    void stalledDownloadsDoNotStopOtherRequests() throws Exception {
        try (TestServer server = new TestServer(dir, false); S3Client s3 = server.client()) {
            s3.createBucket(b -> b.bucket("items"));
            s3.putObject(b -> b.bucket("items").key("big"), RequestBody.fromBytes(new byte[32 << 20]));
            s3.putObject(b -> b.bucket("items").key("small"), RequestBody.fromString("a note"));

            RawRequests raw = new RawRequests(server.endpoint());
            int stalled = 2 * Runtime.getRuntime().availableProcessors();
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int i = 0; i < stalled; i++) {
                    Socket s = new Socket();
                    s.setReceiveBufferSize(4096);
                    s.connect(new InetSocketAddress("127.0.0.1", server.server.port()));
                    StringBuilder req = new StringBuilder("GET /items/big HTTP/1.1\r\n");
                    for (Map.Entry<String, List<String>> h : raw.signedHeaders("GET", "/items/big").entrySet()) {
                        for (String v : h.getValue()) req.append(h.getKey()).append(": ").append(v).append("\r\n");
                    }
                    OutputStream out = s.getOutputStream();
                    out.write(req.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    sockets.add(s); // and never read from it
                }
                Thread.sleep(500); // let every stalled download fill its socket buffers

                CompletableFuture<String> other = CompletableFuture.supplyAsync(() -> {
                    s3.headObject(b -> b.bucket("items").key("big"));
                    return s3.getObjectAsBytes(b -> b.bucket("items").key("small")).asUtf8String();
                });
                assertEquals("a note", other.get(10, TimeUnit.SECONDS), "another client is served while " + stalled + " downloads are stalled");
            } finally {
                for (Socket s : sockets) s.close();
            }
        }
    }
}
