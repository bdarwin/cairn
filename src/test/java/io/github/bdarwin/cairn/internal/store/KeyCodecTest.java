package io.github.bdarwin.cairn.internal.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyCodecTest {

    @Test
    void everyCodePointRoundTripsAsASegment() {
        for (int cp = 1; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) continue;
            if (cp == '/') continue;
            String s = new String(Character.toChars(cp));
            String name = KeyCodec.encodeSegment(s);
            assertEquals(s, KeyCodec.decodeSegment(name), () -> "code point " + Integer.toHexString(s.codePointAt(0)));
            assertSafe(name);
            String two = "x" + s + s;
            assertEquals(two, KeyCodec.decodeSegment(KeyCodec.encodeSegment(two)));
        }
    }

    @Test
    void namesAreSafeOnAFilesystemThatFoldsCaseAndNormalisation() {
        // Different segments must never give names that are equal ignoring case: APFS would see one file.
        Map<String, String> seen = new HashMap<>();
        Random rnd = new Random(1);
        List<String> segments = new ArrayList<>(List.of("note", "Note", "NOTE", "nOtE", ".", "..", "", ".hidden", "%", "%41", "%4e",
                "a%2Fb", "~", "-", "_", Normalizer.normalize("café", Normalizer.Form.NFC), Normalizer.normalize("café", Normalizer.Form.NFD),
                "\u0000", "\u0001", "\n", " ", "a.", "a..", "...", "%h00", "h"));
        for (int i = 0; i < 200_000; i++) segments.add(randomSegment(rnd));
        for (String s : segments) {
            String name = KeyCodec.encodeSegment(s);
            assertSafe(name);
            String folded = name.toLowerCase(Locale.ROOT);
            String before = seen.putIfAbsent(folded, s);
            if (before != null) assertEquals(before, s, "two segments fold to the same name " + name);
        }
    }

    @Test
    void specialSegments() {
        assertEquals("%", KeyCodec.encodeSegment(""));
        assertEquals("%2E", KeyCodec.encodeSegment("."));
        assertEquals("%2E.", KeyCodec.encodeSegment(".."));
        assertEquals("%2Ehidden", KeyCodec.encodeSegment(".hidden"));
        assertEquals("a.b", KeyCodec.encodeSegment("a.b"));
        assertEquals("%4Eote", KeyCodec.encodeSegment("Note"));
        assertEquals("note", KeyCodec.encodeSegment("note"));
        assertEquals("caf%C3%A9", KeyCodec.encodeSegment(Normalizer.normalize("café", Normalizer.Form.NFC)));
        assertEquals("cafe%CC%81", KeyCodec.encodeSegment(Normalizer.normalize("café", Normalizer.Form.NFD)));
        assertEquals("%25", KeyCodec.encodeSegment("%"));
        assertEquals("%00", KeyCodec.encodeSegment("\u0000"));
    }

    @Test
    void decodeRejectsNamesTheEncoderNeverWrites() {
        for (String bad : List.of("A", "%61", "%2e", "%4e", ".x", "%h0123", "%", "%Z1", "%4", "a b", "é", "%C3", "%FF")) {
            if (bad.equals("%")) {
                assertEquals("", KeyCodec.decodeSegment(bad));
                continue;
            }
            assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeSegment(bad), bad);
        }
    }

    @Test
    void segmentsKeepEmptyOnes() {
        assertEquals(List.of("a", "", "b", ""), KeyCodec.segments("a//b/"));
        assertEquals(List.of(""), KeyCodec.segments(""));
        assertEquals(List.of("", "x"), KeyCodec.segments("/x"));
    }

    @Test
    void keysThatCollideNaivelyGetDistinctLocations() {
        List<String> keys = List.of("x", "x/", "x//", "x/./y", "x/y", "x//y", "x/../y", "y", "X", "Note", "note",
                Normalizer.normalize("café", Normalizer.Form.NFC), Normalizer.normalize("café", Normalizer.Form.NFD), "/x", "//x");
        Map<String, String> seen = new HashMap<>();
        for (String k : keys) {
            KeyCodec.Location loc = KeyCodec.locate(k);
            String path = (String.join("/", loc.directories()) + "/" + loc.name()).toLowerCase(Locale.ROOT);
            String before = seen.putIfAbsent(path, k);
            assertNull(before, () -> "keys " + before + " and " + k + " share " + path);
        }
    }

    @Test
    void longSegmentsAndLongPathsOverflow() {
        String fits = "a".repeat(KeyCodec.MAX_NAME);
        KeyCodec.Location ok = KeyCodec.locate("p/" + fits);
        assertFalse(ok.overflow());
        assertEquals(List.of("p"), ok.directories());

        KeyCodec.Location longSegment = KeyCodec.locate("p/" + "a".repeat(KeyCodec.MAX_NAME + 1) + "/q");
        assertTrue(longSegment.overflow());
        assertEquals(List.of("p"), longSegment.directories());
        assertTrue(longSegment.name().matches("%h[0-9a-f]{32}"));

        // Upper case triples in length: 100 upper-case letters need 300 bytes.
        KeyCodec.Location escaped = KeyCodec.locate("p/" + "A".repeat(100));
        assertTrue(escaped.overflow());

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 10; i++) deep.append("d".repeat(99)).append('/');
        deep.append("leaf");
        KeyCodec.Location d = KeyCodec.locate(deep.toString());
        assertTrue(d.overflow());
        int length = String.join("/", d.directories()).length();
        assertTrue(length <= KeyCodec.MAX_PATH, "directories within the budget: " + length);
        assertEquals(6, d.directories().size(), "as deep as fits: 6 x 100 bytes");
    }

    @Test
    void whetherAPrefixFitsDependsOnlyOnThePrefix() {
        // The listing walk relies on it: under one directory, a first segment is either a real directory or overflow, never both.
        Random rnd = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            String base = randomKey(rnd);
            KeyCodec.Location a = KeyCodec.locate(base + "/x");
            KeyCodec.Location b = KeyCodec.locate(base + "/" + randomSegment(rnd) + "/y");
            int common = KeyCodec.segments(base).size();
            boolean aFits = a.directories().size() >= common;
            boolean bFits = b.directories().size() >= common;
            assertEquals(aFits, bFits, base);
        }
    }

    @Test
    void maxLengthKeyFitsUnderPathMaxWithTheLongestRoot() {
        // root + "/" + bucket(63) + "/" + directories + "/" + overflow name + "/" + longest internal file name
        int worst = LocalObjectStore.MAX_ROOT + 1 + 63 + 1 + KeyCodec.MAX_PATH + 1 + 34 + 1 + ".meta-00000000-0000-0000-0000-000000000000.tmp".length();
        assertTrue(worst < 1024, "worst-case path " + worst + " bytes");
    }

    private static void assertSafe(String name) {
        assertFalse(name.isEmpty());
        assertFalse(name.startsWith("."), name);
        assertFalse(name.equals("..") || name.equals("."), name);
        assertTrue(name.length() <= 255 * 3 * 4, name);
        for (char c : name.toCharArray()) assertTrue(c > 0x20 && c < 0x7f && c != '/', () -> "unsafe char in " + name);
        assertEquals(name, Normalizer.normalize(name, Normalizer.Form.NFC));
    }

    private static String randomSegment(Random rnd) {
        int n = rnd.nextInt(6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int kind = rnd.nextInt(5);
            int cp = switch (kind) {
                case 0 -> 'a' + rnd.nextInt(26);
                case 1 -> 'A' + rnd.nextInt(26);
                case 2 -> 0x20 + rnd.nextInt(0x5f);
                case 3 -> 0x80 + rnd.nextInt(0x800);
                default -> 0x10000 + rnd.nextInt(0x1000);
            };
            if (cp == '/') cp = '.';
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static String randomKey(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int segs = 1 + rnd.nextInt(12);
        for (int i = 0; i < segs; i++) {
            if (i > 0) sb.append('/');
            String s = randomSegment(rnd);
            if (rnd.nextInt(10) == 0) s = s.repeat(40);
            sb.append(s);
        }
        String k = sb.toString();
        return k.getBytes(StandardCharsets.UTF_8).length > 1000 ? k.substring(0, 300) : k;
    }
}
