package io.github.bdarwin.cairn.examples;

import io.github.bdarwin.cairn.CairnServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Milestone 2: objects end to end. Starts cairn over a directory, then, through unmodified clients:
 * PUT and GET at four sizes with the AWS SDK for Java v2 (bytes checked), small PUTs from 16
 * concurrent clients, and a round trip with the {@code aws} CLI (bytes checked with {@code cmp}).
 *
 * <p>Run: {@code java -cp ... ObjectsEndToEnd <empty dir>}. Needs room for about 3 GiB.
 */
public final class ObjectsEndToEnd {

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        Path data = Files.createDirectories(root.resolve("data"));
        Path work = Files.createDirectories(root.resolve("work"));
        System.out.printf("java %s, %d cores%n", System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        try (CairnServer server = CairnServer.builder(data).bindAddress("127.0.0.1").port(0).credentials("example-key", "example-secret").start();
             S3Client s3 = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + server.port())).region(Region.US_EAST_1)
                     .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("example-key", "example-secret"))).build()) {
            s3.createBucket(b -> b.bucket("items"));

            System.out.println();
            System.out.println("AWS SDK for Java v2, one client, sequential (PUT includes the forces that make it durable)");
            System.out.println("    size      n   PUT mean    GET mean    PUT MiB/s   GET MiB/s   bytes identical");
            for (long size : new long[] {1024, 1 << 20, 64L << 20, 1L << 30}) {
                int n = size <= (1 << 20) ? 50 : size <= (64L << 20) ? 5 : 2;
                Path file = work.resolve("in-" + size);
                writeRandom(file, size);
                String md5 = md5(file);
                double put = 0, get = 0;
                boolean same = true;
                for (int i = 0; i < n; i++) {
                    String key = "sized/" + size + "/" + i;
                    long t = System.nanoTime();
                    s3.putObject(b -> b.bucket("items").key(key), RequestBody.fromFile(file));
                    put += (System.nanoTime() - t) / 1e6;
                    Path back = work.resolve("back");
                    Files.deleteIfExists(back);
                    t = System.nanoTime();
                    s3.getObject(b -> b.bucket("items").key(key), ResponseTransformer.toFile(back));
                    get += (System.nanoTime() - t) / 1e6;
                    same &= md5.equals(md5(back));
                    Files.delete(back);
                    s3.deleteObject(b -> b.bucket("items").key(key));
                }
                Files.delete(file);
                double mib = size / 1048576.0;
                System.out.printf("%8s %6d %8.2f ms %8.2f ms %11.1f %11.1f   %s%n", human(size), n, put / n, get / n,
                        mib / (put / n / 1000), mib / (get / n / 1000), same);
            }

            System.out.println();
            System.out.println("1 KiB PUTs from concurrent clients (each its own thread, shared connection pool)");
            byte[] small = new byte[1024];
            for (int threads : new int[] {1, 16}) {
                int total = 400;
                AtomicInteger next = new AtomicInteger();
                long t = System.nanoTime();
                try (var pool = Executors.newFixedThreadPool(threads)) {
                    for (int k = 0; k < threads; k++) {
                        pool.submit(() -> {
                            for (int i; (i = next.getAndIncrement()) < total; ) {
                                String key = "small/" + threads + "/" + i;
                                s3.putObject(b -> b.bucket("items").key(key), RequestBody.fromBytes(small));
                            }
                        });
                    }
                }
                double s = (System.nanoTime() - t) / 1e9;
                System.out.printf("  %2d clients: %d PUTs in %.2f s = %.0f PUT/s%n", threads, total, s, total / s);
            }

            System.out.println();
            if (onPath("aws")) {
                Path file = work.resolve("cli.bin");
                writeRandom(file, 5L << 20);
                Path back = work.resolve("cli.back");
                String endpoint = "http://127.0.0.1:" + server.port();
                long t = System.nanoTime();
                run("aws", "--endpoint-url", endpoint, "s3", "cp", file.toString(), "s3://items/cli/file.bin", "--no-progress");
                double up = (System.nanoTime() - t) / 1e9;
                t = System.nanoTime();
                run("aws", "--endpoint-url", endpoint, "s3", "cp", "s3://items/cli/file.bin", back.toString(), "--no-progress");
                double down = (System.nanoTime() - t) / 1e9;
                int cmp = run("cmp", file.toString(), back.toString());
                System.out.println(version());
                System.out.printf("aws s3 cp 5 MiB up %.2f s, down %.2f s (each includes CLI start-up), cmp: %s%n", up, down, cmp == 0 ? "identical" : "DIFFERENT");
                Files.delete(file);
                Files.delete(back);
            } else {
                System.out.println("aws CLI not on PATH: skipped");
            }
        }
    }

    static String version() throws Exception {
        Process p = new ProcessBuilder("aws", "--version").redirectErrorStream(true).start();
        return new String(p.getInputStream().readAllBytes()).trim();
    }

    static int run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("AWS_ACCESS_KEY_ID", "example-key");
        pb.environment().put("AWS_SECRET_ACCESS_KEY", "example-secret");
        pb.environment().put("AWS_DEFAULT_REGION", "us-east-1");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0 && !cmd[0].equals("cmp")) throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        return rc;
    }

    static boolean onPath(String program) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(d, program))) return true;
        }
        return false;
    }

    static void writeRandom(Path file, long size) throws Exception {
        Random rnd = new Random(size);
        byte[] buf = new byte[1 << 20];
        try (var out = Files.newOutputStream(file)) {
            for (long left = size; left > 0; ) {
                rnd.nextBytes(buf);
                int n = (int) Math.min(buf.length, left);
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    static String md5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static String human(long size) {
        return size >= 1L << 30 ? (size >> 30) + " GiB" : size >= 1 << 20 ? (size >> 20) + " MiB" : (size >> 10) + " KiB";
    }
}

/*
Output (2026-10-08, Apple M1 Max, internal SSD, APFS, JDK 21.0.11, AWS SDK 2.55.12):

   java 21.0.11, 10 cores
   
   AWS SDK for Java v2, one client, sequential (PUT includes the forces that make it durable)
       size      n   PUT mean    GET mean    PUT MiB/s   GET MiB/s   bytes identical
      1 KiB     50    28.19 ms     3.51 ms         0.0         0.3   true
      1 MiB     50    35.68 ms     3.14 ms        28.0       318.6   true
     64 MiB      5   229.69 ms    56.24 ms       278.6      1137.9   true
      1 GiB      2  3225.57 ms  1171.06 ms       317.5       874.4   true
   
   1 KiB PUTs from concurrent clients (each its own thread, shared connection pool)
      1 clients: 400 PUTs in 8.13 s = 49 PUT/s
     16 clients: 400 PUTs in 2.49 s = 161 PUT/s
   
   aws-cli/2.37.10 Python/3.14.8 Darwin/25.5.0 source/arm64
   aws s3 cp 5 MiB up 0.56 s, down 0.32 s (each includes CLI start-up), cmp: identical
*/
