package io.github.bdarwin.cairn.internal.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Where objects' bytes live and how they are found. The one implementation, {@link LocalObjectStore},
 * keeps them in a directory tree on one disk; another could place them on several nodes. Every
 * method is safe to call concurrently. Errors a client should see are thrown as
 * {@link io.github.bdarwin.cairn.internal.S3Exception}.
 */
public interface ObjectStore extends AutoCloseable {

    void createBucket(String bucket) throws IOException;

    /** Deletes an empty bucket. */
    void deleteBucket(String bucket) throws IOException;

    boolean bucketExists(String bucket) throws IOException;

    List<BucketInfo> listBuckets() throws IOException;

    /**
     * Stores {@code body} as {@code key}, replacing any object there. The body is read to its end
     * before anything becomes visible; then {@code check} runs, and only if it returns normally is the
     * new object committed. A failure at any point, including a crash, leaves the previous object.
     */
    ObjectInfo put(String bucket, String key, NewObject attributes, InputStream body, BeforeCommit check) throws IOException;

    /** The object, open for reading, or null if there is none. The caller closes it. */
    StoredObject get(String bucket, String key) throws IOException;

    /** The object's metadata, or null. */
    ObjectInfo head(String bucket, String key) throws IOException;

    /** A page of the bucket's keys in UTF-8 order, rolled up by the delimiter if there is one. */
    ListPage list(String bucket, ListQuery query) throws IOException;

    /** Deletes the object; false if there was none. */
    boolean delete(String bucket, String key) throws IOException;

    // ---- multipart uploads ----

    /** Starts a multipart upload; {@code checksumAlgorithm} (an algorithm name) and {@code checksumType} may be null. */
    Upload createUpload(String bucket, String key, NewObject attributes, String checksumAlgorithm, String checksumType) throws IOException;

    /** The upload, if it exists and is for {@code key}; NoSuchUpload otherwise. */
    Upload upload(String bucket, String key, String uploadId) throws IOException;

    /**
     * Stores a part, replacing one with the same number. {@code check} runs before the part is kept and
     * puts the part's checksums, by algorithm name, into {@code checksums}.
     */
    PartInfo putPart(String bucket, String key, String uploadId, int partNumber, InputStream body, BeforeCommit check,
                     java.util.Map<String, String> checksums) throws IOException;

    List<UploadedPart> parts(String bucket, String key, String uploadId) throws IOException;

    /** Turns the named parts into the object, replacing any object at {@code key}, and ends the upload. */
    ObjectInfo completeUpload(String bucket, String key, String uploadId, List<CompletedPart> parts) throws IOException;

    void abortUpload(String bucket, String key, String uploadId) throws IOException;

    /** Uploads in progress, by key and then initiation time. */
    List<Upload> uploads(String bucket) throws IOException;

    @Override
    default void close() throws IOException {
    }

    /** Runs after the body is fully read and before the object is committed. Throw to abandon the write. */
    @FunctionalInterface
    interface BeforeCommit {
        void check(long size, byte[] md5) throws IOException;
    }
}
