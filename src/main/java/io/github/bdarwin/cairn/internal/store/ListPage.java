package io.github.bdarwin.cairn.internal.store;

import java.util.List;

/**
 * A page of a listing, in key order.
 *
 * @param entries   objects and common prefixes, interleaved in UTF-8 order
 * @param truncated whether more entries follow
 */
public record ListPage(List<Entry> entries, boolean truncated) {

    /** An object, or (when {@code object} is null) a common prefix. */
    public record Entry(String key, ObjectInfo object) {
        public boolean isPrefix() {
            return object == null;
        }
    }
}
