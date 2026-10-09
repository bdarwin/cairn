package io.github.bdarwin.cairn.internal.store;

import io.github.bdarwin.cairn.internal.S3Exception;
import io.github.bdarwin.cairn.internal.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32C;

/**
 * Objects as directories on one local disk. The filesystem is the namespace and the source of truth:
 * there is no index, and nothing in memory that a restart (or another node) could not rebuild from
 * the files.
 *
 * <pre>
 * root/
 *   .cairn/format.json          layout version
 *   bucket/
 *     .bucket                   bucket metadata
 *     a/                        key "a" (escaped by KeyCodec)
 *       .meta                   key "a": metadata, and the bytes if small
 *       .data-&lt;uuid&gt;            key "a": the bytes if not
 *       b/.meta                 key "a/b"
 * </pre>
 *
 * <p>A write streams the bytes to a new {@code .data-<uuid>} file (or keeps them in memory if they fit
 * inline), forces it, writes the new {@code .meta} to a temporary file and forces it, then renames it
 * over {@code .meta}: the rename is the commit. A crash before it leaves the old object, after it the
 * new one. The old data file is deleted after the commit.
 */
public final class LocalObjectStore implements ObjectStore {

    /** Objects smaller than this are stored inside {@code .meta}, saving one force (probe 4: 10 ms against 15 ms). */
    public static final int INLINE_MAX = 128 * 1024;
    /** Longest data directory path that leaves room for {@link KeyCodec#MAX_PATH} under the 1024-byte PATH_MAX. */
    public static final int MAX_ROOT = 200;

    static final String META = ".meta";
    static final String BUCKET_META = ".bucket";
    static final int COPY_BUFFER = 1 << 20;
    private static final int STRIPES = 1024;

    private final Path root;
    final Clock clock;
    private final boolean durable;
    private final DirectoryIndex directoryIndex;
    private final LocalUploads uploads;
    private final ReentrantReadWriteLock[] bucketLocks = new ReentrantReadWriteLock[STRIPES];
    private final ReentrantLock[] objectLocks = new ReentrantLock[STRIPES];

    public LocalObjectStore(Path root, Clock clock) throws IOException {
        this(root, clock, true);
    }

    /**
     * @param durable false skips every force: writes still commit by rename and survive a process
     *                crash, but not a power loss. For tests and bulk loading; the server is always durable.
     */
    public LocalObjectStore(Path root, Clock clock, boolean durable) throws IOException {
        this(root, clock, durable, new DirectoryIndex());
    }

