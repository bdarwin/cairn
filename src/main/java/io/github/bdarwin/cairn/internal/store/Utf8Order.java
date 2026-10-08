package io.github.bdarwin.cairn.internal.store;

/**
 * S3's key order: by UTF-8 bytes, which is code point order. Java's {@link String#compareTo} compares
 * UTF-16 units instead, and disagrees for characters above U+FFFF against those in U+E000-U+FFFF.
 */
public final class Utf8Order {

    private Utf8Order() {
    }

    public static int compare(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char x = a.charAt(i), y = b.charAt(i);
            if (x == y) continue;
            if (Character.isSurrogate(x) || Character.isSurrogate(y)) {
                return Integer.compare(a.codePointAt(i), b.codePointAt(i));
            }
            return Character.compare(x, y);
        }
        return Integer.compare(a.length(), b.length());
    }
}
