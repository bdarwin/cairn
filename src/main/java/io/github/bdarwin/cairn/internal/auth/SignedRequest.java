package io.github.bdarwin.cairn.internal.auth;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What signature verification needs from a request.
 *
 * @param method      the HTTP method
 * @param path        the decoded path, e.g. {@code /bucket/a b}
 * @param query       the decoded query parameters, in order
 * @param headers     header values by lower-case name
 */
public record SignedRequest(String method, String path, List<Map.Entry<String, String>> query, Map<String, List<String>> headers) {

    public String header(String lowerName) {
        List<String> v = headers.get(lowerName);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    public String queryParam(String name) {
        for (Map.Entry<String, String> e : query) if (e.getKey().equals(name)) return e.getValue();
        return null;
    }

    /** Lower-cases header names, for building one from a map with any case. */
    public static Map<String, List<String>> lowerCase(Map<String, List<String>> headers) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        headers.forEach((k, v) -> out.computeIfAbsent(k.toLowerCase(Locale.ROOT), x -> new java.util.ArrayList<>()).addAll(v));
        return out;
    }
}
