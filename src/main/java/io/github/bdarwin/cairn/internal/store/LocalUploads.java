package io.github.bdarwin.cairn.internal.store;

import io.github.bdarwin.cairn.internal.S3Exception;
import io.github.bdarwin.cairn.internal.checksum.ChecksumAlgorithm;
import io.github.bdarwin.cairn.internal.checksum.CrcCombine;
import io.github.bdarwin.cairn.internal.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * Multipart uploads for {@link LocalObjectStore}. An upload in progress is a directory:
 *
 * <pre>
 * root/.cairn/uploads/bucket/&lt;upload id&gt;/
 *   upload.json                 key, metadata, declared checksum
 *   part-00001.json             part 1: size, MD5, CRC32C, checksum, data file name
 *   data-00001-&lt;uuid&gt;          part 1's bytes
 * </pre>
 *
 * A part is written like an object: data file forced, then its JSON written to a temporary file,
 * forced and renamed into place. Completing hard-links each part's data file into the object's
 * directory and commits the object's {@code .meta} naming them, the same rename as a PUT; only then
 * is the upload directory removed. A crash before that commit leaves the upload as it was, so the
 * client can complete it again. ProbeMultipartComplete measured the alternative, copying the parts
 * into one file: 16.8 s for 5 GiB against 0.22 s for the links, with reads equally fast either way.
 */
final class LocalUploads {

    static final long MIN_PART = 5L << 20;
    static final int MAX_PARTS = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LocalObjectStore store;
    private final Path uploadsRoot;

    LocalUploads(LocalObjectStore store, Path root) {
        this.store = store;
        this.uploadsRoot = root.resolve(".cairn").resolve("uploads");
    }

    Path bucketUploads(String bucket) {
        return uploadsRoot.resolve(bucket);
    }

