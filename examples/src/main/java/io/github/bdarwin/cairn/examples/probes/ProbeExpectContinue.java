package io.github.bdarwin.cairn.examples.probes;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Probe 1: does the JDK's {@code com.sun.net.httpserver.HttpServer} answer {@code Expect: 100-continue},
 * and when - before the handler runs, or only once the handler starts reading the body?
 *
 * <p>A raw socket client sends the headers, waits up to 2 s for an interim response, then sends the
 * body. Every event is timestamped in milliseconds from the start of the exchange. The handler waits
 * 500 ms before touching the body, so "before the handler reads" and "when the handler reads" are
 * 500 ms apart.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=io.github.bdarwin.cairn.examples.probes.ProbeExpectContinue}
 */
public final class ProbeExpectContinue {

    static volatile long t0;

    static long ms() {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("java " + System.getProperty("java.version"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/read", ex -> {
            System.out.printf("  %4d ms  server: handler entered (%s)%n", ms(), ex.getRequestURI());
            sleep(500);
            System.out.printf("  %4d ms  server: handler starts reading body%n", ms());
            byte[] body = ex.getRequestBody().readAllBytes();
            System.out.printf("  %4d ms  server: handler read %d bytes%n", ms(), body.length);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/reject", ex -> {
            System.out.printf("  %4d ms  server: handler entered (%s), answering 403 without reading%n", ms(), ex.getRequestURI());
            byte[] msg = "denied".getBytes(StandardCharsets.US_ASCII);
            ex.sendResponseHeaders(403, msg.length);
            ex.getResponseBody().write(msg);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        run(port, "/read", true);
        run(port, "/reject", true);
        reuseAfterUnreadBody(port, 1_000);
        reuseAfterUnreadBody(port, 1_000_000);
        server.stop(0);
        System.exit(0);
    }

    static void run(int port, String path, boolean expect) throws Exception {
        System.out.println();
        System.out.println("PUT " + path + (expect ? " with Expect: 100-continue" : ""));
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            t0 = System.nanoTime();
            String head = "PUT " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n"
                    + (expect ? "Expect: 100-continue\r\n" : "") + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            System.out.printf("  %4d ms  client: headers sent%n", ms());
            String first = readSome(in);
            System.out.printf("  %4d ms  client: received %s%n", ms(), show(first));
            if (first == null || first.startsWith("HTTP/1.1 100")) {
                out.write("hello".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                System.out.printf("  %4d ms  client: body sent%n", ms());
                String next = readSome(in);
                System.out.printf("  %4d ms  client: received %s%n", ms(), show(next));
            } else {
                // Final response before the body: is the connection still usable?
                try {
                    out.write("hello".getBytes(StandardCharsets.US_ASCII));
                    out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    String next = readSome(in);
                    System.out.printf("  %4d ms  client: after sending the unread body + a second request: %s%n", ms(), show(next));
                } catch (Exception e) {
                    System.out.printf("  %4d ms  client: connection after early final response: %s%n", ms(), e);
                }
            }
        }
    }

    /** Handler answers without reading a body of {@code size} bytes; is the connection reused afterwards? */
    static void reuseAfterUnreadBody(int port, int size) throws Exception {
        System.out.println();
        System.out.println("PUT /reject with an unread " + size + "-byte body, then GET on the same connection");
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            t0 = System.nanoTime();
            out.write(("PUT /reject HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + size + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            // Write the body on its own thread: if the server stops reading, a large write blocks.
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    out.write(new byte[size]);
                    out.flush();
                    System.out.printf("  %4d ms  client: whole body written%n", ms());
                } catch (Exception e) {
                    System.out.printf("  %4d ms  client: body write failed: %s%n", ms(), e);
                }
            });
            System.out.printf("  %4d ms  client: received %s%n", ms(), show(readSome(in)));
            writer.join(3000);
            if (writer.isAlive()) {
                System.out.printf("  %4d ms  client: body write still blocked after 3 s (server stopped reading)%n", ms());
                return;
            }
            try {
                out.write("GET /reject HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                System.out.printf("  %4d ms  client: second request on same connection: %s%n", ms(), show(readSome(in)));
            } catch (Exception e) {
                System.out.printf("  %4d ms  client: second request on same connection failed: %s%n", ms(), e);
            }
        }
    }

    /** Reads one whole response: status line, headers, and a Content-Length body. Null on timeout. */
    static String readSome(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try {
            while (!sb.toString().endsWith("\r\n\r\n")) {
                int c = in.read();
                if (c < 0) return sb.isEmpty() ? "<EOF>" : sb + "<EOF>";
                sb.append((char) c);
            }
            var m = java.util.regex.Pattern.compile("(?i)content-length: *(\\d+)").matcher(sb);
            int len = m.find() ? Integer.parseInt(m.group(1)) : 0;
            if (!sb.toString().startsWith("HTTP/1.1 100")) {
                for (int i = 0; i < len; i++) sb.append((char) in.read());
            }
            return sb.toString();
        } catch (java.net.SocketTimeoutException e) {
            return sb.isEmpty() ? null : sb + "<timeout>";
        }
    }

    static String show(String s) {
        return s == null ? "nothing within 2 s" : s.replace("\r\n", "\\r\\n ");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}


/*
Output (2026-10-08, Apple M1 Max, macOS 26.5, JDK 21.0.11):

   java 21.0.11
   
   PUT /read with Expect: 100-continue
        1 ms  client: headers sent
       13 ms  server: handler entered (/read)
       13 ms  client: received HTTP/1.1 100 Continue\r\n Content-Length: 0\r\n \r\n 
       13 ms  client: body sent
      518 ms  server: handler starts reading body
      520 ms  server: handler read 5 bytes
      565 ms  client: received HTTP/1.1 200 OK\r\n Date: Thu, 08 Oct 2026 13:32:39 GMT\r\n Content-length: 0\r\n \r\n 
   
   PUT /reject with Expect: 100-continue
        0 ms  client: headers sent
        0 ms  server: handler entered (/reject), answering 403 without reading
        0 ms  client: received HTTP/1.1 100 Continue\r\n Content-Length: 0\r\n \r\n 
        1 ms  client: body sent
        1 ms  client: received HTTP/1.1 403 Forbidden\r\n Date: Thu, 08 Oct 2026 13:32:39 GMT\r\n Content-length: 6\r\n \r\n denied
   
   PUT /reject with an unread 1000-byte body, then GET on the same connection
        0 ms  server: handler entered (/reject), answering 403 without reading
        0 ms  client: whole body written
        0 ms  client: received HTTP/1.1 403 Forbidden\r\n Date: Thu, 08 Oct 2026 13:32:39 GMT\r\n Content-length: 6\r\n \r\n denied
        1 ms  server: handler entered (/reject), answering 403 without reading
        1 ms  client: second request on same connection: HTTP/1.1 403 Forbidden\r\n Date: Thu, 08 Oct 2026 13:32:39 GMT\r\n Content-length: 6\r\n \r\n denied
   
   PUT /reject with an unread 1000000-byte body, then GET on the same connection
        0 ms  server: handler entered (/reject), answering 403 without reading
        1 ms  client: whole body written
        0 ms  client: received HTTP/1.1 403 Forbidden\r\n Date: Thu, 08 Oct 2026 13:32:39 GMT\r\n Content-length: 6\r\n \r\n denied
        1 ms  client: second request on same connection failed: java.net.SocketException: Broken pipe
*/
