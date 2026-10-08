package io.github.bdarwin.cairn.examples.probes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Probe 4: what {@code FileChannel.force(true)} costs per file on this machine, at 4 KiB, 1 MiB and
 * 64 MiB, and what a whole durable object write costs: data file written and forced, a small
 * metadata file written and forced, a rename into place, and the parent directory forced.
 *
 * <p>Compare with {@code examples/native/fsync_probe.c}, which times plain {@code fsync()} and
 * {@code fcntl(F_FULLFSYNC)}: the Java figures say which of the two {@code force} performs.
 *
 * <p>Run: {@code java -cp examples/target/classes io.github.bdarwin.cairn.examples.probes.ProbeFsync <dir>}
 */
public final class ProbeFsync {

    public static void main(String[] args) throws IOException {
        Path dir = Files.createDirectories(Path.of(args[0]));
        System.out.println("java " + System.getProperty("java.version"));
        int[] sizes = {4096, 1 << 20, 64 << 20};
        int[] counts = {200, 50, 10};
        for (int i = 0; i < sizes.length; i++) force(dir, sizes[i], counts[i]);
        for (int i = 0; i < sizes.length; i++) wholeWrite(dir, sizes[i], counts[i]);
        inlineWrite(dir, 4096, 200);
        for (int threads : new int[] {1, 4, 16, 64}) concurrent(dir, 4096, 400, threads);
    }

    /** Small object with its data inline in the metadata file: one file forced, renamed, directory forced. */
    static void inlineWrite(Path dir, int size, int n) throws IOException {
        ByteBuffer both = ByteBuffer.allocateDirect(size + 300);
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) {
            Path obj = Files.createDirectories(dir.resolve("i" + i));
            long t = System.nanoTime();
            writeForced(obj.resolve(".meta.tmp"), both);
            Files.move(obj.resolve(".meta.tmp"), obj.resolve(".meta"), StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel d = FileChannel.open(obj, StandardOpenOption.READ)) {
                d.force(true);
            }
            ms[i] = (System.nanoTime() - t) / 1e6;
            Files.delete(obj.resolve(".meta"));
            Files.delete(obj);
        }
        report("inline write", size, ms);
    }

    /** {@code n} whole object writes spread over {@code threads} virtual threads: objects per second. */
    static void concurrent(Path dir, int size, int n, int threads) throws IOException {
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        long t = System.nanoTime();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int k = 0; k < threads; k++) {
                pool.submit(() -> {
                    ByteBuffer data = ByteBuffer.allocateDirect(size);
                    ByteBuffer meta = ByteBuffer.wrap(new byte[300]);
                    for (int i; (i = next.getAndIncrement()) < n; ) {
                        Path obj = Files.createDirectories(dir.resolve("c" + i));
                        writeForced(obj.resolve(".data.tmp"), data);
                        writeForced(obj.resolve(".meta.tmp"), meta);
                        Files.move(obj.resolve(".data.tmp"), obj.resolve(".data"), StandardCopyOption.ATOMIC_MOVE);
                        Files.move(obj.resolve(".meta.tmp"), obj.resolve(".meta"), StandardCopyOption.ATOMIC_MOVE);
                        try (FileChannel d = FileChannel.open(obj, StandardOpenOption.READ)) {
                            d.force(true);
                        }
                    }
                    return null;
                });
            }
        }
        double s = (System.nanoTime() - t) / 1e9;
        System.out.printf("concurrent    %9d bytes  n=%3d  threads %2d  %7.1f objects/s%n", size, n, threads, n / s);
        for (int i = 0; i < n; i++) {
            Path obj = dir.resolve("c" + i);
            Files.delete(obj.resolve(".data"));
            Files.delete(obj.resolve(".meta"));
            Files.delete(obj);
        }
    }

    static void force(Path dir, int size, int n) throws IOException {
        ByteBuffer data = ByteBuffer.allocateDirect(size);
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) {
            Path f = dir.resolve("f" + i);
            try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                data.clear();
                while (data.hasRemaining()) ch.write(data);
                long t = System.nanoTime();
                ch.force(true);
                ms[i] = (System.nanoTime() - t) / 1e6;
            }
            Files.delete(f);
        }
        report("force(true)", size, ms);
    }

    /** Data + metadata, each written to a temp file and forced, renamed into place, directory forced. */
    static void wholeWrite(Path dir, int size, int n) throws IOException {
        ByteBuffer data = ByteBuffer.allocateDirect(size);
        ByteBuffer meta = ByteBuffer.wrap(new byte[300]);
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) {
            Path obj = Files.createDirectories(dir.resolve("o" + i));
            long t = System.nanoTime();
            writeForced(obj.resolve(".data.tmp"), data);
            writeForced(obj.resolve(".meta.tmp"), meta);
            Files.move(obj.resolve(".data.tmp"), obj.resolve(".data"), StandardCopyOption.ATOMIC_MOVE);
            Files.move(obj.resolve(".meta.tmp"), obj.resolve(".meta"), StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel d = FileChannel.open(obj, StandardOpenOption.READ)) {
                d.force(true);
            }
            ms[i] = (System.nanoTime() - t) / 1e6;
            Files.delete(obj.resolve(".data"));
            Files.delete(obj.resolve(".meta"));
            Files.delete(obj);
        }
        report("object write", size, ms);
    }

    static void writeForced(Path f, ByteBuffer b) throws IOException {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            b.clear();
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
    }

    static void report(String what, int size, double[] ms) {
        double mean = Arrays.stream(ms).average().orElse(0);
        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        System.out.printf("%-13s %9d bytes  n=%3d  mean %8.3f ms  median %8.3f ms  worst %8.3f ms%n",
                what, size, ms.length, mean, sorted[sorted.length / 2], sorted[sorted.length - 1]);
    }
}

