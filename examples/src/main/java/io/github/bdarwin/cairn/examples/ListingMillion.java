package io.github.bdarwin.cairn.examples;

import io.github.bdarwin.cairn.CairnServer;
import io.github.bdarwin.cairn.internal.store.LocalObjectStore;
import io.github.bdarwin.cairn.internal.store.NewObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Milestone 3: ListObjectsV2 over a million keys, through the HTTP API with the AWS SDK.
 *
 * <p>Two shapes of the same number of keys:
 * <ul>
 *   <li><b>nested</b>: {@code readings/<group>/item-<n>}, 1,000 groups of 1,000;</li>
 *   <li><b>flat</b>: {@code flat/item-<n>}, all in one directory.</li>
 * </ul>
 * The keys are loaded straight into the store without forcing each write (loading a million durable
 * objects at ~100 per second would take hours, and listing does not depend on how they were written).
 * Every listing then goes through cairn's HTTP server.
 *
 * <p>Run: {@code java -cp ... ListingMillion <empty dir> <keys>}; keys is a multiple of 1000.
 */
public final class ListingMillion {

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        int keys = Integer.parseInt(args[1]);
        int groups = 1000, perGroup = keys / groups;
        System.out.printf("java %s, %,d keys in each shape%n", System.getProperty("java.version"), keys);

        LocalObjectStore loader = new LocalObjectStore(root, Clock.systemUTC(), false);
        if (loader.bucketExists("nested")) {
            System.out.println("reusing the keys already loaded in " + root);
        } else {
            loader.createBucket("nested");
            loader.createBucket("flat");
            load(loader, "nested", keys, i -> String.format("readings/%04d/item-%07d", i / perGroup, i));
            load(loader, "flat", keys, i -> String.format("flat/item-%07d", i));
        }

        try (CairnServer server = CairnServer.builder(root).bindAddress("127.0.0.1").port(0).credentials("example-key", "example-secret").start();
             S3Client s3 = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + server.port())).region(Region.US_EAST_1)
                     .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("example-key", "example-secret"))).build()) {
            System.out.println();
            System.out.println("each query run three times; run 1 is the first listing since the server started (the file cache is warm from loading)");
            for (int run = 1; run <= 3; run++) {
                System.out.println("run " + run);
                timeOne(s3, "nested", "readings/", "/", "  nested: prefix readings/, delimiter /   ");
                timeOne(s3, "nested", String.format("readings/%04d/", groups / 2), "/", "  nested: one group, delimiter /         ");
                timeOne(s3, "flat", "flat/", "/", "  flat:   prefix flat/, delimiter /, page 1");
                timeOne(s3, "flat", "flat/item-05", "/", "  flat:   prefix flat/item-05, page 1     ");
            }
            System.out.println();
            timeAll(s3, "nested", "readings/", "  nested: every key, pages of 1000        ");
            timeAll(s3, "flat", "flat/", "  flat:   every key, pages of 1000        ");
        }
    }

    static void load(LocalObjectStore store, String bucket, int keys, java.util.function.IntFunction<String> key) throws Exception {
        long t = System.nanoTime();
        AtomicInteger next = new AtomicInteger();
        NewObject attrs = new NewObject("application/json", Map.of(), Map.of(), Map.of());
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int k = 0; k < 8; k++) {
                pool.submit(() -> {
                    for (int i; (i = next.getAndIncrement()) < keys; ) {
                        byte[] body = ("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8);
                        store.put(bucket, key.apply(i), attrs, new ByteArrayInputStream(body), (s, m) -> { });
                    }
                    return null;
                });
            }
        }
        System.out.printf("loaded %,d keys into %s in %.1f s (no forces)%n", keys, bucket, (System.nanoTime() - t) / 1e9);
    }

    static void timeOne(S3Client s3, String bucket, String prefix, String delimiter, String label) {
        long t = System.nanoTime();
        ListObjectsV2Response r = s3.listObjectsV2(b -> b.bucket(bucket).prefix(prefix).delimiter(delimiter));
        double ms = (System.nanoTime() - t) / 1e6;
        System.out.printf("%s %9.1f ms  %4d keys, %4d prefixes, truncated %s%n", label, ms, r.contents().size(), r.commonPrefixes().size(), r.isTruncated());
    }

    static void timeAll(S3Client s3, String bucket, String prefix, String label) {
        long t = System.nanoTime();
        int[] counts = new int[2];
        Consumer<ListObjectsV2Response> count = r -> {
            counts[0]++;
            counts[1] += r.contents().size();
        };
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).forEach(count);
        double s = (System.nanoTime() - t) / 1e9;
        System.out.printf("%s %9.1f s   %,d keys in %,d pages: %.1f ms a page, %,.0f keys/s%n", label, s, counts[1], counts[0],
                s * 1000 / counts[0], counts[1] / s);
    }

    static void timePages(S3Client s3, String bucket, String prefix, int pages, String label) {
        long t = System.nanoTime();
        int n = 0, keys = 0;
        for (ListObjectsV2Response r : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())) {
            keys += r.contents().size();
            if (++n >= pages) break;
        }
        double s = (System.nanoTime() - t) / 1e9;
        System.out.printf("%s %9.1f s   %,d keys in %,d pages: %.1f ms a page%n", label, s, keys, n, s * 1000 / n);
    }
}

