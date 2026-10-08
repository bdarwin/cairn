package io.github.bdarwin.cairn.examples.probes;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Probe 2: what do S3 clients really send? A recording stub that answers just enough of the S3 API
 * (in memory, no signature checks) for a client to complete an upload and a read, and logs every
 * request: request line, all headers, and the body's framing. For {@code aws-chunked} bodies the
 * framing lines (chunk headers, trailers) are printed as sent, with chunk data replaced by its size.
 *
 * <p>Run: {@code mvn -q compile exec:java -Dexec.mainClass=io.github.bdarwin.cairn.examples.probes.ProbeCaptureServer -Dexec.args="9000 capture.log"}
 * then point a client at {@code http://localhost:9000}. Not a server to use: throwaway.
 */
public final class ProbeCaptureServer {

    private static final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private static final AtomicInteger seq = new AtomicInteger();
    private static PrintStream log;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        log = args.length > 1 ? new PrintStream(new java.io.FileOutputStream(args[1], true), true, StandardCharsets.UTF_8) : System.out;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ProbeCaptureServer::handle);
        server.start();
        System.out.println("capture server on " + port);
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            int n = seq.incrementAndGet();
            byte[] raw = ex.getRequestBody().readAllBytes();
            Headers h = ex.getRequestHeaders();
            String sha = h.getFirst("x-amz-content-sha256");
            boolean chunked = sha != null && sha.startsWith("STREAMING")
                    || (h.getFirst("Content-Encoding") != null && h.getFirst("Content-Encoding").contains("aws-chunked"));
            StringBuilder sb = new StringBuilder();
            sb.append("=== #").append(n).append(' ').append(ex.getRequestMethod()).append(' ')
                    .append(ex.getRequestURI().getRawPath());
            if (ex.getRequestURI().getRawQuery() != null) sb.append('?').append(ex.getRequestURI().getRawQuery());
            sb.append('\n');
            Map<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            sorted.putAll(h);
            sorted.forEach((k, v) -> v.forEach(x -> sb.append("  ").append(k).append(": ").append(x).append('\n')));
            byte[] body = raw;
            if (raw.length > 0) {
                sb.append("  -- body: ").append(raw.length).append(" bytes on the wire\n");
                if (chunked) {
                    ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                    sb.append(describeChunked(raw, decoded));
                    body = decoded.toByteArray();
                    sb.append("  -- decoded payload: ").append(body.length).append(" bytes\n");
                } else if (!ex.getRequestMethod().equals("PUT")) {
                    sb.append("  ").append(new String(raw, 0, Math.min(raw.length, 600), StandardCharsets.UTF_8).replace("\n", "\n  ")).append('\n');
                }
            }
            log.print(sb);
            respond(ex, body);
        } catch (Exception e) {
            e.printStackTrace(log);
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    /** Prints the framing of an aws-chunked body, collecting the data into {@code out}. */
    static String describeChunked(byte[] b, ByteArrayOutputStream out) {
        StringBuilder sb = new StringBuilder();
        int p = 0;
        List<String> lines = new ArrayList<>();
        while (p < b.length) {
            int eol = indexOfCrlf(b, p);
            if (eol < 0) {
                lines.add("<unterminated: " + (b.length - p) + " bytes>");
                break;
            }
            String header = new String(b, p, eol - p, StandardCharsets.US_ASCII);
            p = eol + 2;
            String sizeHex = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            int size = Integer.parseInt(sizeHex.trim(), 16);
            lines.add("chunk header: " + header + "   (" + size + " bytes)");
            out.write(b, p, size);
            p += size;
            if (size == 0) {
                // Trailers until an empty line.
                while (p < b.length) {
                    int e = indexOfCrlf(b, p);
                    if (e < 0) {
                        lines.add("trailer bytes without CRLF: " + new String(b, p, b.length - p, StandardCharsets.US_ASCII));
                        p = b.length;
                        break;
                    }
                    String t = new String(b, p, e - p, StandardCharsets.US_ASCII);
                    p = e + 2;
                    lines.add(t.isEmpty() ? "<empty line: end of trailers>" : "trailer: " + t);
                    if (t.isEmpty()) break;
                }
                if (p != b.length) lines.add("<" + (b.length - p) + " bytes after the trailers>");
                break;
            }
            if (b[p] != '\r' || b[p + 1] != '\n') lines.add("<chunk data not followed by CRLF>");
            p += 2;
        }
        int shown = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.size() > 8 && i >= 3 && i < lines.size() - 4) {
                if (shown++ == 0) sb.append("    ... ").append(lines.size() - 7).append(" more chunk headers ...\n");
                continue;
            }
            sb.append("    ").append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    static int indexOfCrlf(byte[] b, int from) {
        for (int i = from; i + 1 < b.length; i++) if (b[i] == '\r' && b[i + 1] == '\n') return i;
        return -1;
    }

    static void respond(HttpExchange ex, byte[] body) throws Exception {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String[] parts = path.substring(1).split("/", 2);
        String bucket = parts[0];
        String key = parts.length > 1 ? parts[1] : "";
        Headers out = ex.getResponseHeaders();
        out.set("x-amz-request-id", "probe");

        if (bucket.isEmpty()) {
            xml(ex, 200, "<ListAllMyBucketsResult><Owner><ID>probe</ID></Owner><Buckets><Bucket><Name>probe</Name>"
                    + "<CreationDate>2026-01-01T00:00:00.000Z</CreationDate></Bucket></Buckets></ListAllMyBucketsResult>");
            return;
        }
        if (key.isEmpty()) {
            switch (method) {
                case "GET" -> {
                    if (query.contains("location")) {
                        xml(ex, 200, "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"></LocationConstraint>");
                    } else {
                        StringBuilder sb = new StringBuilder("<ListBucketResult><Name>" + bucket + "</Name><Prefix></Prefix><KeyCount>");
                        List<String> keys = objects.keySet().stream().filter(k -> k.startsWith(bucket + "/")).sorted().toList();
                        sb.append(keys.size()).append("</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>");
                        for (String k : keys) {
                            sb.append("<Contents><Key>").append(k.substring(bucket.length() + 1)).append("</Key><LastModified>2026-01-01T00:00:00.000Z</LastModified><ETag>\"")
                                    .append(md5(objects.get(k))).append("\"</ETag><Size>").append(objects.get(k).length).append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
                        }
                        xml(ex, 200, sb.append("</ListBucketResult>").toString());
                    }
                }
                default -> ex.sendResponseHeaders(200, -1);
            }
            return;
        }
        String id = bucket + "/" + key;
        switch (method) {
            case "PUT" -> {
                if (query.contains("uploadId")) {
                    String part = id + "#part" + param(query, "partNumber");
                    objects.put(part, body);
                } else {
                    objects.put(id, body);
                }
                out.set("ETag", "\"" + md5(body) + "\"");
                ex.sendResponseHeaders(200, -1);
            }
            case "POST" -> {
                if (query.contains("uploads")) {
                    xml(ex, 200, "<InitiateMultipartUploadResult><Bucket>" + bucket + "</Bucket><Key>" + key
                            + "</Key><UploadId>probe-upload</UploadId></InitiateMultipartUploadResult>");
                } else {
                    ByteArrayOutputStream all = new ByteArrayOutputStream();
                    for (int i = 1; objects.containsKey(id + "#part" + i); i++) all.write(objects.remove(id + "#part" + i));
                    objects.put(id, all.toByteArray());
                    xml(ex, 200, "<CompleteMultipartUploadResult><Bucket>" + bucket + "</Bucket><Key>" + key
                            + "</Key><ETag>\"" + md5(all.toByteArray()) + "-1\"</ETag></CompleteMultipartUploadResult>");
                }
            }
            case "GET", "HEAD" -> {
                byte[] data = objects.get(id);
                if (data == null) {
                    if (method.equals("HEAD")) ex.sendResponseHeaders(404, -1);
                    else xml(ex, 404, "<Error><Code>NoSuchKey</Code><Message>no such key</Message></Error>");
                    return;
                }
                out.set("ETag", "\"" + md5(data) + "\"");
                out.set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
                out.set("Content-Type", "application/octet-stream");
                out.set("Accept-Ranges", "bytes");
                String range = ex.getRequestHeaders().getFirst("Range");
                int from = 0, to = data.length - 1, status = 200;
                if (range != null && range.startsWith("bytes=")) {
                    String[] r = range.substring(6).split("-", -1);
                    from = Integer.parseInt(r[0]);
                    if (!r[1].isEmpty()) to = Math.min(to, Integer.parseInt(r[1]));
                    status = 206;
                    out.set("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
                }
                int len = to - from + 1;
                if (method.equals("HEAD")) {
                    out.set("Content-Length", Integer.toString(len));
                    ex.sendResponseHeaders(status, -1);
                } else {
                    ex.sendResponseHeaders(status, len);
                    ex.getResponseBody().write(data, from, len);
                }
            }
            case "DELETE" -> {
                objects.remove(id);
                ex.sendResponseHeaders(204, -1);
            }
            default -> ex.sendResponseHeaders(405, -1);
        }
    }

    static String param(String query, String name) {
        for (String p : query.split("&")) if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }

    static void xml(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/xml");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    static String md5(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
    }
}
