package io.github.bdarwin.cairn.internal.http;

import io.github.bdarwin.cairn.internal.S3Exception;

/**
 * A single byte range from a {@code Range} header, as S3 serves it: {@code bytes=a-b}, {@code bytes=a-}
 * or {@code bytes=-n}. A header S3 would ignore (another unit, several ranges, bad syntax) gives null,
 * and the whole object is served; a range that starts past the end is a 416.
 */
record Range(long first, long last) {

    long length() {
        return last - first + 1;
    }

    static Range parse(String header, long size) {
        if (header == null) return null;
        String h = header.trim();
        if (!h.startsWith("bytes=")) return null;
        String spec = h.substring(6).trim();
        if (spec.contains(",")) return null;
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
        try {
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long n = Long.parseLong(b);
                if (n < 0) return null;
                if (n == 0 || size == 0) throw unsatisfiable(size);
                return new Range(Math.max(0, size - n), size - 1);
            }
            long first = Long.parseLong(a);
            if (first < 0) return null;
            if (b.isEmpty()) {
                if (first >= size) throw unsatisfiable(size);
                return new Range(first, size - 1);
            }
            long last = Long.parseLong(b);
            if (last < first) return null;
            if (first >= size) throw unsatisfiable(size);
            return new Range(first, Math.min(last, size - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static S3Exception unsatisfiable(long size) {
        return new S3Exception(416, "InvalidRange", "The requested range is not satisfiable").withHeader("Content-Range", "bytes */" + size);
    }
}
