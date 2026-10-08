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

    /** Deletes the object; false if there was none. */
    boolean delete(String bucket, String key) throws IOException;

    @Override
    default void close() throws IOException {
    }

    /** Runs after the body is fully read and before the object is committed. Throw to abandon the write. */
    @FunctionalInterface
    interface BeforeCommit {
        void check(long size, byte[] md5) throws IOException;
    }
}
