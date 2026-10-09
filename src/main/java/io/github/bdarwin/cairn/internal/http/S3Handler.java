package io.github.bdarwin.cairn.internal.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.bdarwin.cairn.internal.S3Exception;
import io.github.bdarwin.cairn.internal.Uris;
import io.github.bdarwin.cairn.internal.auth.AuthContext;
import io.github.bdarwin.cairn.internal.auth.Authenticator;
import io.github.bdarwin.cairn.internal.auth.AwsChunkedInputStream;
import io.github.bdarwin.cairn.internal.auth.SignedRequest;
import io.github.bdarwin.cairn.internal.checksum.ChecksumAlgorithm;
import io.github.bdarwin.cairn.internal.store.BucketInfo;
import io.github.bdarwin.cairn.internal.store.CompletedPart;
import io.github.bdarwin.cairn.internal.store.PartInfo;
import io.github.bdarwin.cairn.internal.store.Upload;
import io.github.bdarwin.cairn.internal.store.UploadedPart;
import io.github.bdarwin.cairn.internal.store.ListPage;
import io.github.bdarwin.cairn.internal.store.ListQuery;
import io.github.bdarwin.cairn.internal.store.NewObject;
import io.github.bdarwin.cairn.internal.store.ObjectInfo;
import io.github.bdarwin.cairn.internal.store.ObjectStore;
import io.github.bdarwin.cairn.internal.store.StoredObject;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Routes S3 requests (path-style: {@code /bucket/key}) to the store and writes S3's responses. */
public final class S3Handler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(S3Handler.class.getName());
    private static final long MAX_PUT_SIZE = 5L << 30;
    private static final int MAX_KEY_BYTES = 1024;
    private static final int MAX_USER_METADATA = 2048;
    private static final int MAX_PARTS = 10_000;
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern IP_ADDRESS = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");
    /** Headers stored with an object and returned on GET and HEAD. */
    private static final List<String> STORED_HEADERS = List.of("Cache-Control", "Content-Disposition", "Content-Encoding", "Content-Language", "Expires");
    /** Query parameters that name a sub-resource; a request with one cairn does not serve is refused, not misread. */
    private static final Set<String> SUBRESOURCES = Set.of("accelerate", "acl", "analytics", "attributes", "cors", "delete", "encryption",
            "intelligent-tiering", "inventory", "legal-hold", "lifecycle", "location", "logging", "metrics", "notification", "object-lock",
            "ownershipControls", "partNumber", "policy", "policyStatus", "publicAccessBlock", "replication", "requestPayment", "restore",
            "retention", "select", "tagging", "torrent", "uploadId", "uploads", "versionId", "versioning", "versions", "website");

    private final ObjectStore store;
    private final Authenticator authenticator;
    private final String region;

    public S3Handler(ObjectStore store, Authenticator authenticator, String region) {
        this.store = store;
        this.authenticator = authenticator;
        this.region = region;
    }

    @Override
    public void handle(HttpExchange ex) {
        String requestId = Long.toHexString(ThreadLocalRandom.current().nextLong()).toUpperCase(Locale.ROOT);
        ex.getResponseHeaders().set("x-amz-request-id", requestId);
        ex.getResponseHeaders().set("Server", "cairn");
        Request r = null;
        try {
            r = Request.parse(ex);
            AuthContext auth = authenticator.authenticate(r.signed());
            route(r, auth, ex);
        } catch (S3Exception e) {
            sendError(ex, r, e, requestId);
        } catch (IOException e) {
            // Usually the client went away mid-request; if nothing was sent yet, say so.
            LOG.log(Level.FINE, "request failed", e);
            sendError(ex, r, S3Exception.internal("We encountered an internal error. Please try again."), requestId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "request failed", e);
            sendError(ex, r, S3Exception.internal("We encountered an internal error. Please try again."), requestId);
        } finally {
            ex.close();
        }
    }

    private void route(Request r, AuthContext auth, HttpExchange ex) throws IOException {
        if (r.bucket == null) {
            if (r.method.equals("GET")) {
                listBuckets(ex);
                return;
            }
            throw methodNotAllowed();
        }
        if (r.key == null) {
            routeBucket(r, ex);
        } else {
            routeObject(r, auth, ex);
        }
    }

    private void routeBucket(Request r, HttpExchange ex) throws IOException {
        switch (r.method) {
            case "PUT" -> {
                refuseSubresources(r);
                if (!validBucketName(r.bucket)) throw new S3Exception(400, "InvalidBucketName", "The specified bucket is not valid.");
                store.createBucket(r.bucket);
                ex.getResponseHeaders().set("Location", "/" + r.bucket);
                send(ex, 200, null);
            }
            case "HEAD" -> {
                if (!store.bucketExists(r.bucket)) throw S3Exception.noSuchBucket();
                ex.getResponseHeaders().set("x-amz-bucket-region", region);
                send(ex, 200, null);
            }
            case "DELETE" -> {
                refuseSubresources(r);
                store.deleteBucket(r.bucket);
                send(ex, 204, null);
            }
            case "GET" -> {
                if (r.has("location")) {
                    requireBucket(r.bucket);
                    String constraint = region.equals("us-east-1") ? "" : Xml.escape(region);
                    sendXml(ex, 200, "<LocationConstraint xmlns=\"" + Xml.NS + "\">" + constraint + "</LocationConstraint>");
                } else if (r.has("object-lock")) {
                    requireBucket(r.bucket);
                    throw new S3Exception(404, "ObjectLockConfigurationNotFoundError", "Object Lock configuration does not exist for this bucket");
                } else if (r.has("versioning")) {
                    requireBucket(r.bucket);
                    sendXml(ex, 200, "<VersioningConfiguration xmlns=\"" + Xml.NS + "\"/>");
                } else if (r.has("uploads")) {
                    refuseSubresources(r, "uploads");
                    listUploads(r, ex);
                } else {
                    refuseSubresources(r);
                    if ("2".equals(r.param("list-type"))) listObjectsV2(r, ex);
                    else listObjectsV1(r, ex);
                }
            }
            default -> throw methodNotAllowed();
        }
    }

    private void routeObject(Request r, AuthContext auth, HttpExchange ex) throws IOException {
        if (r.key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new S3Exception(400, "KeyTooLongError", "Your key is too long");
        }
        switch (r.method) {
            case "PUT" -> {
                if (r.has("uploadId") || r.has("partNumber")) {
                    refuseSubresources(r, "uploadId", "partNumber");
                    if (r.header("x-amz-copy-source") != null) throw S3Exception.notImplemented("UploadPartCopy");
                    uploadPart(r, auth, ex);
                    return;
                }
                refuseSubresources(r);
                if (r.header("x-amz-copy-source") != null) throw S3Exception.notImplemented("x-amz-copy-source");
                putObject(r, auth, ex);
            }
            case "GET", "HEAD" -> {
                if (r.method.equals("GET") && r.has("uploadId")) {
                    refuseSubresources(r, "uploadId");
                    listParts(r, ex);
                    return;
                }
                refuseSubresources(r);
                getObject(r, ex, r.method.equals("HEAD"));
            }
            case "DELETE" -> {
                if (r.has("uploadId")) {
                    refuseSubresources(r, "uploadId");
                    store.abortUpload(r.bucket, r.key, r.param("uploadId"));
                    send(ex, 204, null);
                    return;
                }
                refuseSubresources(r);
                store.delete(r.bucket, r.key);
                send(ex, 204, null);
            }
            case "POST" -> {
                if (r.has("uploads")) {
                    refuseSubresources(r, "uploads");
                    createUpload(r, ex);
                } else if (r.has("uploadId")) {
                    refuseSubresources(r, "uploadId");
                    completeUpload(r, ex);
                } else {
                    refuseSubresources(r);
                    throw methodNotAllowed();
                }
            }
            default -> throw methodNotAllowed();
        }
    }

    // ---- objects ----

    private void putObject(Request r, AuthContext auth, HttpExchange ex) throws IOException {
        IncomingBody in = IncomingBody.of(r, auth, ex, null);
        NewObject attrs = new NewObject(contentType(r), userMetadata(r), storedHeaders(r), Map.of());
        ObjectInfo info = store.put(r.bucket, r.key, attrs, in.body, in.check(attrs.checksums()));
        Headers h = ex.getResponseHeaders();
        h.set("ETag", quote(info.etag()));
        info.checksums().forEach((alg, v) -> h.set(ChecksumAlgorithm.valueOf(alg).headerName(), v));
        send(ex, 200, null);
    }

    /**
     * A request body as it arrives: de-framed if {@code aws-chunked}, with the digests needed to check
     * it, and the checks to run once it has all been read (length, signed SHA-256, Content-MD5,
     * x-amz-checksum-*).
     */
    private static final class IncomingBody {
        final DigestingInputStream body;
        final AwsChunkedInputStream decoder;
        final ExpectedChecksum expected;
        final long declared;
        final byte[] contentMd5;
        final AuthContext auth;

        private IncomingBody(DigestingInputStream body, AwsChunkedInputStream decoder, ExpectedChecksum expected, long declared,
                             byte[] contentMd5, AuthContext auth) {
            this.body = body;
            this.decoder = decoder;
            this.expected = expected;
            this.declared = declared;
            this.contentMd5 = contentMd5;
            this.auth = auth;
        }

        /** @param computeIfAbsent an algorithm to compute when the request declares none (a multipart upload's), or null */
        static IncomingBody of(Request r, AuthContext auth, HttpExchange ex, ChecksumAlgorithm computeIfAbsent) {
            boolean chunked = AwsChunkedInputStream.isChunked(auth.payloadHash());
            String lengthHeader = chunked ? r.header("x-amz-decoded-content-length") : r.header("content-length");
            if (lengthHeader == null && r.header("transfer-encoding") == null) {
                throw new S3Exception(411, "MissingContentLength", "You must provide the Content-Length HTTP header.");
            }
            long declared = -1;
            if (lengthHeader != null) {
                try {
                    declared = Long.parseLong(lengthHeader.trim());
                } catch (NumberFormatException e) {
                    throw S3Exception.invalidArgument("Content-Length must be a number");
                }
                if (declared < 0) throw S3Exception.invalidArgument("Content-Length must not be negative");
                if (declared > MAX_PUT_SIZE) {
                    throw new S3Exception(400, "EntityTooLarge", "Your proposed upload exceeds the maximum allowed object size.");
                }
            }
            byte[] contentMd5 = null;
            if (r.header("content-md5") != null) {
                try {
                    contentMd5 = Base64.getDecoder().decode(r.header("content-md5").trim());
                } catch (IllegalArgumentException e) {
                    contentMd5 = new byte[0];
                }
                if (contentMd5.length != 16) throw new S3Exception(400, "InvalidDigest", "The Content-MD5 you specified was invalid.");
            }
            ExpectedChecksum expected = ExpectedChecksum.of(r);
            if (expected == null && computeIfAbsent != null) expected = new ExpectedChecksum(computeIfAbsent, null, false);
            InputStream raw = ex.getRequestBody();
            AwsChunkedInputStream decoder = chunked ? new AwsChunkedInputStream(raw, auth) : null;
            DigestingInputStream body = new DigestingInputStream(decoder != null ? decoder : raw,
                    expected == null ? null : expected.algorithm.newHasher(),
                    isHexSha256(auth.payloadHash()) ? io.github.bdarwin.cairn.internal.auth.SigV4.sha256() : null);
            return new IncomingBody(body, decoder, expected, declared, contentMd5, auth);
        }

        /** The checks to run before commit; the computed checksum goes into {@code checksums}. */
        ObjectStore.BeforeCommit check(Map<String, String> checksums) {
            return (size, md5) -> {
                if (declared >= 0 && size != declared) throw S3Exception.incompleteBody();
                if (body.sha256 != null && !HexFormat.of().formatHex(body.sha256.digest()).equalsIgnoreCase(auth.payloadHash())) {
                    throw new S3Exception(400, "XAmzContentSHA256Mismatch", "The provided 'x-amz-content-sha256' header does not match what was computed.");
                }
                if (contentMd5 != null && !MessageDigest.isEqual(contentMd5, md5)) {
                    throw S3Exception.badDigest("The Content-MD5 you specified did not match what we received.");
                }
                if (expected != null) {
                    String given = expected.value;
                    if (expected.inTrailer) {
                        given = decoder == null ? null : decoder.trailers().get(expected.algorithm.headerName());
                        if (given == null) throw S3Exception.invalidRequest("The " + expected.algorithm.headerName() + " trailer was declared but not sent.");
                    }
                    String computed = body.checksum.base64();
                    if (given != null && !computed.equals(given)) {
                        throw S3Exception.badDigest("The " + expected.algorithm.name() + " you specified did not match the calculated checksum.");
                    }
                    checksums.put(expected.algorithm.name(), computed);
                }
            };
        }
    }

    // ---- multipart uploads ----

    private void createUpload(Request r, HttpExchange ex) throws IOException {
        String alg = r.header("x-amz-checksum-algorithm");
        String type = r.header("x-amz-checksum-type");
        ChecksumAlgorithm algorithm = null;
        if (alg != null) {
            algorithm = ChecksumAlgorithm.parse(alg);
            if (algorithm == null) throw S3Exception.invalidRequest("Checksum algorithm provided is unsupported.");
        }
        if (type != null) {
            type = type.trim().toUpperCase(Locale.ROOT);
            if (!type.equals("FULL_OBJECT") && !type.equals("COMPOSITE")) throw S3Exception.invalidRequest("Value for x-amz-checksum-type header is invalid.");
            if (algorithm == null) throw S3Exception.invalidRequest("x-amz-checksum-type requires x-amz-checksum-algorithm.");
        }
        if (algorithm != null && type == null) type = algorithm == ChecksumAlgorithm.CRC64NVME ? "FULL_OBJECT" : "COMPOSITE";
        if ("FULL_OBJECT".equals(type) && (algorithm == ChecksumAlgorithm.SHA1 || algorithm == ChecksumAlgorithm.SHA256)) {
            throw S3Exception.invalidRequest("The FULL_OBJECT checksum type cannot be used with the " + algorithm + " checksum algorithm.");
        }
        if ("COMPOSITE".equals(type) && algorithm == ChecksumAlgorithm.CRC64NVME) {
            throw S3Exception.invalidRequest("The COMPOSITE checksum type cannot be used with the CRC64NVME checksum algorithm.");
        }
        NewObject attrs = new NewObject(contentType(r), userMetadata(r), storedHeaders(r), Map.of());
        Upload u = store.createUpload(r.bucket, r.key, attrs, algorithm == null ? null : algorithm.name(), type);
        if (algorithm != null) {
            ex.getResponseHeaders().set("x-amz-checksum-algorithm", algorithm.name());
            ex.getResponseHeaders().set("x-amz-checksum-type", type);
        }
        StringBuilder sb = new StringBuilder("<InitiateMultipartUploadResult xmlns=\"" + Xml.NS + "\">");
        Xml.element(sb, "Bucket", r.bucket);
        Xml.element(sb, "Key", r.key);
        Xml.element(sb, "UploadId", u.id());
        sb.append("</InitiateMultipartUploadResult>");
        sendXml(ex, 200, sb.toString());
    }

    private void uploadPart(Request r, AuthContext auth, HttpExchange ex) throws IOException {
        int number = partNumber(r.param("partNumber"));
        Upload u = store.upload(r.bucket, r.key, r.param("uploadId"));
        ChecksumAlgorithm uploadAlg = u.checksumAlgorithm() == null ? null : ChecksumAlgorithm.valueOf(u.checksumAlgorithm());
        IncomingBody in = IncomingBody.of(r, auth, ex, uploadAlg);
        if (uploadAlg != null && in.expected.algorithm != uploadAlg) {
            throw S3Exception.invalidRequest("Checksum Type mismatch occurred, expected checksum Type: " + uploadAlg.name().toLowerCase(Locale.ROOT)
                    + ", actual checksum Type: " + in.expected.algorithm.name().toLowerCase(Locale.ROOT));
        }
        Map<String, String> checksums = new LinkedHashMap<>();
        PartInfo part = store.putPart(r.bucket, r.key, u.id(), number, in.body, in.check(checksums), checksums);
        Headers h = ex.getResponseHeaders();
        h.set("ETag", quote(part.etag()));
        checksums.forEach((alg, v) -> h.set(ChecksumAlgorithm.valueOf(alg).headerName(), v));
        send(ex, 200, null);
    }

    private void completeUpload(Request r, HttpExchange ex) throws IOException {
        Upload u = store.upload(r.bucket, r.key, r.param("uploadId"));
        org.w3c.dom.Document doc = Xml.parse(ex.getRequestBody().readNBytes(4 << 20));
        List<CompletedPart> parts = new ArrayList<>();
        org.w3c.dom.NodeList list = doc.getDocumentElement().getElementsByTagNameNS("*", "Part");
        String checksumElement = u.checksumAlgorithm() == null ? null : "Checksum" + u.checksumAlgorithm();
        for (int i = 0; i < list.getLength(); i++) {
            org.w3c.dom.Element p = (org.w3c.dom.Element) list.item(i);
            String number = text(p, "PartNumber"), etag = text(p, "ETag");
            if (number == null || etag == null) throw new S3Exception(400, "MalformedXML", "The XML you provided was not well-formed or did not validate against our published schema.");
            etag = etag.trim();
            if (etag.startsWith("\"") && etag.endsWith("\"") && etag.length() >= 2) etag = etag.substring(1, etag.length() - 1);
            parts.add(new CompletedPart(partNumber(number.trim()), etag, checksumElement == null ? null : text(p, checksumElement)));
        }
        if (parts.size() > MAX_PARTS) throw S3Exception.invalidArgument("Part count must not exceed " + MAX_PARTS);
        ObjectInfo info = store.completeUpload(r.bucket, r.key, u.id(), parts);
        StringBuilder sb = new StringBuilder("<CompleteMultipartUploadResult xmlns=\"" + Xml.NS + "\">");
        Xml.element(sb, "Location", "/" + r.bucket + "/" + r.key);
        Xml.element(sb, "Bucket", r.bucket);
        Xml.element(sb, "Key", r.key);
        Xml.element(sb, "ETag", quote(info.etag()));
        info.checksums().forEach((alg, v) -> {
            Xml.element(sb, "Checksum" + alg, v);
            Xml.element(sb, "ChecksumType", v.contains("-") ? "COMPOSITE" : "FULL_OBJECT");
        });
        sb.append("</CompleteMultipartUploadResult>");
        sendXml(ex, 200, sb.toString());
    }

    private void listParts(Request r, HttpExchange ex) throws IOException {
        Upload u = store.upload(r.bucket, r.key, r.param("uploadId"));
        int marker = r.param("part-number-marker") == null ? 0 : nonNegative(r.param("part-number-marker"), "part-number-marker");
        int max = r.param("max-parts") == null ? 1000 : Math.min(1000, nonNegative(r.param("max-parts"), "max-parts"));
        List<UploadedPart> all = store.parts(r.bucket, r.key, u.id()).stream().filter(p -> p.part().number() > marker).toList();
        List<UploadedPart> page = all.subList(0, Math.min(max, all.size()));
        boolean truncated = all.size() > page.size();
        StringBuilder sb = new StringBuilder("<ListPartsResult xmlns=\"" + Xml.NS + "\">");
        Xml.element(sb, "Bucket", r.bucket);
        Xml.element(sb, "Key", r.key);
        Xml.element(sb, "UploadId", u.id());
        Xml.element(sb, "PartNumberMarker", marker);
        Xml.element(sb, "NextPartNumberMarker", page.isEmpty() ? marker : page.getLast().part().number());
        Xml.element(sb, "MaxParts", max);
        Xml.element(sb, "IsTruncated", truncated);
        if (u.checksumAlgorithm() != null) {
            Xml.element(sb, "ChecksumAlgorithm", u.checksumAlgorithm());
            Xml.element(sb, "ChecksumType", u.checksumType());
        }
        sb.append("<Initiator><ID>cairn</ID><DisplayName>cairn</DisplayName></Initiator><Owner><ID>cairn</ID><DisplayName>cairn</DisplayName></Owner>");
        Xml.element(sb, "StorageClass", "STANDARD");
        for (UploadedPart p : page) {
            sb.append("<Part>");
            Xml.element(sb, "PartNumber", p.part().number());
            Xml.element(sb, "LastModified", HttpDates.iso(p.lastModified()));
            Xml.element(sb, "ETag", quote(p.part().etag()));
            Xml.element(sb, "Size", p.part().size());
            if (p.part().checksum() != null) Xml.element(sb, "Checksum" + u.checksumAlgorithm(), p.part().checksum());
            sb.append("</Part>");
        }
        sb.append("</ListPartsResult>");
        sendXml(ex, 200, sb.toString());
    }

    private void listUploads(Request r, HttpExchange ex) throws IOException {
        String prefix = orEmpty(r.param("prefix"));
        String delimiter = emptyToNull(r.param("delimiter"));
        String keyMarker = emptyToNull(r.param("key-marker"));
        String idMarker = emptyToNull(r.param("upload-id-marker"));
        int max = r.param("max-uploads") == null ? 1000 : Math.min(1000, nonNegative(r.param("max-uploads"), "max-uploads"));
        boolean url = encodingType(r);
        List<Upload> uploads = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        boolean truncated = false;
        String nextKey = null, nextId = null;
        for (Upload u : store.uploads(r.bucket)) {
            if (!u.key().startsWith(prefix)) continue;
            if (keyMarker != null) {
                int c = io.github.bdarwin.cairn.internal.store.Utf8Order.compare(u.key(), keyMarker);
                if (c < 0 || (c == 0 && (idMarker == null || u.id().compareTo(idMarker) <= 0))) continue;
            }
            String rolled = null;
            if (delimiter != null) {
                int i = u.key().indexOf(delimiter, prefix.length());
                if (i >= 0) rolled = u.key().substring(0, i + delimiter.length());
            }
            if (rolled != null && !prefixes.isEmpty() && prefixes.getLast().equals(rolled)) continue;
            if (uploads.size() + prefixes.size() >= max) {
                truncated = true;
                break;
            }
            if (rolled != null) {
                prefixes.add(rolled);
                nextKey = rolled;
                nextId = null;
            } else {
                uploads.add(u);
                nextKey = u.key();
                nextId = u.id();
            }
        }
        StringBuilder sb = new StringBuilder("<ListMultipartUploadsResult xmlns=\"" + Xml.NS + "\">");
        Xml.element(sb, "Bucket", r.bucket);
        Xml.element(sb, "KeyMarker", enc(orEmpty(keyMarker), url));
        Xml.element(sb, "UploadIdMarker", orEmpty(idMarker));
        if (truncated) {
            Xml.element(sb, "NextKeyMarker", enc(nextKey, url));
            Xml.element(sb, "NextUploadIdMarker", orEmpty(nextId));
        }
        Xml.element(sb, "Prefix", enc(prefix, url));
        if (delimiter != null) Xml.element(sb, "Delimiter", enc(delimiter, url));
        if (url) Xml.element(sb, "EncodingType", "url");
        Xml.element(sb, "MaxUploads", max);
        Xml.element(sb, "IsTruncated", truncated);
        for (Upload u : uploads) {
            sb.append("<Upload>");
            Xml.element(sb, "Key", enc(u.key(), url));
            Xml.element(sb, "UploadId", u.id());
            sb.append("<Initiator><ID>cairn</ID><DisplayName>cairn</DisplayName></Initiator><Owner><ID>cairn</ID><DisplayName>cairn</DisplayName></Owner>");
            Xml.element(sb, "StorageClass", "STANDARD");
            Xml.element(sb, "Initiated", HttpDates.iso(u.initiated()));
            if (u.checksumAlgorithm() != null) Xml.element(sb, "ChecksumAlgorithm", u.checksumAlgorithm());
            sb.append("</Upload>");
        }
        for (String p : prefixes) {
            sb.append("<CommonPrefixes>");
            Xml.element(sb, "Prefix", enc(p, url));
            sb.append("</CommonPrefixes>");
        }
        sb.append("</ListMultipartUploadsResult>");
        sendXml(ex, 200, sb.toString());
    }

    private static int partNumber(String s) {
        try {
            int n = Integer.parseInt(s == null ? "" : s.trim());
            if (n >= 1 && n <= MAX_PARTS) return n;
        } catch (NumberFormatException e) {
            // fall through
        }
        throw S3Exception.invalidArgument("Part number must be an integer between 1 and " + MAX_PARTS + ", inclusive");
    }

    private static int nonNegative(String s, String name) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n >= 0) return n;
        } catch (NumberFormatException e) {
            // fall through
        }
        throw S3Exception.invalidArgument("Argument " + name + " must be an integer between 0 and 2147483647");
    }

    private static String text(org.w3c.dom.Element parent, String localName) {
        org.w3c.dom.NodeList l = parent.getElementsByTagNameNS("*", localName);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private void getObject(Request r, HttpExchange ex, boolean head) throws IOException {
        try (StoredObject o = store.get(r.bucket, r.key)) {
            if (o == null) throw S3Exception.noSuchKey();
            ObjectInfo info = o.info();
            long size = info.size();
            Range range = Range.parse(r.header("range"), size);
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", info.contentType());
            h.set("ETag", quote(info.etag()));
            h.set("Last-Modified", HttpDates.http(info.lastModified()));
            h.set("Accept-Ranges", "bytes");
            info.headers().forEach(h::set);
            info.userMetadata().forEach(h::set);
            if (info.parts().size() > 1) h.set("x-amz-mp-parts-count", Integer.toString(info.parts().size()));
            if (range == null && "ENABLED".equalsIgnoreCase(r.header("x-amz-checksum-mode")) && !info.checksums().isEmpty()) {
                info.checksums().forEach((alg, v) -> {
                    h.set(ChecksumAlgorithm.valueOf(alg).headerName(), v);
                    // A composite checksum (of the parts' checksums) ends in -N; clients do not check it against the bytes.
                    h.set("x-amz-checksum-type", v.contains("-") ? "COMPOSITE" : "FULL_OBJECT");
                });
            }
            responseOverrides(r, h);
            long offset = range == null ? 0 : range.first();
            long length = range == null ? size : range.length();
            int status = 200;
            if (range != null) {
                status = 206;
                h.set("Content-Range", "bytes " + range.first() + "-" + range.last() + "/" + size);
            }
            if (head) {
                h.set("Content-Length", Long.toString(length));
                ex.sendResponseHeaders(status, -1);
                return;
            }
            ex.sendResponseHeaders(status, length == 0 ? -1 : length);
            if (length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    o.copyTo(offset, length, out);
                }
            }
        }
    }

    private static void responseOverrides(Request r, Headers h) {
        String[][] map = {{"response-content-type", "Content-Type"}, {"response-content-language", "Content-Language"},
                {"response-expires", "Expires"}, {"response-cache-control", "Cache-Control"},
                {"response-content-disposition", "Content-Disposition"}, {"response-content-encoding", "Content-Encoding"}};
        for (String[] m : map) {
            String v = r.param(m[0]);
            if (v != null) h.set(m[1], v);
        }
    }

    // ---- listing ----

    private void listObjectsV2(Request r, HttpExchange ex) throws IOException {
        String prefix = orEmpty(r.param("prefix"));
        String delimiter = emptyToNull(r.param("delimiter"));
        int maxKeys = maxKeys(r);
        boolean url = encodingType(r);
        String token = r.param("continuation-token");
        String startAfter = emptyToNull(r.param("start-after"));
        String after = startAfter;
        boolean afterIsPrefix = false;
        if (token != null) {
            ContinuationToken t = ContinuationToken.decode(token);
            // The token is past start-after already, unless start-after is further on.
            if (startAfter == null || io.github.bdarwin.cairn.internal.store.Utf8Order.compare(t.after(), startAfter) >= 0) {
                after = t.after();
                afterIsPrefix = t.isPrefix();
            }
        }
        ListPage page = store.list(r.bucket, new ListQuery(prefix, delimiter, after, afterIsPrefix, maxKeys));
        StringBuilder sb = new StringBuilder("<ListBucketResult xmlns=\"" + Xml.NS + "\">");
        Xml.element(sb, "Name", r.bucket);
        Xml.element(sb, "Prefix", enc(prefix, url));
        if (delimiter != null) Xml.element(sb, "Delimiter", enc(delimiter, url));
        Xml.element(sb, "MaxKeys", maxKeys);
        if (url) Xml.element(sb, "EncodingType", "url");
        Xml.element(sb, "KeyCount", page.entries().size());
        Xml.element(sb, "IsTruncated", page.truncated());
        if (token != null) Xml.element(sb, "ContinuationToken", token);
        if (page.truncated()) {
            ListPage.Entry last = page.entries().getLast();
            Xml.element(sb, "NextContinuationToken", new ContinuationToken(last.key(), last.isPrefix()).encode());
        }
        if (startAfter != null) Xml.element(sb, "StartAfter", enc(startAfter, url));
        appendEntries(sb, page, url, "true".equals(r.param("fetch-owner")));
        sb.append("</ListBucketResult>");
        sendXml(ex, 200, sb.toString());
    }

    private void listObjectsV1(Request r, HttpExchange ex) throws IOException {
        String prefix = orEmpty(r.param("prefix"));
        String delimiter = emptyToNull(r.param("delimiter"));
        int maxKeys = maxKeys(r);
        boolean url = encodingType(r);
        String marker = emptyToNull(r.param("marker"));
        // A marker is a key or a common prefix from the previous page; skipping keys under it is right either way,
        // because a key equal to a returned prefix cannot follow it.
        boolean markerIsPrefix = marker != null && delimiter != null && marker.endsWith(delimiter);
        ListPage page = store.list(r.bucket, new ListQuery(prefix, delimiter, marker, markerIsPrefix, maxKeys));
        StringBuilder sb = new StringBuilder("<ListBucketResult xmlns=\"" + Xml.NS + "\">");
        Xml.element(sb, "Name", r.bucket);
        Xml.element(sb, "Prefix", enc(prefix, url));
        Xml.element(sb, "Marker", enc(orEmpty(marker), url));
        if (page.truncated() && delimiter != null) Xml.element(sb, "NextMarker", enc(page.entries().getLast().key(), url));
        Xml.element(sb, "MaxKeys", maxKeys);
        if (delimiter != null) Xml.element(sb, "Delimiter", enc(delimiter, url));
        if (url) Xml.element(sb, "EncodingType", "url");
        Xml.element(sb, "IsTruncated", page.truncated());
        appendEntries(sb, page, url, true);
        sb.append("</ListBucketResult>");
        sendXml(ex, 200, sb.toString());
    }

    private static void appendEntries(StringBuilder sb, ListPage page, boolean url, boolean owner) {
        for (ListPage.Entry e : page.entries()) {
            if (e.isPrefix()) continue;
            ObjectInfo o = e.object();
            sb.append("<Contents>");
            Xml.element(sb, "Key", enc(o.key(), url));
            Xml.element(sb, "LastModified", HttpDates.iso(o.lastModified()));
            Xml.element(sb, "ETag", quote(o.etag()));
            Xml.element(sb, "Size", o.size());
            if (owner) sb.append("<Owner><ID>cairn</ID><DisplayName>cairn</DisplayName></Owner>");
            Xml.element(sb, "StorageClass", "STANDARD");
            sb.append("</Contents>");
        }
        for (ListPage.Entry e : page.entries()) {
            if (!e.isPrefix()) continue;
            sb.append("<CommonPrefixes>");
            Xml.element(sb, "Prefix", enc(e.key(), url));
            sb.append("</CommonPrefixes>");
        }
    }

    private static int maxKeys(Request r) {
        String v = r.param("max-keys");
        if (v == null) return 1000;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) throw S3Exception.invalidArgument("max-keys cannot be negative");
            return Math.min(n, 1000);
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument("Provided max-keys not an integer or within integer range");
        }
    }

    private static boolean encodingType(Request r) {
        String v = r.param("encoding-type");
        if (v == null) return false;
        if (!v.equals("url")) throw S3Exception.invalidArgument("Invalid Encoding Method specified in Request");
        return true;
    }

    /** With {@code encoding-type=url}, names are percent-encoded so any key survives XML 1.0. */
    private static String enc(String s, boolean url) {
        return url ? io.github.bdarwin.cairn.internal.auth.SigV4.uriEncode(s, false) : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** The opaque {@code NextContinuationToken}: the last key or common prefix returned. */
    record ContinuationToken(String after, boolean isPrefix) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(((isPrefix ? "p" : "k") + after).getBytes(StandardCharsets.UTF_8));
        }

        static ContinuationToken decode(String token) {
            try {
                String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                if (s.isEmpty() || (s.charAt(0) != 'p' && s.charAt(0) != 'k')) throw new IllegalArgumentException();
                return new ContinuationToken(s.substring(1), s.charAt(0) == 'p');
            } catch (IllegalArgumentException e) {
                throw S3Exception.invalidArgument("The continuation token provided is incorrect");
            }
        }
    }

    // ---- buckets ----

    private void listBuckets(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("<ListAllMyBucketsResult xmlns=\"" + Xml.NS + "\"><Owner><ID>cairn</ID><DisplayName>cairn</DisplayName></Owner><Buckets>");
        for (BucketInfo b : store.listBuckets()) {
            sb.append("<Bucket>");
            Xml.element(sb, "Name", b.name());
            Xml.element(sb, "CreationDate", HttpDates.iso(b.created()));
            sb.append("</Bucket>");
        }
        sb.append("</Buckets></ListAllMyBucketsResult>");
        sendXml(ex, 200, sb.toString());
    }

    private void requireBucket(String bucket) throws IOException {
        if (!store.bucketExists(bucket)) throw S3Exception.noSuchBucket();
    }

    static boolean validBucketName(String name) {
        return BUCKET_NAME.matcher(name).matches() && !name.contains("..") && !IP_ADDRESS.matcher(name).matches()
                && !name.startsWith("xn--") && !name.endsWith("-s3alias");
    }

    // ---- request pieces ----

    private static String contentType(Request r) {
        String ct = r.header("content-type");
        return ct == null || ct.isBlank() ? "binary/octet-stream" : ct;
    }

    private static Map<String, String> userMetadata(Request r) {
        Map<String, String> out = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
            if (e.getKey().startsWith("x-amz-meta-")) {
                String v = String.join(",", e.getValue());
                out.put(e.getKey(), v);
                total += e.getKey().length() - "x-amz-meta-".length() + v.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (total > MAX_USER_METADATA) throw new S3Exception(400, "MetadataTooLarge", "Your metadata headers exceed the maximum allowed metadata size.");
        return out;
    }

    private static Map<String, String> storedHeaders(Request r) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : STORED_HEADERS) {
            String v = r.header(name.toLowerCase(Locale.ROOT));
            if (v == null) continue;
            if (name.equals("Content-Encoding")) {
                // aws-chunked describes this request's framing, not the object.
                v = String.join(",", java.util.Arrays.stream(v.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty() && !s.equalsIgnoreCase("aws-chunked")).toList());
                if (v.isEmpty()) continue;
            }
            out.put(name, v);
        }
        return out;
    }

    private static void refuseSubresources(Request r, String... allowed) {
        for (Map.Entry<String, String> p : r.query) {
            if (SUBRESOURCES.contains(p.getKey()) && !List.of(allowed).contains(p.getKey())) throw S3Exception.notImplemented("?" + p.getKey());
        }
    }

    private static boolean isHexSha256(String s) {
        return s.length() == 64 && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    private static S3Exception methodNotAllowed() {
        return new S3Exception(405, "MethodNotAllowed", "The specified method is not allowed against this resource.");
    }

    static String quote(String etag) {
        return "\"" + etag + "\"";
    }

    // ---- responses ----

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
        }
    }

    private static void sendXml(HttpExchange ex, int status, String xml) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/xml");
        send(ex, status, (Xml.DECLARATION + xml).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(HttpExchange ex, Request r, S3Exception e, String requestId) {
        if (ex.getResponseCode() != -1) return;
        try {
            Headers h = ex.getResponseHeaders();
            // Response headers prepared for a success must not leak into the error.
            for (String name : List.copyOf(h.keySet())) {
                if (!name.equalsIgnoreCase("x-amz-request-id") && !name.equalsIgnoreCase("Server")) h.remove(name);
            }
            String method = ex.getRequestMethod();
            if (method.equals("PUT") || method.equals("POST")) {
                // The body may be unread; the client cannot reuse this connection anyway (probe 1).
                h.set("Connection", "close");
            }
            e.headers().forEach(h::set);
            if (method.equals("HEAD")) {
                ex.sendResponseHeaders(e.status(), -1);
                return;
            }
            StringBuilder sb = new StringBuilder("<Error>");
            Xml.element(sb, "Code", e.code());
            Xml.element(sb, "Message", e.getMessage());
            if (r != null && r.bucket != null) {
                if (r.key != null) Xml.element(sb, "Key", r.key);
                Xml.element(sb, "BucketName", r.bucket);
                Xml.element(sb, "Resource", "/" + r.bucket + (r.key != null ? "/" + r.key : ""));
            }
            Xml.element(sb, "RequestId", requestId);
            sb.append("</Error>");
            sendXml(ex, e.status(), sb.toString());
        } catch (IOException io) {
            LOG.log(Level.FINE, "could not send the error response", io);
        }
    }

    // ---- types ----

    /** A parsed request: method, bucket and key (null when absent), query and headers. */
    static final class Request {
        final String method;
        final String bucket;
        final String key;
        final List<Map.Entry<String, String>> query;
        final Map<String, List<String>> headers;
        final String path;

        private Request(String method, String path, String bucket, String key, List<Map.Entry<String, String>> query, Map<String, List<String>> headers) {
            this.method = method;
            this.path = path;
            this.bucket = bucket;
            this.key = key;
            this.query = query;
            this.headers = headers;
        }

        static Request parse(HttpExchange ex) {
            String rawPath = ex.getRequestURI().getRawPath();
            if (rawPath == null || !rawPath.startsWith("/")) throw new S3Exception(400, "InvalidURI", "Couldn't parse the specified URI.");
            String path = Uris.decode(rawPath);
            String rest = path.substring(1);
            String bucket = null, key = null;
            if (!rest.isEmpty()) {
                int slash = rest.indexOf('/');
                if (slash < 0) {
                    bucket = rest;
                } else {
                    bucket = rest.substring(0, slash);
                    key = rest.substring(slash + 1);
                    if (key.isEmpty()) key = null;
                }
            }
            return new Request(ex.getRequestMethod(), path, bucket, key, Uris.parseQuery(ex.getRequestURI().getRawQuery()),
                    SignedRequest.lowerCase(ex.getRequestHeaders()));
        }

        SignedRequest signed() {
            return new SignedRequest(method, path, query, headers);
        }

        String header(String lowerName) {
            List<String> v = headers.get(lowerName);
            return v == null || v.isEmpty() ? null : v.getFirst();
        }

        boolean has(String param) {
            for (Map.Entry<String, String> e : query) if (e.getKey().equals(param)) return true;
            return false;
        }

        String param(String name) {
            for (Map.Entry<String, String> e : query) if (e.getKey().equals(name)) return e.getValue();
            return null;
        }
    }

    /**
     * The checksum a PUT declares: its algorithm, and where its value comes from: a header, a trailer,
     * or nowhere ({@code x-amz-sdk-checksum-algorithm} alone: compute and store it, nothing to compare).
     */
    private record ExpectedChecksum(ChecksumAlgorithm algorithm, String value, boolean inTrailer) {
        static ExpectedChecksum of(Request r) {
            ChecksumAlgorithm alg = null;
            String value = null;
            for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
                ChecksumAlgorithm a = ChecksumAlgorithm.fromHeaderName(e.getKey());
                if (a == null) continue;
                if (alg != null) throw multipleChecksums();
                alg = a;
                value = e.getValue().getFirst().trim();
            }
            String trailer = r.header("x-amz-trailer");
            if (trailer != null) {
                ChecksumAlgorithm a = ChecksumAlgorithm.fromHeaderName(trailer.trim());
                if (a == null) throw S3Exception.invalidRequest("The value specified in the x-amz-trailer header is not supported");
                if (alg != null) throw multipleChecksums();
                return new ExpectedChecksum(a, null, true);
            }
            if (alg != null) return new ExpectedChecksum(alg, value, false);
            String sdk = r.header("x-amz-sdk-checksum-algorithm");
            if (sdk == null) return null;
            alg = ChecksumAlgorithm.parse(sdk);
            if (alg == null) throw S3Exception.invalidRequest("Value for x-amz-sdk-checksum-algorithm header is invalid.");
            return new ExpectedChecksum(alg, null, false);
        }

        private static S3Exception multipleChecksums() {
            return S3Exception.invalidRequest("Expecting a single x-amz-checksum- header. Multiple checksum Types are not allowed.");
        }
    }

    /** Passes bytes through, updating the request's checksum and, for a signed payload, its SHA-256. */
    private static final class DigestingInputStream extends FilterInputStream {
        final ChecksumAlgorithm.Hasher checksum;
        final MessageDigest sha256;

        DigestingInputStream(InputStream in, ChecksumAlgorithm.Hasher checksum, MessageDigest sha256) {
            super(in);
            this.checksum = checksum;
            this.sha256 = sha256;
        }

        @Override
        public int read() throws IOException {
            int c = in.read();
            if (c >= 0) {
                byte[] one = {(byte) c};
                update(one, 0, 1);
            }
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) update(b, off, n);
            return n;
        }

        private void update(byte[] b, int off, int n) {
            if (checksum != null) checksum.update(b, off, n);
            if (sha256 != null) sha256.update(b, off, n);
        }
    }
}
