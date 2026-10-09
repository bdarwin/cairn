package io.github.bdarwin.cairn.examples;

import io.github.bdarwin.cairn.CairnServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Milestone 4: {@code mc} and the {@code aws} CLI upload a large file to cairn by multipart upload,
 * unmodified, and read it back byte for byte. The server is durable (every part and the commit are
 * forced). Prints each client's version, upload and download time, the ETag (S3's {@code md5-N}
 * form), and checks that no upload is left in progress.
 *
 * <p>Run: {@code java -cp ... MultipartBigFile <empty dir> <MiB>}; needs room for three copies.
 */
public final class MultipartBigFile {

    static Path work;
    static String endpoint;

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        long mib = Long.parseLong(args[1]);
        Path data = Files.createDirectories(root.resolve("data"));
        work = Files.createDirectories(root.resolve("work"));
        Path file = work.resolve("big.bin");
        Random rnd = new Random(mib);
        byte[] buf = new byte[1 << 20];
        try (var out = Files.newOutputStream(file)) {
            for (long i = 0; i < mib; i++) {
                rnd.nextBytes(buf);
                out.write(buf);
            }
        }
        System.out.printf("java %s, file of %,d MiB%n", System.getProperty("java.version"), mib);

        try (CairnServer server = CairnServer.builder(data).bindAddress("127.0.0.1").port(0).credentials("example-key", "example-secret").start()) {
            endpoint = "http://127.0.0.1:" + server.port();
            run("aws", "--endpoint-url", endpoint, "s3", "mb", "s3://items");

            System.out.println();
            System.out.println(output("aws", "--version").trim());
            Path back = work.resolve("back.bin");
            double up = timed("aws", "--endpoint-url", endpoint, "s3", "cp", file.toString(), "s3://items/aws/big.bin", "--no-progress");
            String head = output("aws", "--endpoint-url", endpoint, "s3api", "head-object", "--bucket", "items", "--key", "aws/big.bin");
            double down = timed("aws", "--endpoint-url", endpoint, "s3", "cp", "s3://items/aws/big.bin", back.toString(), "--no-progress");
            report(file, back, up, down, mib, head);
            run("aws", "--endpoint-url", endpoint, "s3", "rm", "s3://items/aws/big.bin"); // room for the next copy

            System.out.println();
            System.out.println(output("mc", "--version").lines().findFirst().orElse("").trim());
            run("mc", "--config-dir", work.resolve("mc").toString(), "alias", "set", "cairn", endpoint, "example-key", "example-secret", "--api", "s3v4");
            up = timed("mc", "--config-dir", work.resolve("mc").toString(), "--quiet", "cp", file.toString(), "cairn/items/mc/big.bin");
            head = output("aws", "--endpoint-url", endpoint, "s3api", "head-object", "--bucket", "items", "--key", "mc/big.bin");
            down = timed("mc", "--config-dir", work.resolve("mc").toString(), "--quiet", "cp", "cairn/items/mc/big.bin", back.toString());
            report(file, back, up, down, mib, head);

            System.out.println();
            String uploads = output("aws", "--endpoint-url", endpoint, "s3api", "list-multipart-uploads", "--bucket", "items");
            System.out.println("uploads left in progress: " + (uploads.contains("UploadId") ? uploads : "none"));
            try (Stream<Path> s = Files.walk(data.resolve(".cairn"))) {
                System.out.println("files under .cairn/uploads: " + s.filter(Files::isRegularFile).filter(p -> p.toString().contains("uploads")).count());
            }
        }
        Files.deleteIfExists(file);
    }

    static void report(Path file, Path back, double up, double down, long mib, String head) throws Exception {
        String etag = head.lines().filter(l -> l.contains("\"ETag\"")).findFirst().orElse("").trim();
        int cmp = new ProcessBuilder("cmp", file.toString(), back.toString()).start().waitFor();
        System.out.printf("upload   %7.1f s  %6.1f MiB/s%n", up, mib / up);
        System.out.printf("download %7.1f s  %6.1f MiB/s%n", down, mib / down);
        System.out.println("ETag: " + etag);
        System.out.println("cmp: " + (cmp == 0 ? "identical" : "DIFFERENT"));
        Files.deleteIfExists(back);
    }

    static double timed(String... cmd) throws Exception {
        long t = System.nanoTime();
        run(cmd);
        return (System.nanoTime() - t) / 1e9;
    }

    static void run(String... cmd) throws Exception {
        output(cmd);
    }

    static String output(String... cmd) throws Exception {
        List<String> c = new ArrayList<>(List.of(cmd));
        ProcessBuilder pb = new ProcessBuilder(c).redirectErrorStream(true);
        pb.environment().put("AWS_ACCESS_KEY_ID", "example-key");
        pb.environment().put("AWS_SECRET_ACCESS_KEY", "example-secret");
        pb.environment().put("AWS_DEFAULT_REGION", "us-east-1");
        pb.environment().put("AWS_CONFIG_FILE", work.resolve("no-config").toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + out);
        return out;
    }
}

/*
Output (2026-10-09, Apple M1 Max, internal SSD, APFS, JDK 21.0.11; java ... MultipartBigFile <dir> 5120):

   java 21.0.11, file of 5,120 MiB
   
   aws-cli/2.37.10 Python/3.14.8 Darwin/25.5.0 source/arm64
   upload      16.4 s   311.5 MiB/s
   download    10.6 s   481.4 MiB/s
   ETag: "ETag": "\"65ecf8b8ec06d2ead0ce19579f7f637b-640\"",
   cmp: identical
   
   mc version RELEASE.2025-08-13T08-35-41Z (commit-id=7394ce0dd2a80935aded936b09fa12cbb3cb8096)
   upload       9.8 s   521.8 MiB/s
   download     5.1 s  1009.3 MiB/s
   ETag: "ETag": "\"90e289217b18fd1a108ebbbab1db922d-320\"",
   cmp: identical
   
   uploads left in progress: none
   files under .cairn/uploads: 0
*/