    Upload create(String bucket, String key, NewObject attributes, String checksumAlgorithm, String checksumType) throws IOException {
        store.requireBucket(bucket);
        byte[] idBytes = new byte[16];
        RANDOM.nextBytes(idBytes);
        String id = HexFormat.of().formatHex(idBytes);
        Upload u = new Upload(id, key, Instant.ofEpochMilli(store.clock.millis()), attributes, checksumAlgorithm, checksumType);
        Path dir = bucketUploads(bucket).resolve(id);
        boolean newBucketDir = !Files.isDirectory(dir.getParent());
        Files.createDirectories(dir);
        if (newBucketDir) store.force(dir.getParent().getParent());
        store.force(dir.getParent());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1);
        m.put("id", id);
        m.put("key", key);
        m.put("initiated", u.initiated().toEpochMilli());
        m.put("contentType", attributes.contentType());
        m.put("meta", attributes.userMetadata());
        m.put("headers", attributes.headers());
        if (checksumAlgorithm != null) {
            m.put("checksumAlgorithm", checksumAlgorithm);
            m.put("checksumType", checksumType);
        }
        store.writeDurably(dir, "upload.json", (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
        return u;
    }

    /** The upload, checking it belongs to {@code key}; NoSuchUpload otherwise. */
    Upload get(String bucket, String key, String id) throws IOException {
        store.requireBucket(bucket);
        Upload u = read(bucketUploads(bucket), id);
        if (u == null || !u.key().equals(key)) throw noSuchUpload();
        return u;
    }

    PartInfo putPart(String bucket, String key, String id, int number, InputStream body, ObjectStore.BeforeCommit check,
                     Map<String, String> checksums) throws IOException {
        Upload u = get(bucket, key, id);
        Path dir = bucketUploads(bucket).resolve(id);
        String dataName = String.format("data-%05d-%s", number, UUID.randomUUID());
        Path data = dir.resolve(dataName);
        MessageDigest md5 = LocalObjectStore.md5();
        CRC32C crc = new CRC32C();
        long size = 0;
        try {
            try (FileChannel ch = FileChannel.open(data, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buf = new byte[LocalObjectStore.COPY_BUFFER];
                for (int r; (r = body.readNBytes(buf, 0, buf.length)) > 0; ) {
                    md5.update(buf, 0, r);
                    crc.update(buf, 0, r);
                    LocalObjectStore.writeFully(ch, buf, r);
                    size += r;
                }
                store.force(ch);
            } catch (NoSuchFileException e) {
                throw noSuchUpload();
            }
            byte[] digest = md5.digest();
            check.check(size, digest);
            String checksum = u.checksumAlgorithm() == null ? null : checksums.get(u.checksumAlgorithm());
            PartInfo part = new PartInfo(number, size, HexFormat.of().formatHex(digest), LocalObjectStore.base64(crc.getValue()), checksum);
            Map<String, Object> m = partJson(part);
            m.put("lastModified", store.clock.millis());
            m.put("file", dataName);
            byte[] json = (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
            Lock lock = store.objectLock(dir);
            lock.lock();
            try {
                if (!Files.exists(dir.resolve("upload.json"))) throw noSuchUpload(); // aborted or completed meanwhile
                String name = String.format("part-%05d.json", number);
                Map<String, Object> old = readPart(dir.resolve(name));
                Path tmp = dir.resolve("." + name + "-" + UUID.randomUUID() + ".tmp");
                try (FileChannel ch = FileChannel.open(tmp, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                    LocalObjectStore.writeFully(ch, json, json.length);
                    store.force(ch);
                }
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                store.force(dir);
                if (old != null) Files.deleteIfExists(dir.resolve((String) old.get("file")));
            } finally {
                lock.unlock();
            }
            return part;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(data);
            throw e;
        }
    }

    List<UploadedPart> parts(String bucket, String key, String id) throws IOException {
        get(bucket, key, id);
        List<UploadedPart> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Object>> e : readParts(bucketUploads(bucket).resolve(id)).entrySet()) {
            out.add(new UploadedPart(toPart(e.getValue()), Instant.ofEpochMilli((Long) e.getValue().get("lastModified"))));
        }
        return out;
    }

    ObjectInfo complete(String bucket, String key, String id, List<CompletedPart> requested) throws IOException {
        Path bucketDir = store.requireBucket(bucket);
        Path dir = bucketUploads(bucket).resolve(id);
        Lock lock = store.objectLock(dir);
        lock.lock();
        try {
            Upload u = get(bucket, key, id);
            if (requested.isEmpty()) throw new S3Exception(400, "MalformedXML", "You must specify at least one part");
            Map<Integer, Map<String, Object>> stored = readParts(dir);
            List<PartInfo> parts = new ArrayList<>();
            List<String> files = new ArrayList<>();
            int previous = 0;
            for (CompletedPart c : requested) {
                if (c.number() <= previous) throw new S3Exception(400, "InvalidPartOrder", "The list of parts was not in ascending order. Parts must be ordered by part number.");
                previous = c.number();
                Map<String, Object> m = stored.get(c.number());
                if (m == null || !m.get("etag").equals(c.etag())) throw invalidPart();
                PartInfo p = toPart(m);
                if (c.checksum() != null && !c.checksum().equals(p.checksum())) throw invalidPart();
                parts.add(p);
                files.add((String) m.get("file"));
            }
            for (int i = 0; i < parts.size() - 1; i++) {
                if (parts.get(i).size() < MIN_PART) {
                    throw new S3Exception(400, "EntityTooSmall", "Your proposed upload is smaller than the minimum allowed object size.");
                }
            }
            MessageDigest md5s = LocalObjectStore.md5();
            long size = 0;
            for (PartInfo p : parts) {
                md5s.update(HexFormat.of().parseHex(p.etag()));
                size += p.size();
            }
            String etag = HexFormat.of().formatHex(md5s.digest()) + "-" + parts.size();
            Map<String, String> checksums = new LinkedHashMap<>();
            if (u.checksumAlgorithm() != null) checksums.put(u.checksumAlgorithm(), objectChecksum(u, parts));

            // The object keeps the parts' files: hard links, renumbered 1..N in the object's directory.
            KeyCodec.Location loc = KeyCodec.locate(key);
            Path objectDir = LocalObjectStore.objectDir(bucketDir, loc);
            String batch = UUID.randomUUID().toString();
            List<String> linked = new ArrayList<>();
            List<PartInfo> renumbered = new ArrayList<>();
            boolean committed = false;
            try {
                for (int i = 0; i < parts.size(); i++) {
                    String name = ".data-" + batch + "-" + (i + 1);
                    store.linkFile(bucketDir, loc, name, dir.resolve(files.get(i)));
                    linked.add(name);
                    PartInfo p = parts.get(i);
                    renumbered.add(new PartInfo(i + 1, p.size(), p.etag(), p.crc32c(), p.checksum()));
                }
                store.force(objectDir);
                ObjectInfo info = new ObjectInfo(key, size, etag, Instant.ofEpochMilli(store.clock.millis()), u.attributes().contentType(),
                        Map.copyOf(u.attributes().userMetadata()), Map.copyOf(u.attributes().headers()), Map.copyOf(checksums),
                        List.copyOf(renumbered));
                store.commit(bucketDir, loc, MetaFile.encode(info, linked, null), linked, key);
                committed = true;
                // The object is complete; the upload's own links to the bytes go.
                LocalObjectStore.deleteTree(dir);
                store.force(dir.getParent());
                return info;
            } catch (IOException | RuntimeException e) {
                // Before the commit, the links are strays; after it, they are the object.
                if (!committed) for (String name : linked) Files.deleteIfExists(objectDir.resolve(name));
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    void abort(String bucket, String key, String id) throws IOException {
        Path dir = bucketUploads(bucket).resolve(id);
        Lock lock = store.objectLock(dir);
        lock.lock();
        try {
            get(bucket, key, id);
            Files.delete(dir.resolve("upload.json"));
            store.force(dir);
            LocalObjectStore.deleteTree(dir);
            store.force(dir.getParent());
        } finally {
            lock.unlock();
        }
    }

    /** Every upload in progress in the bucket, sorted by key and then by initiation time. */
    List<Upload> list(String bucket) throws IOException {
        store.requireBucket(bucket);
        List<Upload> out = new ArrayList<>();
        Path b = bucketUploads(bucket);
        if (!Files.isDirectory(b)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(b)) {
            for (Path p : ds) {
                Upload u = read(b, p.getFileName().toString());
                if (u != null) out.add(u);
            }
        }
        out.sort((x, y) -> {
            int c = Utf8Order.compare(x.key(), y.key());
            return c != 0 ? c : x.initiated().compareTo(y.initiated());
        });
        return out;
    }

    /**
     * The object's checksum from its parts': for CRCs and a full-object type, the CRC of all the bytes,
     * combined from the parts' CRCs without reading them; otherwise S3's composite form, the checksum of
     * the parts' checksums followed by {@code -N}.
     */
    static String objectChecksum(Upload u, List<PartInfo> parts) {
        ChecksumAlgorithm alg = ChecksumAlgorithm.valueOf(u.checksumAlgorithm());
        for (PartInfo p : parts) if (p.checksum() == null) throw invalidPart();
        if ("FULL_OBJECT".equals(u.checksumType())) {
            CrcCombine c = CrcCombine.of(alg);
            long crc = value(parts.getFirst().checksum());
            for (int i = 1; i < parts.size(); i++) crc = c.combine(crc, value(parts.get(i).checksum()), parts.get(i).size());
            int bytes = c.width() / 8;
            byte[] out = ByteBuffer.allocate(8).putLong(crc).array();
            return Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(out, 8 - bytes, 8));
        }
        ChecksumAlgorithm.Hasher h = alg.newHasher();
        for (PartInfo p : parts) {
            byte[] raw = Base64.getDecoder().decode(p.checksum());
            h.update(raw, 0, raw.length);
        }
        return h.base64() + "-" + parts.size();
    }

    private static long value(String base64) {
        long v = 0;
        for (byte b : Base64.getDecoder().decode(base64)) v = (v << 8) | (b & 0xff);
        return v;
    }

    private static Map<String, Object> partJson(PartInfo p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1);
        m.put("number", p.number());
        m.put("size", p.size());
        m.put("etag", p.etag());
        m.put("crc32c", p.crc32c());
        if (p.checksum() != null) m.put("checksum", p.checksum());
        return m;
    }

    private static PartInfo toPart(Map<String, Object> m) {
        return new PartInfo(((Long) m.get("number")).intValue(), (Long) m.get("size"), (String) m.get("etag"), (String) m.get("crc32c"),
                (String) m.get("checksum"));
    }

    private static Map<Integer, Map<String, Object>> readParts(Path dir) throws IOException {
        Map<Integer, Map<String, Object>> out = new java.util.TreeMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "part-*.json")) {
            for (Path p : ds) {
                Map<String, Object> m = readPart(p);
                if (m != null) out.put(((Long) m.get("number")).intValue(), m);
            }
        } catch (NoSuchFileException e) {
            throw noSuchUpload();
        }
        return out;
    }

    private static Map<String, Object> readPart(Path p) throws IOException {
        try {
            return Json.parseObject(Files.readString(p).trim());
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Upload read(Path bucketUploads, String id) throws IOException {
        if (!id.matches("[0-9a-f]{32}")) return null;
        Map<String, Object> m;
        try {
            m = Json.parseObject(Files.readString(bucketUploads.resolve(id).resolve("upload.json")).trim());
        } catch (NoSuchFileException e) {
            return null;
        }
        Map<String, String> meta = new LinkedHashMap<>(), headers = new LinkedHashMap<>();
        ((Map<String, Object>) m.get("meta")).forEach((k, v) -> meta.put(k, (String) v));
        ((Map<String, Object>) m.get("headers")).forEach((k, v) -> headers.put(k, (String) v));
        return new Upload(id, (String) m.get("key"), Instant.ofEpochMilli((Long) m.get("initiated")),
                new NewObject((String) m.get("contentType"), meta, headers, Map.of()),
                (String) m.get("checksumAlgorithm"), (String) m.get("checksumType"));
    }

    static S3Exception noSuchUpload() {
        return new S3Exception(404, "NoSuchUpload",
                "The specified upload does not exist. The upload ID may be invalid, or the upload may have been aborted or completed.");
    }

    private static S3Exception invalidPart() {
        return new S3Exception(400, "InvalidPart",
                "One or more of the specified parts could not be found. The part may not have been uploaded, or the specified entity tag may not match the part's entity tag.");
    }
}
