package io.github.bdarwin.cairn.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strict percent-decoding of request paths and query strings. */
public final class Uris {

    private Uris() {
    }

    /** Decodes {@code %XX} escapes; {@code +} stays {@code +}. Rejects bad escapes and invalid UTF-8. */
    public static String decode(String raw) {
        if (raw.indexOf('%') < 0) return raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) throw invalidUri();
                int hi = Character.digit(raw.charAt(i + 1), 16), lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw invalidUri();
                out.write(hi << 4 | lo);
                i += 2;
            } else if (c <= 0xff) {
                // The JDK server reads the request line as ISO-8859-1: one char per byte on the wire.
                out.write(c);
            } else {
                throw invalidUri();
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw invalidUri();
        }
    }

    /** The query's parameters in order, decoded; {@code ?uploads} gives {@code uploads=""}. */
    public static List<Map.Entry<String, String>> parseQuery(String rawQuery) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.add(new AbstractMap.SimpleImmutableEntry<>(decode(k), decode(v)));
        }
        return out;
    }

    private static S3Exception invalidUri() {
        return new S3Exception(400, "InvalidURI", "Couldn't parse the specified URI.");
    }
}