/*
Output (2026-10-08, Apple M1 Max, internal SSD, APFS, JDK 21.0.11):

   java 21.0.11
   force(true)        4096 bytes  n=200  mean    4.716 ms  median    4.708 ms  worst    9.846 ms
   force(true)     1048576 bytes  n= 50  mean    7.030 ms  median    6.830 ms  worst   19.459 ms
   force(true)    67108864 bytes  n= 10  mean   18.181 ms  median   18.580 ms  worst   19.556 ms
   object write       4096 bytes  n=200  mean   15.198 ms  median   14.894 ms  worst   28.785 ms
   object write    1048576 bytes  n= 50  mean   18.495 ms  median   17.720 ms  worst   32.091 ms
   object write   67108864 bytes  n= 10  mean   41.266 ms  median   41.215 ms  worst   41.882 ms
   inline write       4096 bytes  n=200  mean   10.429 ms  median    9.902 ms  worst   19.661 ms
   concurrent         4096 bytes  n=400  threads  1     58.9 objects/s
   concurrent         4096 bytes  n=400  threads  4     76.0 objects/s
   concurrent         4096 bytes  n=400  threads 16     97.4 objects/s
   concurrent         4096 bytes  n=400  threads 64    105.0 objects/s
*/

/*
Output of the same run on JDK 25.0.2 (same machine, same day):

   java 25.0.2
   force(true)        4096 bytes  n=200  mean    4.684 ms  median    4.655 ms  worst    9.703 ms
   force(true)     1048576 bytes  n= 50  mean    6.487 ms  median    6.642 ms  worst    9.273 ms
   force(true)    67108864 bytes  n= 10  mean   18.994 ms  median   18.545 ms  worst   25.263 ms
   object write       4096 bytes  n=200  mean   16.053 ms  median   15.159 ms  worst   35.945 ms
   object write    1048576 bytes  n= 50  mean   18.131 ms  median   17.855 ms  worst   23.840 ms
   object write   67108864 bytes  n= 10  mean   43.072 ms  median   42.852 ms  worst   48.760 ms
   inline write       4096 bytes  n=200  mean   10.096 ms  median    9.841 ms  worst   20.469 ms
   concurrent         4096 bytes  n=400  threads  1     60.6 objects/s
   concurrent         4096 bytes  n=400  threads  4     73.4 objects/s
   concurrent         4096 bytes  n=400  threads 16     94.7 objects/s
   concurrent         4096 bytes  n=400  threads 64     94.9 objects/s
*/
