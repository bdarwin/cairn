package io.github.bdarwin.cairn.examples;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A TCP pass-through in front of a server that writes down each HTTP/1.1 exchange: the request line,
 * its {@code Range}, the status, and how many body bytes went each way. Clients sign the {@code Host}
 * they connect to, and the proxy forwards it unchanged, so signatures still verify.
 *
 * <p>Bodies are framed by {@code Content-Length} only, which is what cairn and DuckDB send; requests on
 * one connection are taken to be answered in order, as HTTP/1.1 requires.
 */
public final class CountingProxy implements AutoCloseable {

    /** One request and its response. */
    public record Exchange(String method, String target, String range, long requestBody, int status, long responseBody) {
        @Override
        public String toString() {
            return method + " " + target + (range.isEmpty() ? "" : " Range: " + range) + (requestBody > 0 ? " (" + requestBody + " bytes up)" : "")
                    + " -> " + status + ", " + responseBody + " bytes";
        }
    }

    private final ServerSocket listener;
    private final int targetPort;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Exchange> exchanges = new ArrayList<>();
    private final AtomicLong wireDown = new AtomicLong();

    public CountingProxy(int targetPort) throws IOException {
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        threads.submit(() -> {
            while (!listener.isClosed()) {
                Socket client;
                try {
                    client = listener.accept();
                } catch (IOException e) {
                    return null;
                }
                threads.submit(() -> connection(client));
            }
            return null;
        });
    }

    public int port() {
        return listener.getLocalPort();
    }

    /** The exchanges so far, and clears the record. */
    public synchronized List<Exchange> drain() {
        List<Exchange> out = List.copyOf(exchanges);
        exchanges.clear();
        return out;
    }

    /** Every byte sent from the server to clients, headers included. */
    public long wireBytesDown() {
        return wireDown.get();
    }

    private synchronized void record(Exchange e) {
        exchanges.add(e);
    }

    private record Pending(String method, String target, String range, long requestBody) {
    }

    private Void connection(Socket client) throws IOException {
        try (client; Socket server = new Socket(InetAddress.getLoopbackAddress(), targetPort)) {
            LinkedBlockingQueue<Pending> pending = new LinkedBlockingQueue<>();
            var responses = threads.submit(() -> {
                responses(new BufferedInputStream(server.getInputStream(), 1 << 16), client.getOutputStream(), pending);
                return null;
            });
            requests(new BufferedInputStream(client.getInputStream(), 1 << 16), server.getOutputStream(), pending);
            try {
                responses.get();
            } catch (Exception ignored) {
                // the connection ended
            }
        }
        return null;
    }

    private void requests(InputStream in, OutputStream out, LinkedBlockingQueue<Pending> pending) {
        try {
            while (true) {
                List<String> head = readHead(in, out, null);
                if (head == null) break;
                String[] line = head.getFirst().split(" ");
                long length = contentLength(head);
                pending.add(new Pending(line[0], line[1], header(head, "range"), length));
                copy(in, out, length, null);
            }
        } catch (IOException ignored) {
            // the client went away
        }
        closeQuietly(out);
    }

    private void responses(InputStream in, OutputStream out, LinkedBlockingQueue<Pending> pending) {
        try {
            while (true) {
                List<String> head = readHead(in, out, wireDown);
                if (head == null) break;
                int status = Integer.parseInt(head.getFirst().split(" ")[1]);
                if (status == 100) continue;
                Pending p = pending.take();
                long length = p.method.equals("HEAD") || status == 204 || status == 304 ? 0 : contentLength(head);
                copy(in, out, length, wireDown);
                record(new Exchange(p.method, p.target, p.range, p.requestBody, status, length));
            }
        } catch (IOException | InterruptedException ignored) {
            // the server closed the connection
        }
        closeQuietly(out);
    }

    /** Reads and forwards a request or response head; null at the end of the stream. */
    private static List<String> readHead(InputStream in, OutputStream out, AtomicLong counter) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, c;
        while (state < 4 && (c = in.read()) >= 0) {
            head.write(c);
            if (c == '\r') state = state == 2 ? 3 : 1;
            else if (c == '\n') state = state == 1 ? 2 : state == 3 ? 4 : 0;
            else state = 0;
        }
        if (head.size() == 0) return null;
        out.write(head.toByteArray());
        out.flush();
        if (counter != null) counter.addAndGet(head.size());
        return List.of(head.toString(StandardCharsets.ISO_8859_1).split("\r\n"));
    }

    private static void copy(InputStream in, OutputStream out, long length, AtomicLong counter) throws IOException {
        byte[] buf = new byte[1 << 16];
        while (length > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, length));
            if (n < 0) throw new IOException("body cut short");
            out.write(buf, 0, n);
            if (counter != null) counter.addAndGet(n);
            length -= n;
        }
        out.flush();
    }

    private static String header(List<String> head, String name) {
        for (String h : head) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(name)) return h.substring(colon + 1).trim();
        }
        return "";
    }

    private static long contentLength(List<String> head) {
        String v = header(head, "content-length");
        return v.isEmpty() ? 0 : Long.parseLong(v);
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        threads.shutdownNow();
    }
}
