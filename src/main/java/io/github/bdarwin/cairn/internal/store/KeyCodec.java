package io.github.bdarwin.cairn.internal.store;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Maps S3 keys to directory names and back, so that every legal key round-trips on a filesystem
 * that folds case and Unicode normalisation (APFS by default), cannot hold {@code .}, {@code ..} or
 * empty names, and limits names to 255 characters and paths to 1024 bytes. The reasons and the
 * measurements behind them are in {@code docs/probes.md}, probe 5.
 *
 * <p>Each segment of the key between {@code /} separators becomes one name:
 * <ul>
 *   <li>{@code a-z}, {@code 0-9}, {@code -}, {@code _}, {@code ~}, and {@code .} anywhere but first,
 *       are written as they are;</li>
 *   <li>every other byte of the segment's UTF-8 is written {@code %XX}, upper-case hex;</li>
 *   <li>an empty segment is written as a lone {@code %}.</li>
 * </ul>
 * Names are pure ASCII, never start with {@code .}, and two different segments never produce names
 * that are equal ignoring case. Names starting with {@code .} are left for the store's own files.
 *
 * <p>A key whose path does not fit (a segment over {@link #MAX_NAME} or a path over
 * {@link #MAX_PATH}) is placed in an overflow entry, {@code %h} plus 32 hex digits of its SHA-256,
 * inside the deepest directory that fits. The budget is fixed, so the layout does not depend on
 * where the data directory is.
 */
public final class KeyCodec {

    /** Longest encoded segment given its own directory. */
    public static final int MAX_NAME = 240;
    /** Longest encoded path, below the bucket, given real directories. */
    public static final int MAX_PATH = 640;
    /** Prefix of an overflow entry's name. {@code h} is not a hex digit, so no escape produces it. */
    public static final String OVERFLOW_PREFIX = "%h";

    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    private KeyCodec() {
    }

    /** Where a key lives: the real directories leading to it, and its own name in the last one. */
    public record Location(List<String> directories, String name, boolean overflow) {
    }

    /** Encodes one key segment (the bytes between separators) as a directory name. */
    public static String encodeSegment(String segment) {
        if (segment.isEmpty()) return "%";
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (isLiteral(b, i == 0)) {
                sb.append((char) b);
            } else {
                sb.append('%').append(HEX_UPPER.toHexDigits((byte) b));
            }
        }
        return sb.toString();
    }

    private static boolean isLiteral(int b, boolean first) {
        return (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '~'
                || (b == '.' && !first);
    }

    /**
     * Decodes a directory name written by {@link #encodeSegment}. Throws if the name is not one the
     * encoder could have written: names are canonical, so each segment has exactly one name.
     */
    public static String decodeSegment(String name) {
        if (name.equals("%")) return "";
        if (name.isEmpty() || name.startsWith(OVERFLOW_PREFIX)) throw new IllegalArgumentException("not a segment name: " + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '%') {
                if (i + 2 >= name.length()) throw new IllegalArgumentException("truncated escape: " + name);
                String hex = name.substring(i + 1, i + 3);
                if (!isUpperHex(hex.charAt(0)) || !isUpperHex(hex.charAt(1))) throw new IllegalArgumentException("bad escape: " + name);
                int b = HexFormat.fromHexDigits(hex);
                if (isLiteral(b, out.size() == 0)) throw new IllegalArgumentException("non-canonical escape: " + name);
                out.write(b);
                i += 2;
            } else if (c < 0x80 && isLiteral(c, out.size() == 0)) {
                out.write(c);
            } else {
                throw new IllegalArgumentException("not a segment name: " + name);
            }
        }
        byte[] bytes = out.toByteArray();
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(s.getBytes(StandardCharsets.UTF_8), bytes)) throw new IllegalArgumentException("not UTF-8: " + name);
        return s;
    }

    private static boolean isUpperHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    /** Splits a key on {@code /}, keeping empty segments: {@code "a//b/"} is {@code [a, "", b, ""]}. */
    public static List<String> segments(String key) {
        List<String> out = new ArrayList<>();
        int from = 0;
        for (int i = key.indexOf('/'); i >= 0; i = key.indexOf('/', from)) {
            out.add(key.substring(from, i));
            from = i + 1;
        }
        out.add(key.substring(from));
        return out;
    }

    /** Where {@code key} is stored, below its bucket's directory. */
    public static Location locate(String key) {
        List<String> segments = segments(key);
        List<String> dirs = new ArrayList<>(segments.size());
        int pathLength = 0;
        for (int i = 0; i < segments.size(); i++) {
            String name = encodeSegment(segments.get(i));
            int next = pathLength + (pathLength == 0 ? 0 : 1) + name.length();
            if (name.length() > MAX_NAME || next > MAX_PATH) {
                return new Location(List.copyOf(dirs), overflowName(key), true);
            }
            pathLength = next;
            if (i == segments.size() - 1) return new Location(List.copyOf(dirs), name, false);
            dirs.add(name);
        }
        throw new AssertionError("unreachable");
    }

    /** The overflow entry name for {@code key}: {@code %h} and 32 lower-case hex digits of its SHA-256. */
    public static String overflowName(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return OVERFLOW_PREFIX + HexFormat.of().formatHex(h, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isOverflowName(String name) {
        return name.startsWith(OVERFLOW_PREFIX);
    }
}
