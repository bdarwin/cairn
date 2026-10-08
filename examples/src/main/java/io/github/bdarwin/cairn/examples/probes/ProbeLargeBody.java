package io.github.bdarwin.cairn.examples.probes;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Probe 3: can the JDK's {@code HttpServer} on virtual threads stream a 5 GB PUT to disk and a 5 GB
 * GET back, with a flat heap, at a throughput close to a plain file copy on the same disk?
 *
 * <p>The server streams the request body to a file through a 1 MiB buffer, and streams the file into
 * a fixed-length response. {@code curl} is the client (a separate process, so the client's memory is
 * not in the measurement). Baselines: {@code dd bs=1m} copying the same file on the same disk, and
 * {@code dd} reading it to {@code /dev/null}. A sampler records the server's peak used heap.
 *
 * <p>Run with a small heap so that buffering the body would fail:
 * {@code java -Xmx64m -cp ... ProbeLargeBody <dir> <sizeMiB>}. The directory needs room for three
 * copies of the file; they are deleted at the end.
 */
public final class ProbeLargeBody {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long mib = Long.parseLong(args[1]);
        long size = mib << 20;
        Path src = dir.resolve("large-src.bin");
        Path stored = dir.resolve("large-stored.bin");
        Path copy = dir.resolve("large-ddcopy.bin");
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        System.out.printf("java %s, max heap %d MiB, file %d MiB%n", System.getProperty("java.version"),
                mem.getHeapMemoryUsage().getMax() >> 20, mib);

        run("dd", "if=/dev/urandom", "of=" + src, "bs=1m", "count=" + mib);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/obj", ex -> {
            try (ex) {
                if (ex.getRequestMethod().equals("PUT")) {
                    try (InputStream in = ex.getRequestBody();
                         OutputStream out = Files.newOutputStream(stored, StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        copy(in, out);
                    }
                    ex.sendResponseHeaders(200, -1);
                } else {
                    long len = Files.size(stored);
                    ex.sendResponseHeaders(200, len);
                    try (InputStream in = Files.newInputStream(stored); OutputStream out = ex.getResponseBody()) {
                        copy(in, out);
                    }
                }
            }
        });
        server.createContext("/asread", ex -> {
            try (ex; InputStream in = ex.getRequestBody();
                 OutputStream out = Files.newOutputStream(stored, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                copyAsRead(in, out);
                ex.sendResponseHeaders(200, -1);
            }
        });
        server.createContext("/discard", ex -> {
            try (ex; InputStream in = ex.getRequestBody()) {
                copy(in, OutputStream.nullOutputStream());
                ex.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        String discardUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/discard";
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/obj";

        AtomicLong peak = new AtomicLong();
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        System.gc();
        long heapBefore = mem.getHeapMemoryUsage().getUsed();
        peak.set(heapBefore);
        double put = timed(() -> run("curl", "-sS", "-f", "-T", src.toString(), url, "-o", "/dev/null"));
        long peakPut = peak.get();
        peak.set(mem.getHeapMemoryUsage().getUsed());
        double get = timed(() -> run("curl", "-sS", "-f", url, "-o", "/dev/null"));
        long peakGet = peak.get();
        double putAsRead = timed(() -> run("curl", "-sS", "-f", "-T", src.toString(), discardUrl.replace("/discard", "/asread"), "-o", "/dev/null"));
        double putDiscard = timed(() -> run("curl", "-sS", "-f", "-T", src.toString(), discardUrl, "-o", "/dev/null"));
        sampler.interrupt();
        server.stop(0);

        double ddCopy = timed(() -> run("dd", "if=" + src, "of=" + copy, "bs=1m"));
        double ddRead = timed(() -> run("dd", "if=" + stored, "of=/dev/null", "bs=1m"));
        boolean same = run("cmp", src.toString(), stored.toString()) == 0;

        System.out.printf("heap used before: %d MiB%n", heapBefore >> 20);
        System.out.printf("PUT  %6.1f s  %7.1f MiB/s   peak heap %d MiB%n", put, mib / put, peakPut >> 20);
        System.out.printf("GET  %6.1f s  %7.1f MiB/s   peak heap %d MiB%n", get, mib / get, peakGet >> 20);
        System.out.printf("PUT, write each read    %6.1f s  %7.1f MiB/s%n", putAsRead, mib / putAsRead);
        System.out.printf("PUT, body discarded     %6.1f s  %7.1f MiB/s%n", putDiscard, mib / putDiscard);
        System.out.printf("dd copy (same disk)     %6.1f s  %7.1f MiB/s%n", ddCopy, mib / ddCopy);
        System.out.printf("dd read to /dev/null    %6.1f s  %7.1f MiB/s%n", ddRead, mib / ddRead);
        System.out.println("stored file identical to source: " + same);
        for (Path p : List.of(src, stored, copy)) Files.deleteIfExists(p);
    }

    /** Fills the whole 1 MiB buffer before each write: a socket read returns far less than 1 MiB. */
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 20];
        for (int n; (n = in.readNBytes(buf, 0, buf.length)) > 0; ) out.write(buf, 0, n);
    }

    /** Writes whatever each read returns. */
    static void copyAsRead(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 20];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    interface Action {
        void run() throws Exception;
    }

    static double timed(Action a) throws Exception {
        long t = System.nanoTime();
        a.run();
        return (System.nanoTime() - t) / 1e9;
    }

    static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        int rc = p.waitFor();
        if (rc != 0 && !cmd[0].equals("cmp")) throw new IllegalStateException(String.join(" ", cmd) + " exited " + rc);
        return rc;
    }
}

/*
Output (2026-10-08, Apple M1 Max, 32 GiB RAM, internal SSD, APFS, JDK 21.0.11, java -Xmx64m ... 5120):

   java 21.0.11, max heap 64 MiB, file 5120 MiB
   heap used before: 1 MiB
   PUT     3.9 s   1326.3 MiB/s   peak heap 5 MiB
   GET     1.8 s   2829.0 MiB/s   peak heap 7 MiB
   PUT, write each read       6.5 s    791.0 MiB/s
   PUT, body discarded        1.4 s   3540.3 MiB/s
   dd copy (same disk)        3.1 s   1671.3 MiB/s
   dd read to /dev/null       0.8 s   6037.9 MiB/s
   stored file identical to source: true
*/
