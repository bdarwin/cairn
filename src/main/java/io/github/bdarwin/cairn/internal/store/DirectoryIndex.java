package io.github.bdarwin.cairn.internal.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sorted entries of a directory, as the listing walk needs them, cached for large directories.
 *
 * <p>Measured on a directory of 1,000,000 keys (docs/listing.md): reading its names takes 1.5 s
 * with a warm cache and sorting them 0.3 s, against 20 ms for the 1,000 metadata files a page needs.
 * Re-reading the directory for every page made a flat listing cost seconds a page. Directories of
 * {@link #CACHE_FROM} entries or more are therefore kept sorted in memory, keyed by the directory's
 * modification time: an entry created or removed in it changes that time, and the next listing
 * reads it again. Nothing here is state: drop it and it is rebuilt from disk.
 */
final class DirectoryIndex {

    /** Smaller directories are read on every listing: 10,000 names read and sorted in about 20 ms. */
    static final int CACHE_FROM = 10_000;
    /** Bound on cached entries across all directories. */
    static final long MAX_ENTRIES = 4_000_000;

    enum Kind { OBJECT, SUBTREE, OVERFLOW }

    /** One sortable entry: {@code rel} is the key text after the directory's prefix; {@code name} the file name. */
    record Item(String rel, Kind kind, String name) {
    }

    private record Cached(FileTime modified, List<Item> items) {
    }

    private final Map<Path, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final int cacheFrom;
    private long cachedEntries;

    DirectoryIndex() {
        this(CACHE_FROM);
    }

    /** {@code cacheFrom} 0 caches every directory; for tests. */
    DirectoryIndex(int cacheFrom) {
        this.cacheFrom = cacheFrom;
    }

    /** The directory's entries, sorted in UTF-8 order of {@code rel}; empty if it does not exist. */
    List<Item> items(Path dir, String keyPrefix) throws IOException {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(dir);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        synchronized (this) {
            Cached c = cache.get(dir);
            if (c != null && c.modified.equals(modified)) return c.items;
        }
        List<Item> items = read(dir, keyPrefix);
        if (items.size() >= cacheFrom) {
            synchronized (this) {
                Cached old = cache.put(dir, new Cached(modified, items));
                if (old != null) cachedEntries -= old.items.size();
                cachedEntries += items.size();
                for (Iterator<Cached> it = cache.values().iterator(); cachedEntries > MAX_ENTRIES && it.hasNext(); ) {
                    Cached evict = it.next();
                    if (evict.items == items) continue;
                    cachedEntries -= evict.items.size();
                    it.remove();
                }
            }
        }
        return items;
    }

    private static List<Item> read(Path dir, String keyPrefix) throws IOException {
        List<Item> items = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (KeyCodec.isOverflowName(name)) {
                    // The key of an overflow entry never changes (its name is the key's hash), so it can be cached.
                    MetaFile.Contents c = ListingWalk.readMeta(p);
                    if (c != null && c.info().key().startsWith(keyPrefix)) {
                        items.add(new Item(c.info().key().substring(keyPrefix.length()), Kind.OVERFLOW, name));
                    }
                    continue;
                }
                String segment;
                try {
                    segment = KeyCodec.decodeSegment(name);
                } catch (IllegalArgumentException e) {
                    continue; // not a name cairn wrote
                }
                items.add(new Item(segment, Kind.OBJECT, name));
                items.add(new Item(segment + "/", Kind.SUBTREE, name));
            }
        } catch (NoSuchFileException | NotDirectoryException e) {
            return List.of();
        }
        items.sort((a, b) -> Utf8Order.compare(a.rel, b.rel));
        return Collections.unmodifiableList(items);
    }

    /** The first index whose {@code rel} is not before {@code rel}. */
    static int lowerBound(List<Item> items, String rel) {
        int lo = 0, hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Utf8Order.compare(items.get(mid).rel, rel) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
