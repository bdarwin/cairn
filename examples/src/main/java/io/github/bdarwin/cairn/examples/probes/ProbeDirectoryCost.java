package io.github.bdarwin.cairn.examples.probes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Probe for milestone 3: in a directory of 1,000,000 object directories (the flat shape that
 * {@code ListingMillion} loads), what does each step of listing one page cost? Reading every name,
 * sorting them, reading 1,000 {@code .meta} files, and reading 1,000 object directories. Also prints
 * {@code unix:nlink} of an object directory, to see whether APFS's link count could tell a leaf
 * directory from one with subdirectories (it cannot: it counts files too).
 *
 * <p>Run: {@code java ProbeDirectoryCost.java <data>/flat/flat <data>/nested/readings/0500}
 */
public final class ProbeDirectoryCost {

    public static void main(String[] a) throws Exception {
        Path d = Path.of(a[0]);
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            List<String> names = new ArrayList<>();
            try (var ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) names.add(p.getFileName().toString());
            }
            long t1 = System.nanoTime();
            names.sort(null);
            long t2 = System.nanoTime();
            for (int i = 0; i < 1000; i++) Files.readAllBytes(d.resolve(names.get(i + 500000)).resolve(".meta"));
            long t3 = System.nanoTime();
            int sub = 0;
            for (int i = 0; i < 1000; i++) {
                try (var ds = Files.newDirectoryStream(d.resolve(names.get(i + 600000)))) {
                    for (Path p : ds) sub++;
                }
            }
            long t4 = System.nanoTime();
            Object nl = Files.getAttribute(d.resolve(names.get(0)), "unix:nlink");
            Object nlParent = Files.getAttribute(Path.of(a[1]), "unix:nlink");
            System.out.printf("readdir %d names %.0f ms, sort %.0f ms, 1000 meta reads %.1f ms, 1000 leaf readdirs %.1f ms, leaf nlink %s, group dir nlink %s%n",
                    names.size(), (t1 - t) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 1e6, nl, nlParent);
        }
    }
}

/*
Output (2026-10-08, Apple M1 Max, internal SSD, APFS, JDK 21.0.11), right after ListingMillion's load;
the group directory holds 1,000 object directories, the leaf only its .meta:

   readdir 1000000 names 3794 ms, sort 286 ms, 1000 meta reads 82.2 ms, 1000 leaf readdirs 51.0 ms, leaf nlink 3, group dir nlink 1002
   readdir 1000000 names 1502 ms, sort 287 ms, 1000 meta reads 19.9 ms, 1000 leaf readdirs 19.1 ms, leaf nlink 3, group dir nlink 1002
   readdir 1000000 names 1494 ms, sort 269 ms, 1000 meta reads 24.4 ms, 1000 leaf readdirs 24.5 ms, leaf nlink 3, group dir nlink 1002
*/
