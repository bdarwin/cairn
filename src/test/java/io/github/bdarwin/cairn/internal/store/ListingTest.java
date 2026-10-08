package io.github.bdarwin.cairn.internal.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Listing order and roll-up, checked against a sorted list of every key. */
class ListingTest {

    /** Characters that break naive orders: '-' < '/' < letters, case, '%', U+E000-U+FFFF against above U+FFFF. */
    private static final String[] ALPHABET = {"a", "b", "A", "-", "/", ".", "%", "~", " ", "é", "�", "", "😀", "a/", "/b", "ab"};

    @TempDir
    Path dir;
    LocalObjectStore store;

    @BeforeEach
    void open() throws IOException {
        store = new LocalObjectStore(dir, Clock.systemUTC(), false);
        store.createBucket("items");
    }

    @Test
    void utf8OrderIsCodePointOrder() {
        Random rnd = new Random(2);
        for (int i = 0; i < 100_000; i++) {
            String a = randomKey(rnd, 4), b = randomKey(rnd, 4);
            int expected = Integer.signum(Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
            assertEquals(expected, Integer.signum(Utf8Order.compare(a, b)), a + " vs " + b);
        }
        assertTrue(Utf8Order.compare("�", "😀") < 0, "U+FFFD sorts before U+1F600 in UTF-8, after it in UTF-16");
        assertTrue("�".compareTo("😀") > 0);
    }

    @Test
    void theExampleFromTheBrief() throws IOException {
        for (String k : List.of("a/b", "a-c", "a", "a/b/c", "a.d", "a0")) put(k);
        assertEquals(List.of("a", "a-c", "a.d", "a/b", "a/b/c", "a0"), keys(store.list("items", new ListQuery("", null, null, false, 1000))));
        assertEquals(List.of("a", "a-c", "a.d", "a/", "a0"), keys(store.list("items", new ListQuery("", "/", null, false, 1000))));
    }

    @Test
    void randomKeysAndQueriesMatchASortedList() throws IOException {
        Random rnd = new Random(42);
        TreeSet<String> all = new TreeSet<>(Utf8Order::compare);
        while (all.size() < 1000) {
            String k = randomKey(rnd, 1 + rnd.nextInt(7));
            if (k.isEmpty() || all.contains(k)) continue;
            put(k);
            all.add(k);
        }
        // A few keys long enough to live in overflow entries.
        for (int i = 0; i < 20; i++) {
            String k = randomKey(rnd, 3) + "/" + "Z".repeat(90 + i) + "/" + randomKey(rnd, 2);
            if (all.add(k)) put(k);
        }
        List<String> sorted = new ArrayList<>(all);
        List<String> prefixes = new ArrayList<>(List.of("", "a", "a/", "a-", "A", "%", "😀", "�", "ab/", "/"));
        for (int i = 0; i < 120; i++) {
            String k = sorted.get(rnd.nextInt(sorted.size()));
            int cut = rnd.nextInt(k.length() + 1);
            if (cut > 0 && cut < k.length() && Character.isLowSurrogate(k.charAt(cut))) cut--; // a prefix is valid UTF-8
            prefixes.add(k.substring(0, cut));
        }
        int checked = 0;
        for (String prefix : prefixes) {
            for (String delimiter : new String[] {null, "/", "-", "a/", "😀"}) {
                String after = rnd.nextBoolean() ? null : sorted.get(rnd.nextInt(sorted.size()));
                int pageSize = 1 + rnd.nextInt(40);
                List<String> expected = reference(sorted, prefix, delimiter, after);
                List<String> got = pages(prefix, delimiter, after, pageSize);
                assertEquals(expected, got, () -> esc("prefix '" + prefix + "' delimiter '" + delimiter + "' after '" + after + "' pages of " + pageSize
                        + "\nexpected " + expected + "\ngot      " + got));
                checked++;
            }
        }
        assertTrue(checked > 600);
    }

    /** The same checks with every directory served from the sorted-entry cache, as large ones are. */
    @Test
    void randomKeysAndQueriesMatchWithEveryDirectoryCached() throws IOException {
        store = new LocalObjectStore(dir, Clock.systemUTC(), false, new DirectoryIndex(0));
        randomKeysAndQueriesMatchASortedList();
    }

    @Test
    void cachedDirectoriesSeeNewAndDeletedKeys() throws IOException {
        store = new LocalObjectStore(dir, Clock.systemUTC(), false, new DirectoryIndex(0));
        put("k1");
        put("k3");
        assertEquals(List.of("k1", "k3"), keys(store.list("items", new ListQuery("", null, null, false, 1000))));
        put("k2");
        store.delete("items", "k1");
        assertEquals(List.of("k2", "k3"), keys(store.list("items", new ListQuery("", null, null, false, 1000))));
    }

    @Test
    void emptyDirectoriesProduceNothing() throws IOException {
        put("x/y/z");
        store.delete("items", "x/y/z");
        put("x/w");
        java.nio.file.Files.createDirectories(dir.resolve("items/x/stray/deeper"));
        assertEquals(List.of("x/w"), keys(store.list("items", new ListQuery("", null, null, false, 1000))));
        assertEquals(List.of("x/"), keys(store.list("items", new ListQuery("", "/", null, false, 1000))));
        assertEquals(List.of("x/w"), keys(store.list("items", new ListQuery("x/", "/", null, false, 1000))));
    }

    /** Lists every page, continuing after the last entry as a client's continuation token does. */
    private List<String> pages(String prefix, String delimiter, String startAfter, int pageSize) throws IOException {
        List<String> out = new ArrayList<>();
        String after = startAfter;
        boolean afterIsPrefix = false;
        for (int guard = 0; guard < 10_000; guard++) {
            ListPage page = store.list("items", new ListQuery(prefix, delimiter, after, afterIsPrefix, pageSize));
            for (ListPage.Entry e : page.entries()) out.add(e.key());
            if (!page.truncated()) return out;
            ListPage.Entry last = page.entries().getLast();
            after = last.key();
            afterIsPrefix = last.isPrefix();
        }
        throw new AssertionError("listing did not end");
    }

    /** What S3 returns, computed the obvious way. */
    static List<String> reference(List<String> sorted, String prefix, String delimiter, String after) {
        List<String> out = new ArrayList<>();
        for (String k : sorted) {
            if (!k.startsWith(prefix)) continue;
            if (after != null && Utf8Order.compare(k, after) <= 0) continue;
            String entry = k;
            if (delimiter != null) {
                int i = k.indexOf(delimiter, prefix.length());
                if (i >= 0) entry = k.substring(0, i + delimiter.length());
            }
            if (out.isEmpty() || !out.getLast().equals(entry)) out.add(entry);
        }
        return out;
    }

    private void put(String key) throws IOException {
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        store.put("items", key, new NewObject("binary/octet-stream", Map.of(), Map.of(), Map.of()), new ByteArrayInputStream(b), (s, m) -> { });
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(c -> sb.append(c < 0x20 || c > 0x7e ? String.format("\\u{%x}", c) : String.valueOf((char) c)));
        return sb.toString();
    }

    private static List<String> keys(ListPage p) {
        return p.entries().stream().map(ListPage.Entry::key).toList();
    }

    private static String randomKey(Random rnd, int pieces) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces; i++) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