/*
Output (2026-10-08, Apple M1 Max, internal SSD, APFS, JDK 21.0.11, AWS SDK 2.55.12), with the code as it is now:

   java 21.0.11, 1,000,000 keys in each shape
   reusing the keys already loaded in /private/tmp/claude-501/-Users-bdarwin-my-source-cairn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/l1m
   
   each query run three times; run 1 is the first listing since the server started (the file cache is warm from loading)
   run 1
     nested: prefix readings/, delimiter /       1427.4 ms     0 keys, 1000 prefixes, truncated false
     nested: one group, delimiter /              201.3 ms  1000 keys,    0 prefixes, truncated false
     flat:   prefix flat/, delimiter /, page 1    7746.2 ms  1000 keys,    0 prefixes, truncated true
     flat:   prefix flat/item-05, page 1          121.2 ms  1000 keys,    0 prefixes, truncated true
   run 2
     nested: prefix readings/, delimiter /        906.5 ms     0 keys, 1000 prefixes, truncated false
     nested: one group, delimiter /              108.3 ms  1000 keys,    0 prefixes, truncated false
     flat:   prefix flat/, delimiter /, page 1      88.7 ms  1000 keys,    0 prefixes, truncated true
     flat:   prefix flat/item-05, page 1           73.0 ms  1000 keys,    0 prefixes, truncated true
   run 3
     nested: prefix readings/, delimiter /        153.9 ms     0 keys, 1000 prefixes, truncated false
     nested: one group, delimiter /               79.7 ms  1000 keys,    0 prefixes, truncated false
     flat:   prefix flat/, delimiter /, page 1      69.6 ms  1000 keys,    0 prefixes, truncated true
     flat:   prefix flat/item-05, page 1           71.0 ms  1000 keys,    0 prefixes, truncated true
   
     nested: every key, pages of 1000             134.6 s   1,000,000 keys in 1,000 pages: 134.6 ms a page, 7,428 keys/s
     flat:   every key, pages of 1000             139.0 s   1,000,000 keys in 1,000 pages: 139.0 ms a page, 7,192 keys/s

The first run, before DirectoryIndex existed and before the walk could seek (commit 0edebdf plus the
first listing code). It is what decided that an index was needed; its last line was "first 20 pages",
because every key of the flat shape would have taken about 1,000 pages at over 5 s each:

   java 21.0.11, 1,000,000 keys in each shape
   loaded 1,000,000 keys into nested in 190.9 s (no forces)
   loaded 1,000,000 keys into flat in 243.2 s (no forces)
   
   each query run three times; the first run follows the load, so the file cache is warm throughout
   run 1
     nested: prefix readings/, delimiter /       5347.4 ms     0 keys, 1000 prefixes, truncated false
     nested: one group, delimiter /              160.6 ms  1000 keys,    0 prefixes, truncated false
     flat:   prefix flat/, delimiter /, page 1    6661.9 ms  1000 keys,    0 prefixes, truncated true
     flat:   prefix flat/item-05, page 1         5339.6 ms  1000 keys,    0 prefixes, truncated true
   run 2
     nested: prefix readings/, delimiter /       4907.6 ms     0 keys, 1000 prefixes, truncated false
     nested: one group, delimiter /              101.0 ms  1000 keys,    0 prefixes, truncated false
     flat:   prefix flat/, delimiter /, page 1    7558.4 ms  1000 keys,    0 prefixes, truncated true
     flat:   prefix flat/item-05, page 1         5465.0 ms  1000 keys,    0 prefixes, truncated true
   run 3
     nested: prefix readings/, delimiter /       4905.4 ms     0 keys, 1000 prefixes, truncated false
     nested: one group, delimiter /               86.8 ms  1000 keys,    0 prefixes, truncated false
     flat:   prefix flat/, delimiter /, page 1    7348.9 ms  1000 keys,    0 prefixes, truncated true
     flat:   prefix flat/item-05, page 1         5295.8 ms  1000 keys,    0 prefixes, truncated true
   
     nested: every key, pages of 1000             133.0 s   1,000,000 keys in 1,000 pages: 133.0 ms a page, 7,518 keys/s
     flat:   first 20 pages of 1000                105.7 s   20,000 keys in 20 pages: 5283.4 ms a page
   
*/
