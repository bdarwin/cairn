package io.github.bdarwin.cairn.internal.store;

import io.github.bdarwin.cairn.internal.store.DirectoryIndex.Item;
import io.github.bdarwin.cairn.internal.store.DirectoryIndex.Kind;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Walks a bucket's directories and yields keys in S3's order, UTF-8 bytes.
 *
 * <p>A depth-first walk is not that order: {@code -} (0x2D) sorts before {@code /} (0x2F), so key
 * {@code a-c} comes before {@code a/b}, although the walk meets directory {@code a} first. Each
 * directory is therefore read whole and sorted with a directory {@code d} compared as {@code d/},
 * and the object stored in it as {@code d}. Overflow entries (keys too long for real directories)
 * sort by the rest of their key, read from their metadata.
 *
 * <p>Each directory is entered at the first entry that can matter (binary search on the prefix and
 * on the continuation point) and left as soon as its entries pass the prefix. With a delimiter, a
 * subdirectory whose keys all roll up to one common prefix is not walked: any one object in it is
 * enough to report the prefix.
 */
final class ListingWalk {

    private static final class Frame {
        final Path dir;
        final String keyPrefix;
        final List<Item> items;
        int next;

        Frame(Path dir, String keyPrefix, List<Item> items, int start) {
            this.dir = dir;
            this.keyPrefix = keyPrefix;
            this.items = items;
            this.next = start;
        }
    }

    private final DirectoryIndex index;
    private final String prefix;
    private final String delimiter;
    private final String after;
    private final boolean afterIsPrefix;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private String lastPrefix;

    private ListingWalk(DirectoryIndex index, String prefix, String delimiter, String after, boolean afterIsPrefix) {
        this.index = index;
        this.prefix = prefix == null ? "" : prefix;
        this.delimiter = delimiter == null || delimiter.isEmpty() ? null : delimiter;
        this.after = after;
        this.afterIsPrefix = afterIsPrefix;
    }

    /** Starts at the deepest directory that holds every key with the query's prefix. */
    static ListingWalk start(DirectoryIndex index, Path bucketDir, ListQuery q) throws IOException {
        ListingWalk w = new ListingWalk(index, q.prefix(), q.delimiter(), q.after(), q.afterIsPrefix());
        Path dir = bucketDir;
        String keyPrefix = "";
        List<String> segments = KeyCodec.segments(w.prefix);
        for (int i = 0; i < segments.size() - 1; i++) {
            String next = keyPrefix + segments.get(i) + "/";
            // Does prefix "next" have a real directory? Only if a key under it would be placed there.
            if (KeyCodec.locate(next + "x").directories().size() < i + 1) break;
            dir = dir.resolve(KeyCodec.encodeSegment(segments.get(i)));
            keyPrefix = next;
            if (!Files.isDirectory(dir)) break;
        }
        w.push(dir, keyPrefix);
        return w;
    }

    private void push(Path dir, String keyPrefix) throws IOException {
        List<Item> items = index.items(dir, keyPrefix);
        // Skip, by binary search, entries before the prefix and before the continuation point.
        int start = 0;
        if (prefix.length() > keyPrefix.length() && prefix.startsWith(keyPrefix)) {
            start = DirectoryIndex.lowerBound(items, prefix.substring(keyPrefix.length()));
        }
        if (after != null && after.startsWith(keyPrefix)) {
            String rel = after.substring(keyPrefix.length());
            int slash = rel.indexOf('/');
            // Entries for the segment holding "after" (its object, its subtree) come at or after this point.
            start = Math.max(start, DirectoryIndex.lowerBound(items, slash < 0 ? rel : rel.substring(0, slash)));
        }
        stack.push(new Frame(dir, keyPrefix, items, start));
    }

    /** The next object or common prefix, or null at the end. */
    ListPage.Entry next() throws IOException {
        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            if (f.next >= f.items.size()) {
                stack.pop();
                continue;
            }
            Item item = f.items.get(f.next++);
            String full = f.keyPrefix + item.rel();
            if (!full.startsWith(prefix) && !prefix.startsWith(full)) {
                // Entries are sorted: once past the prefix, nothing later in this directory matches.
                if (Utf8Order.compare(full, prefix) > 0) stack.pop();
                continue;
            }
            Path path = f.dir.resolve(item.name());
            if (item.kind() == Kind.SUBTREE) {
                if (after != null && Utf8Order.compare(full, after) < 0 && !after.startsWith(full)) continue;
                if (afterIsPrefix && full.startsWith(after)) continue;
                String rolled = rollUp(full);
                if (rolled != null) {
                    // Every key below starts with "full", so all of them roll up to "rolled".
                    if (rolled.equals(lastPrefix)) continue;
                    if (afterIsPrefix && rolled.equals(after)) continue;
                    if (!hasKeyBelow(path, full)) continue;
                    lastPrefix = rolled;
                    return new ListPage.Entry(rolled, null);
                }
                push(path, full);
                continue;
            }
            String key = full;
            if (!key.startsWith(prefix)) continue;
            if (after != null && (Utf8Order.compare(key, after) <= 0 || (afterIsPrefix && key.startsWith(after)))) continue;
            MetaFile.Contents c = readMeta(path);
            if (c == null || !c.info().key().equals(key)) continue;
            String rolled = rollUp(key);
            if (rolled != null) {
                if (rolled.equals(lastPrefix)) continue;
                lastPrefix = rolled;
                return new ListPage.Entry(rolled, null);
            }
            return new ListPage.Entry(key, c.info());
        }
        return null;
    }

    /**
     * Whether the directory for key prefix {@code keyPrefix} holds any key the query would return. Any
     * one is enough, so this stops at the first, in whatever order the filesystem gives entries.
     */
    private boolean hasKeyBelow(Path dir, String keyPrefix) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (KeyCodec.isOverflowName(name)) {
                    MetaFile.Contents c = readMeta(p);
                    if (c != null && passes(c.info().key())) return true;
                    continue;
                }
                String segment;
                try {
                    segment = KeyCodec.decodeSegment(name);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String key = keyPrefix + segment;
                if (passes(key) && readMeta(p) != null) return true;
                String sub = key + "/";
                boolean subtreeCanPass = after == null || Utf8Order.compare(sub, after) > 0 || after.startsWith(sub);
                if (subtreeCanPass && !(afterIsPrefix && sub.startsWith(after)) && hasKeyBelow(p, sub)) return true;
            }
        } catch (NoSuchFileException | NotDirectoryException e) {
            return false;
        }
        return false;
    }

    private boolean passes(String key) {
        return key.startsWith(prefix) && (after == null || (Utf8Order.compare(key, after) > 0 && !(afterIsPrefix && key.startsWith(after))));
    }

    /** The common prefix {@code s} rolls up to, or null if the delimiter does not occur after the prefix. */
    private String rollUp(String s) {
        if (delimiter == null || !s.startsWith(prefix)) return null;
        int i = s.indexOf(delimiter, prefix.length());
        return i < 0 ? null : s.substring(0, i + delimiter.length());
    }

    static MetaFile.Contents readMeta(Path dir) throws IOException {
        try {
            return MetaFile.decode(Files.readAllBytes(dir.resolve(LocalObjectStore.META)));
        } catch (NoSuchFileException | NotDirectoryException e) {
            return null;
        }
    }
}
