package io.github.bdarwin.cairn.examples.probes;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Found in milestone 5: does a client that stops reading its download hold up anyone else? The JDK
 * {@code HttpServer} serves 32 MiB from memory at {@code /big} and a few bytes at {@code /small}.
 * For each executor, the probe opens downloads of {@code /big} that never read (a 4 KiB receive
 * buffer, so the server blocks writing almost at once), in steps up to twice the number of cores,
 * then times one {@code GET /small} on a fresh connection, giving up after 5 s.
 *
 * <p>Up to JDK 23 a virtual thread blocked inside a monitor keeps its carrier thread, and the JDK
 * server writes a body inside one ({@code sun.net.httpserver.Request$WriteStream.write} is
 * synchronized). Run it on JDK 21 and on JDK 24 or later:
 * {@code java -cp target/classes io.github.bdarwin.cairn.examples.probes.ProbeStalledDownloads}
 */
public final class ProbeStalledDownloads {

    static final byte[] BIG = new byte[32 << 20];

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("java " + System.getProperty("java.version") + ", " + cores + " cores");
        for (String kind : List.of("virtual", "platform")) {
            System.out.println(kind + " threads:");
            for (int stalled : new int[]{cores / 2, cores - 1, cores, 2 * cores}) {
                ExecutorService executor = kind.equals("virtual") ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newCachedThreadPool();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
                server.setExecutor(executor);
                server.createContext("/big", ex -> {
                    ex.sendResponseHeaders(200, BIG.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(BIG);
                    } catch (IOException gone) {
                        // the client closed
                    }
                });
                server.createContext("/small", ex -> {
                    byte[] b = "a note".getBytes(StandardCharsets.US_ASCII);
                    ex.sendResponseHeaders(200, b.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(b);
                    }
                });
                server.start();
                int port = server.getAddress().getPort();
                List<Socket> sockets = new ArrayList<>();
                for (int i = 0; i < stalled; i++) {
                    Socket s = new Socket();
                    s.setReceiveBufferSize(4096);
                    s.connect(new InetSocketAddress("127.0.0.1", port));
                    s.getOutputStream().write(("GET /big HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    sockets.add(s);
                }
                Thread.sleep(500);
                long t = System.nanoTime();
                String result;
                try (Socket s = new Socket("127.0.0.1", port)) {
                    s.setSoTimeout(5000);
                    s.getOutputStream().write("GET /small HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    InputStream in = s.getInputStream();
                    String reply = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
                    result = reply.startsWith("HTTP/1.1 200") ? String.format("answered in %.1f ms", (System.nanoTime() - t) / 1e6) : "odd reply";
                } catch (SocketTimeoutException e) {
                    result = "no answer after 5 s";
                }
                System.out.printf("  %2d stalled downloads: GET /small %s%n", stalled, result);
                for (Socket s : sockets) s.close();
                server.stop(0);
                executor.shutdownNow();
            }
        }
    }
}

/*
Output (2026-11-07, Apple M1 Max, JDK 21.0.11 then JDK 25.0.5):

   java 21.0.11, 10 cores
   virtual threads:
      5 stalled downloads: GET /small answered in 17.1 ms
      9 stalled downloads: GET /small answered in 7.8 ms
     10 stalled downloads: GET /small no answer after 5 s
     20 stalled downloads: GET /small no answer after 5 s
   platform threads:
      5 stalled downloads: GET /small answered in 8.1 ms
      9 stalled downloads: GET /small answered in 8.2 ms
     10 stalled downloads: GET /small answered in 7.6 ms
     20 stalled downloads: GET /small answered in 1.8 ms
   java 25.0.5.0.3, 10 cores
   virtual threads:
      5 stalled downloads: GET /small answered in 13.1 ms
      9 stalled downloads: GET /small answered in 4.7 ms
     10 stalled downloads: GET /small answered in 7.6 ms
     20 stalled downloads: GET /small answered in 3.2 ms
   platform threads:
      5 stalled downloads: GET /small answered in 3.5 ms
      9 stalled downloads: GET /small answered in 7.7 ms
     10 stalled downloads: GET /small answered in 7.1 ms
     20 stalled downloads: GET /small answered in 1.3 ms
*/
