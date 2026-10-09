package io.github.bdarwin.cairn.examples.probes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Probe for milestone 4: how should CompleteMultipartUpload turn parts into an object?
 * <ul>
 *   <li><b>stitch</b>: copy the parts, in order, into one data file and force it;</li>
 *   <li><b>part list</b>: hard-link each part file into the object's directory, force the directory,
 *       and record the list in the metadata; reads then cross from file to file.</li>
 * </ul>
 * Measures the time to complete, the extra disk used while completing, and then the time to read the
 * whole object back each way.
 *
 * <p>Run: {@code java ProbeMultipartComplete.java <dir> <totalMiB> <partMiB>}; needs room for twice the total.
 */
public final class ProbeMultipartComplete {

    public static void main(String[] args) throws IOException {
        Path dir = Files.createDirectories(Path.of(args[0]));
        long totalMiB = Long.parseLong(args[1]);
        int partMiB = Integer.parseInt(args[2]);
        int parts = (int) ((totalMiB + partMiB - 1) / partMiB);
        System.out.printf("java %s, %d MiB in %d parts of %d MiB%n", System.getProperty("java.version"), totalMiB, parts, partMiB);

        Path upload = Files.createDirectories(dir.resolve("upload"));
        List<Path> partFiles = new ArrayList<>();
        byte[] buf = new byte[1 << 20];
        Random rnd = new Random(1);
        for (int i = 0; i < parts; i++) {
            Path p = upload.resolve("part-" + (i + 1));
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (int m = 0; m < partMiB && (long) i * partMiB + m < totalMiB; m++) {
                    rnd.nextBytes(buf);
                    ch.write(ByteBuffer.wrap(buf));
                }
                ch.force(true);
            }
            partFiles.add(p);
        }

        // Stitch.
        Path stitched = Files.createDirectories(dir.resolve("stitched")).resolve(".data");
        long t = System.nanoTime();
        try (FileChannel out = FileChannel.open(stitched, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (Path p : partFiles) {
                try (FileChannel in = FileChannel.open(p, StandardOpenOption.READ)) {
                    long size = in.size();
                    for (long pos = 0; pos < size; ) pos += in.transferTo(pos, size - pos, out);
                }
            }
            out.force(true);
        }
        double stitch = (System.nanoTime() - t) / 1e9;

        // Part list.
        Path listed = Files.createDirectories(dir.resolve("listed"));
        t = System.nanoTime();
        List<Path> links = new ArrayList<>();
        for (int i = 0; i < partFiles.size(); i++) {
            Path link = listed.resolve(".data-" + (i + 1));
            Files.createLink(link, partFiles.get(i));
            links.add(link);
        }
        try (FileChannel d = FileChannel.open(listed, StandardOpenOption.READ)) {
            d.force(true);
        }
        double link = (System.nanoTime() - t) / 1e9;

        double readStitched = readAll(List.of(stitched));
        double readListed = readAll(links);
        System.out.printf("complete by stitching:  %7.2f s, %d MiB more on disk until the parts are deleted%n", stitch, totalMiB);
        System.out.printf("complete by part list:  %7.3f s, no more on disk (hard links)%n", link);
        System.out.printf("read whole object, one file:      %6.2f s  %6.0f MiB/s%n", readStitched, totalMiB / readStitched);
        System.out.printf("read whole object, %4d files:    %6.2f s  %6.0f MiB/s%n", parts, readListed, totalMiB / readListed);

        for (Path p : partFiles) Files.delete(p);
        for (Path p : links) Files.delete(p);
        Files.delete(stitched);
    }

    static double readAll(List<Path> files) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(1 << 20);
        long t = System.nanoTime();
        for (Path p : files) {
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
                while (ch.read(b.clear()) > 0) {
                    // read only
                }
            }
        }
        return (System.nanoTime() - t) / 1e9;
    }
}

/*
Output (2026-10-09, Apple M1 Max, internal SSD, APFS, JDK 21.0.11; 8 MiB is the aws CLI's part size):

   java 21.0.11, 5120 MiB in 640 parts of 8 MiB
   complete by stitching:    16.80 s, 5120 MiB more on disk until the parts are deleted
   complete by part list:    0.217 s, no more on disk (hard links)
   read whole object, one file:        1.19 s    4320 MiB/s
   read whole object,  640 files:      1.19 s    4304 MiB/s
*/
