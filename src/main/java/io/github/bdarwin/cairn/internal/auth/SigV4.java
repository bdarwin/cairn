package io.github.bdarwin.cairn.internal.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** The pieces of AWS Signature Version 4 that both the request and the chunk signatures use. */
public final class SigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final HexFormat HEX = HexFormat.of();

    private SigV4() {
    }

    public static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256().digest(data));
    }

    public static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    /** The key derived from the secret for one day, region and service. */
    public static byte[] signingKey(String secret, String date, String region, String service) {
        byte[] k = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, service);
        return hmac(k, "aws4_request");
    }

    /**
     * SigV4's URI encoding: every byte except {@code A-Z a-z 0-9 - _ . ~} as {@code %XX}, upper-case;
     * {@code /} kept when {@code encodeSlash} is false (for paths).
     */
    public static String uriEncode(String s, boolean encodeSlash) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (byte value : bytes) {
            int b = value & 0xff;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~' || (b == '/' && !encodeSlash)) {
                sb.append((char) b);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** The canonical query string from decoded parameters, leaving out {@code exclude} (may be null). */
    public static String canonicalQuery(List<Map.Entry<String, String>> params, String exclude) {
        List<String[]> encoded = new ArrayList<>();
        for (Map.Entry<String, String> p : params) {
            if (p.getKey().equals(exclude)) continue;
            encoded.add(new String[] {uriEncode(p.getKey(), true), uriEncode(p.getValue(), true)});
        }
        encoded.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder sb = new StringBuilder();
        for (String[] e : encoded) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(e[0]).append('=').append(e[1]);
        }
        return sb.toString();
    }

    /** A header value as SigV4 canonicalises it: trimmed, runs of spaces collapsed to one. */
    public static String canonicalHeaderValue(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        boolean space = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '\t') {
                space = true;
            } else {
                if (space && !sb.isEmpty()) sb.append(' ');
                space = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String stringToSign(String amzDate, String scope, String canonicalRequest) {
        return ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    }

    /** Compares two hex signatures in constant time. */
    public static boolean sameSignature(String expected, String given) {
        if (given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), given.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
