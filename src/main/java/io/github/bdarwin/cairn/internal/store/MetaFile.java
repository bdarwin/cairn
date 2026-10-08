package io.github.bdarwin.cairn.internal.store;

import io.github.bdarwin.cairn.internal.json.Json;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code .meta} file of an object: one line of JSON, a newline, then the object's bytes when they
 * are small enough to be stored inline. The JSON carries {@code "format"}, so a reader that did not
 * write the file, another version or another node, knows what it holds. {@code head -1 .meta} shows it.
 *
 * <pre>
 * {"format":1,"key":"a/b","size":5,"etag":"5d41...","lastModified":1791462000000,
 *  "contentType":"text/plain","meta":{},"headers":{},"checksums":{"CRC32":"NhCmhg=="},
 *  "parts":[{"number":1,"size":5,"etag":"5d41...","crc32c":"mnG7TA=="}],"data":{"inline":5}}
 * </pre>
 *
 * {@code "data"} is either {@code {"inline": n}} or {@code {"file": ".data-<uuid>"}}, naming a file in
 * the same directory.
 */
final class MetaFile {

    static final int FORMAT = 1;

    /** A decoded {@code .meta}: the object's info, and where its bytes are. */
    record Contents(ObjectInfo info, String dataFile, byte[] inline) {
    }

    private MetaFile() {
    }

    static byte[] encode(ObjectInfo info, String dataFile, byte[] inline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("key", info.key());
        m.put("size", info.size());
        m.put("etag", info.etag());
        m.put("lastModified", info.lastModified().toEpochMilli());
        m.put("contentType", info.contentType());
        m.put("meta", info.userMetadata());
        m.put("headers", info.headers());
        m.put("checksums", info.checksums());
        List<Object> parts = new ArrayList<>();
        for (PartInfo p : info.parts()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("number", p.number());
            pm.put("size", p.size());
            pm.put("etag", p.etag());
            pm.put("crc32c", p.crc32c());
            parts.add(pm);
        }
        m.put("parts", parts);
        m.put("data", inline != null ? Map.of("inline", inline.length) : Map.of("file", dataFile));
        byte[] head = (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
        if (inline == null) return head;
        byte[] all = Arrays.copyOf(head, head.length + inline.length);
        System.arraycopy(inline, 0, all, head.length, inline.length);
        return all;
    }

    @SuppressWarnings("unchecked")
    static Contents decode(byte[] bytes) {
        int nl = 0;
        while (nl < bytes.length && bytes[nl] != '\n') nl++;
        if (nl == bytes.length) throw new IllegalArgumentException("metadata without a header line");
        Map<String, Object> m = Json.parseObject(new String(bytes, 0, nl, StandardCharsets.UTF_8));
        Object format = m.get("format");
        if (!(format instanceof Long f) || f != FORMAT) throw new IllegalArgumentException("unknown metadata format " + format);
        List<PartInfo> parts = new ArrayList<>();
        for (Object o : (List<Object>) m.get("parts")) {
            Map<String, Object> p = (Map<String, Object>) o;
            parts.add(new PartInfo(((Long) p.get("number")).intValue(), (Long) p.get("size"), (String) p.get("etag"), (String) p.get("crc32c")));
        }
        ObjectInfo info = new ObjectInfo((String) m.get("key"), (Long) m.get("size"), (String) m.get("etag"),
                Instant.ofEpochMilli((Long) m.get("lastModified")), (String) m.get("contentType"),
                strings(m.get("meta")), strings(m.get("headers")), strings(m.get("checksums")), List.copyOf(parts));
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        if (data.containsKey("inline")) {
            int n = ((Long) data.get("inline")).intValue();
            if (bytes.length - nl - 1 != n) throw new IllegalArgumentException("inline data is " + (bytes.length - nl - 1) + " bytes, header says " + n);
            return new Contents(info, null, Arrays.copyOfRange(bytes, nl + 1, bytes.length));
        }
        return new Contents(info, (String) data.get("file"), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strings(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o != null) ((Map<String, Object>) o).forEach((k, v) -> out.put(k, (String) v));
        return out;
    }
}
