package io.github.bdarwin.cairn.internal.store;

import java.io.IOException;
import java.io.OutputStream;

/** An object open for reading. Its bytes stay readable until closed, even if it is overwritten or deleted meanwhile. */
public interface StoredObject extends AutoCloseable {

    ObjectInfo info();

    /** Writes {@code length} bytes starting at {@code offset} to {@code out}. */
    void copyTo(long offset, long length, OutputStream out) throws IOException;

    @Override
    void close() throws IOException;
}