    LocalObjectStore(Path root, Clock clock, boolean durable, DirectoryIndex directoryIndex) throws IOException {
        this.directoryIndex = directoryIndex;
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.durable = durable;
        this.uploads = new LocalUploads(this, this.root);
        int rootLength = this.root.toString().getBytes(StandardCharsets.UTF_8).length;
        if (rootLength > MAX_ROOT) {
            throw new IllegalArgumentException("data directory path is " + rootLength + " bytes; at most " + MAX_ROOT
                    + " leaves room for every key under the 1024-byte path limit");
        }
        for (int i = 0; i < STRIPES; i++) {
            bucketLocks[i] = new ReentrantReadWriteLock();
            objectLocks[i] = new ReentrantLock();
        }
        Files.createDirectories(this.root);
        Path format = this.root.resolve(".cairn").resolve("format.json");
        if (Files.exists(format)) {
            Map<String, Object> f = Json.parseObject(Files.readString(format));
            if (!Long.valueOf(1).equals(f.get("format"))) throw new IOException("unknown data directory format: " + f);
        } else {
            Files.createDirectories(format.getParent());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("format", 1);
            f.put("layout", "object-directories");
            f.put("maxName", KeyCodec.MAX_NAME);
            f.put("maxPath", KeyCodec.MAX_PATH);
            writeDurably(format.getParent(), "format.json", (Json.write(f) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    public Path root() {
        return root;
    }

    // ---- buckets ----

    @Override
    public void createBucket(String bucket) throws IOException {
        Lock lock = bucketLock(bucket).writeLock();
        lock.lock();
        try {
            Path dir = root.resolve(bucket);
            if (Files.exists(dir.resolve(BUCKET_META))) {
                throw new S3Exception(409, "BucketAlreadyOwnedByYou", "Your previous request to create the named bucket succeeded and you already own it.");
            }
            boolean created = !Files.isDirectory(dir);
            Files.createDirectories(dir);
            if (created) force(root);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", 1);
            m.put("name", bucket);
            m.put("created", clock.millis());
            writeDurably(dir, BUCKET_META, (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteBucket(String bucket) throws IOException {
        Lock lock = bucketLock(bucket).writeLock();
        lock.lock();
        try {
            Path dir = requireBucket(bucket);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (!p.getFileName().toString().startsWith(".") && containsObject(p)) {
                        throw new S3Exception(409, "BucketNotEmpty", "The bucket you tried to delete is not empty");
                    }
                }
            }
            Files.delete(dir.resolve(BUCKET_META));
            force(dir);
            deleteTree(dir);
            force(root);
            // Uploads in progress go with the bucket.
            deleteTree(uploads.bucketUploads(bucket));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        return Files.exists(root.resolve(bucket).resolve(BUCKET_META));
    }

    @Override
    public List<BucketInfo> listBuckets() throws IOException {
        List<BucketInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                Path meta = p.resolve(BUCKET_META);
                try {
                    Map<String, Object> m = Json.parseObject(Files.readString(meta));
                    out.add(new BucketInfo(name, Instant.ofEpochMilli((Long) m.get("created"))));
                } catch (NoSuchFileException e) {
                    // a directory that is not (or no longer) a bucket
                }
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    // ---- objects ----

    @Override
    public ObjectInfo put(String bucket, String key, NewObject attributes, InputStream body, BeforeCommit check) throws IOException {
        Lock lock = bucketLock(bucket).readLock();
        lock.lock();
        try {
            Path bucketDir = requireBucket(bucket);
            KeyCodec.Location loc = KeyCodec.locate(key);
            Path dir = objectDir(bucketDir, loc);
            MessageDigest md5 = md5();
            CRC32C crc = new CRC32C();

            byte[] head = new byte[INLINE_MAX];
            int n = body.readNBytes(head, 0, INLINE_MAX);
            md5.update(head, 0, n);
            crc.update(head, 0, n);
            long size = n;
            byte[] inline = null;
            String dataFile = null;
            if (n < INLINE_MAX) {
                inline = java.util.Arrays.copyOf(head, n);
            } else {
                dataFile = ".data-" + UUID.randomUUID();
                Path data = null;
                try {
                    FileChannel ch = createFile(bucketDir, loc, dataFile);
                    data = dir.resolve(dataFile);
                    try (ch) {
                        writeFully(ch, head, n);
                        byte[] buf = new byte[COPY_BUFFER];
                        for (int r; (r = body.readNBytes(buf, 0, buf.length)) > 0; ) {
                            md5.update(buf, 0, r);
                            crc.update(buf, 0, r);
                            writeFully(ch, buf, r);
                            size += r;
                        }
                        CrashPoints.reached(CrashPoints.DATA_WRITTEN);
                        force(ch);
                    }
                    CrashPoints.reached(CrashPoints.DATA_FORCED);
                } catch (IOException | RuntimeException e) {
                    if (data != null) Files.deleteIfExists(data);
                    throw e;
                }
            }
            byte[] digest = md5.digest();
            try {
                check.check(size, digest);
            } catch (IOException | RuntimeException e) {
                if (dataFile != null) Files.deleteIfExists(dir.resolve(dataFile));
                throw e;
            }
            String etag = HexFormat.of().formatHex(digest);
            String crc32c = base64(crc.getValue());
            ObjectInfo info = new ObjectInfo(key, size, etag, Instant.ofEpochMilli(clock.millis()), attributes.contentType(),
                    Map.copyOf(attributes.userMetadata()), Map.copyOf(attributes.headers()), Map.copyOf(attributes.checksums()),
                    List.of(new PartInfo(1, size, etag, crc32c, null)));
            List<String> files = dataFile == null ? List.of() : List.of(dataFile);
            try {
                commit(bucketDir, loc, MetaFile.encode(info, files, inline), files, key);
            } catch (IOException | RuntimeException e) {
                if (dataFile != null) Files.deleteIfExists(dir.resolve(dataFile));
                throw e;
            }
            return info;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes the new {@code .meta} beside the old one, then renames it into place under the object's
     * lock: the commit. Afterwards deletes the data files of the version it replaced.
     */
    void commit(Path bucketDir, KeyCodec.Location loc, byte[] meta, List<String> dataFiles, String key) throws IOException {
        Path dir = objectDir(bucketDir, loc);
        String tmpName = ".meta-" + UUID.randomUUID() + ".tmp";
        try (FileChannel ch = createFile(bucketDir, loc, tmpName)) {
            writeFully(ch, meta, meta.length);
            force(ch);
        }
        CrashPoints.reached(CrashPoints.META_FORCED);
        Path tmp = dir.resolve(tmpName);
        ReentrantLock lock = objectLock(dir);
        lock.lock();
        try {
            MetaFile.Contents old = readMeta(dir);
            if (loc.overflow() && old != null && !old.info().key().equals(key)) {
                Files.deleteIfExists(tmp);
                throw S3Exception.internal("key hash collision in overflow entry " + loc.name());
            }
            Files.move(tmp, dir.resolve(META), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            CrashPoints.reached(CrashPoints.RENAMED);
            force(dir);
            if (old != null) {
                for (String f : old.dataFiles()) if (!dataFiles.contains(f)) Files.deleteIfExists(dir.resolve(f));
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public StoredObject get(String bucket, String key) throws IOException {
        Path bucketDir = requireBucket(bucket);
        KeyCodec.Location loc = KeyCodec.locate(key);
        Path dir = objectDir(bucketDir, loc);
        for (int attempt = 0; attempt < 5; attempt++) {
            MetaFile.Contents c = readMeta(dir);
            if (c == null || !c.info().key().equals(key)) return null;
            if (c.inline() != null) return new InlineObject(c.info(), c.inline());
            List<FileChannel> channels = new ArrayList<>();
            try {
                for (String f : c.dataFiles()) channels.add(FileChannel.open(dir.resolve(f), StandardOpenOption.READ));
                return new FileObject(c.info(), channels);
            } catch (NoSuchFileException e) {
                // Replaced between reading .meta and opening its data files: read .meta again.
                for (FileChannel ch : channels) ch.close();
            }
        }
        throw S3Exception.internal("object kept changing while being opened");
    }

    @Override
    public ObjectInfo head(String bucket, String key) throws IOException {
        Path bucketDir = requireBucket(bucket);
        MetaFile.Contents c = readMeta(objectDir(bucketDir, KeyCodec.locate(key)));
        return c == null || !c.info().key().equals(key) ? null : c.info();
    }

    @Override
    public ListPage list(String bucket, ListQuery query) throws IOException {
        Path bucketDir = requireBucket(bucket);
        ListingWalk walk = ListingWalk.start(directoryIndex, bucketDir, query);
        List<ListPage.Entry> entries = new ArrayList<>(Math.min(query.maxKeys(), 1000));
        while (entries.size() < query.maxKeys()) {
            ListPage.Entry e = walk.next();
            if (e == null) return new ListPage(entries, false);
            entries.add(e);
        }
        return new ListPage(entries, walk.next() != null);
    }

    @Override
    public boolean delete(String bucket, String key) throws IOException {
        Lock bucketLock = bucketLock(bucket).readLock();
        bucketLock.lock();
        try {
            Path bucketDir = requireBucket(bucket);
            Path dir = objectDir(bucketDir, KeyCodec.locate(key));
            ReentrantLock lock = objectLock(dir);
            lock.lock();
            try {
                MetaFile.Contents c = readMeta(dir);
                if (c == null || !c.info().key().equals(key)) return false;
                Files.delete(dir.resolve(META));
                force(dir);
                for (String f : c.dataFiles()) Files.deleteIfExists(dir.resolve(f));
            } finally {
                lock.unlock();
            }
            prune(dir, bucketDir);
            return true;
        } finally {
            bucketLock.unlock();
        }
    }

    // ---- multipart uploads ----

    @Override
    public Upload createUpload(String bucket, String key, NewObject attributes, String checksumAlgorithm, String checksumType) throws IOException {
        Lock lock = bucketLock(bucket).readLock();
        lock.lock();
        try {
            return uploads.create(bucket, key, attributes, checksumAlgorithm, checksumType);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Upload upload(String bucket, String key, String uploadId) throws IOException {
        return uploads.get(bucket, key, uploadId);
    }

    @Override
    public PartInfo putPart(String bucket, String key, String uploadId, int partNumber, InputStream body, BeforeCommit check,
                            Map<String, String> checksums) throws IOException {
        Lock lock = bucketLock(bucket).readLock();
        lock.lock();
        try {
            return uploads.putPart(bucket, key, uploadId, partNumber, body, check, checksums);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<UploadedPart> parts(String bucket, String key, String uploadId) throws IOException {
        return uploads.parts(bucket, key, uploadId);
    }

    @Override
    public ObjectInfo completeUpload(String bucket, String key, String uploadId, List<CompletedPart> parts) throws IOException {
        Lock lock = bucketLock(bucket).readLock();
        lock.lock();
        try {
            return uploads.complete(bucket, key, uploadId, parts);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void abortUpload(String bucket, String key, String uploadId) throws IOException {
        Lock lock = bucketLock(bucket).readLock();
        lock.lock();
        try {
            uploads.abort(bucket, key, uploadId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Upload> uploads(String bucket) throws IOException {
        return uploads.list(bucket);
    }

    // ---- helpers ----

    Path requireBucket(String bucket) {
        Path dir = root.resolve(bucket);
        if (!Files.exists(dir.resolve(BUCKET_META))) throw S3Exception.noSuchBucket();
        return dir;
    }

    static Path objectDir(Path bucketDir, KeyCodec.Location loc) {
        Path p = bucketDir;
        for (String d : loc.directories()) p = p.resolve(d);
        return p.resolve(loc.name());
    }

    /**
     * Creates {@code name} in the object's directory, creating the directories on the way and forcing
     * each parent that gained an entry. Retries if a concurrent delete prunes a directory meanwhile.
     */
    FileChannel createFile(Path bucketDir, KeyCodec.Location loc, String name) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Path dir = ensureDirectories(bucketDir, loc);
            try {
                return FileChannel.open(dir.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (NoSuchFileException e) {
                if (attempt >= 10) throw e;
            }
        }
    }

    /** Hard-links {@code target} into the object's directory as {@code name}, creating directories as {@link #createFile} does. */
    void linkFile(Path bucketDir, KeyCodec.Location loc, String name, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Path dir = ensureDirectories(bucketDir, loc);
            try {
                Files.createLink(dir.resolve(name), target);
                return;
            } catch (NoSuchFileException e) {
                if (attempt >= 10 || !Files.exists(target)) throw e;
            }
        }
    }

    private Path ensureDirectories(Path bucketDir, KeyCodec.Location loc) throws IOException {
        Path dir = bucketDir;
        List<String> names = new ArrayList<>(loc.directories());
        names.add(loc.name());
        for (String d : names) {
            Path next = dir.resolve(d);
            if (!Files.isDirectory(next)) {
                try {
                    Files.createDirectory(next);
                    force(dir);
                } catch (FileAlreadyExistsException e) {
                    // created concurrently
                } catch (NoSuchFileException e) {
                    return next; // a parent was pruned meanwhile; the caller's retry starts again
                }
            }
            dir = next;
        }
        return dir;
    }

    static MetaFile.Contents readMeta(Path dir) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(dir.resolve(META));
        } catch (NoSuchFileException e) {
            return null;
        } catch (java.nio.file.FileSystemException e) {
            if (e.getMessage() != null && e.getMessage().contains("Not a directory")) return null;
            throw e;
        }
        return MetaFile.decode(bytes);
    }

    /** Removes now-empty directories from {@code dir} up to, not including, the bucket directory. */
    private void prune(Path dir, Path bucketDir) throws IOException {
        // A writer that loses a directory to this between creating it and creating its file retries (createFile).
        for (Path p = dir; p != null && !p.equals(bucketDir) && p.startsWith(bucketDir); p = p.getParent()) {
            try {
                Files.delete(p);
            } catch (DirectoryNotEmptyException e) {
                return;
            } catch (NoSuchFileException e) {
                // gone already; keep going up
            }
        }
    }

    private static boolean containsObject(Path p) throws IOException {
        if (!Files.isDirectory(p)) return false;
        if (Files.exists(p.resolve(META))) return true;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            for (Path c : ds) {
                if (!c.getFileName().toString().startsWith(".") && containsObject(c)) return true;
            }
        }
        return false;
    }

    static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteTree(c);
            }
        }
        Files.deleteIfExists(p);
    }

    /** Writes {@code name} in {@code dir} through a forced temporary file and a rename, then forces the directory. */
    void writeDurably(Path dir, String name, byte[] bytes) throws IOException {
        Path tmp = dir.resolve("." + name + "-" + UUID.randomUUID() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(ch, bytes, bytes.length);
            force(ch);
        }
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        force(dir);
    }

    void force(Path dir) throws IOException {
        if (!durable) return;
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        }
    }

    void force(FileChannel ch) throws IOException {
        if (durable) ch.force(true);
    }

    static void writeFully(FileChannel ch, byte[] b, int n) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(b, 0, n);
        while (buf.hasRemaining()) ch.write(buf);
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base64(long crc32) {
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt((int) crc32).array());
    }

    ReentrantReadWriteLock bucketLock(String bucket) {
        return bucketLocks[Math.floorMod(bucket.hashCode(), STRIPES)];
    }

    ReentrantLock objectLock(Path dir) {
        return objectLocks[Math.floorMod(dir.hashCode(), STRIPES)];
    }

    private record InlineObject(ObjectInfo info, byte[] data) implements StoredObject {
        public void copyTo(long offset, long length, OutputStream out) throws IOException {
            out.write(data, (int) offset, (int) length);
        }

        public void close() {
        }
    }

    /** Bytes in one data file per part, read through channels opened together, so one version is read. */
    private record FileObject(ObjectInfo info, List<FileChannel> channels) implements StoredObject {
        public void copyTo(long offset, long length, OutputStream out) throws IOException {
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(COPY_BUFFER, Math.max(length, 1)));
            long partStart = 0, end = offset + length;
            for (int i = 0; i < channels.size() && partStart < end; i++) {
                long partSize = info.parts().get(i).size();
                long partEnd = partStart + partSize;
                if (partEnd > offset) {
                    FileChannel ch = channels.get(i);
                    long pos = Math.max(offset, partStart) - partStart, stop = Math.min(end, partEnd) - partStart;
                    while (pos < stop) {
                        buf.clear().limit((int) Math.min(buf.capacity(), stop - pos));
                        int n = ch.read(buf, pos);
                        if (n < 0) throw new IOException("data file shorter than its metadata says");
                        out.write(buf.array(), 0, n);
                        pos += n;
                    }
                }
                partStart = partEnd;
            }
        }

        public void close() throws IOException {
            for (FileChannel ch : channels) ch.close();
        }
    }
}
