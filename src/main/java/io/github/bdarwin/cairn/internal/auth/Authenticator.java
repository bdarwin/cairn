package io.github.bdarwin.cairn.internal.auth;

import io.github.bdarwin.cairn.internal.S3Exception;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Verifies AWS Signature Version 4, in the {@code Authorization} header or in the query string
 * (presigned URLs), against static access keys.
 */
public final class Authenticator {

    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final Duration MAX_SKEW = Duration.ofMinutes(15);
    private static final long MAX_PRESIGN_SECONDS = 7 * 24 * 3600;

    private final Map<String, String> secrets;
    private final Clock clock;

    public Authenticator(Map<String, String> secretsByAccessKey, Clock clock) {
        this.secrets = Map.copyOf(secretsByAccessKey);
        this.clock = clock;
    }

    public AuthContext authenticate(SignedRequest r) {
        String auth = r.header("authorization");
        if (auth != null) return fromHeader(r, auth);
        if (r.queryParam("X-Amz-Algorithm") != null) return fromQuery(r);
        throw S3Exception.accessDenied("Access Denied");
    }

    private AuthContext fromHeader(SignedRequest r, String auth) {
        if (!auth.startsWith(SigV4.ALGORITHM + " ")) {
            if (auth.startsWith("AWS ")) throw S3Exception.invalidRequest("Signature Version 2 is not supported; use AWS4-HMAC-SHA256.");
            throw S3Exception.authorizationMalformed("The authorization header is malformed.");
        }
        String credential = null, signedHeaders = null, signature = null;
        for (String part : auth.substring(SigV4.ALGORITHM.length() + 1).split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            String name = p.substring(0, eq), value = p.substring(eq + 1).trim();
            switch (name) {
                case "Credential" -> credential = value;
                case "SignedHeaders" -> signedHeaders = value;
                case "Signature" -> signature = value;
                default -> {
                }
            }
        }
        if (credential == null || signedHeaders == null || signature == null) {
            throw S3Exception.authorizationMalformed("The authorization header is malformed; it must contain Credential, SignedHeaders and Signature.");
        }
        String payloadHash = r.header("x-amz-content-sha256");
        if (payloadHash == null) throw S3Exception.invalidRequest("Missing required header for this request: x-amz-content-sha256");
        String amzDate = r.header("x-amz-date");
        Instant when = amzDate != null ? parseAmzDate(amzDate) : parseHttpDate(r.header("date"));
        if (amzDate == null) amzDate = AMZ_DATE.format(when.atOffset(ZoneOffset.UTC));
        if (Duration.between(when, clock.instant()).abs().compareTo(MAX_SKEW) > 0) {
            throw new S3Exception(403, "RequestTimeTooSkewed", "The difference between the request time and the current time is too large.");
        }
        Credential c = Credential.parse(credential, amzDate);
        String secret = secrets.get(c.accessKey);
        if (secret == null) throw S3Exception.invalidAccessKeyId();
        List<String> signed = Arrays.asList(signedHeaders.split(";"));
        if (!signed.contains("host")) throw S3Exception.authorizationMalformed("SignedHeaders must include host.");
        String canonical = canonicalRequest(r, signed, payloadHash, null);
        byte[] key = SigV4.signingKey(secret, c.date, c.region, c.service);
        String expected = SigV4.hex(SigV4.hmac(key, SigV4.stringToSign(amzDate, c.scope(), canonical)));
        if (!SigV4.sameSignature(expected, signature)) throw S3Exception.signatureDoesNotMatch();
        return new AuthContext(c.accessKey, key, amzDate, c.scope(), expected, payloadHash);
    }

    private AuthContext fromQuery(SignedRequest r) {
        if (!SigV4.ALGORITHM.equals(r.queryParam("X-Amz-Algorithm"))) {
            throw S3Exception.invalidArgument("X-Amz-Algorithm only supports \"AWS4-HMAC-SHA256\"");
        }
        String credential = r.queryParam("X-Amz-Credential");
        String amzDate = r.queryParam("X-Amz-Date");
        String expires = r.queryParam("X-Amz-Expires");
        String signedHeaders = r.queryParam("X-Amz-SignedHeaders");
        String signature = r.queryParam("X-Amz-Signature");
        if (credential == null || amzDate == null || expires == null || signedHeaders == null || signature == null) {
            throw S3Exception.accessDenied("Query-string authentication requires the X-Amz-Algorithm, X-Amz-Credential, X-Amz-Signature, X-Amz-Date, X-Amz-SignedHeaders, and X-Amz-Expires parameters.");
        }
        Instant when = parseAmzDate(amzDate);
        long seconds;
        try {
            seconds = Long.parseLong(expires);
        } catch (NumberFormatException e) {
            throw S3Exception.authorizationMalformed("X-Amz-Expires should be a number");
        }
        if (seconds < 0) throw S3Exception.authorizationMalformed("X-Amz-Expires must be non-negative");
        if (seconds > MAX_PRESIGN_SECONDS) throw S3Exception.authorizationMalformed("X-Amz-Expires must be less than a week (in seconds) that is 604800");
        Instant now = clock.instant();
        if (when.minus(MAX_SKEW).isAfter(now)) throw S3Exception.accessDenied("Request is not valid yet");
        if (now.isAfter(when.plusSeconds(seconds))) throw S3Exception.accessDenied("Request has expired");
        Credential c = Credential.parse(credential, amzDate);
        String secret = secrets.get(c.accessKey);
        if (secret == null) throw S3Exception.invalidAccessKeyId();
        String payloadHash = r.header("x-amz-content-sha256");
        if (payloadHash == null) payloadHash = "UNSIGNED-PAYLOAD";
        List<String> signed = Arrays.asList(signedHeaders.split(";"));
        String canonical = canonicalRequest(r, signed, payloadHash, "X-Amz-Signature");
        byte[] key = SigV4.signingKey(secret, c.date, c.region, c.service);
        String expected = SigV4.hex(SigV4.hmac(key, SigV4.stringToSign(amzDate, c.scope(), canonical)));
        if (!SigV4.sameSignature(expected, signature)) throw S3Exception.signatureDoesNotMatch();
        return new AuthContext(c.accessKey, key, amzDate, c.scope(), expected, payloadHash);
    }

    /** The canonical request; visible for tests that compare it with a client's. */
    static String canonicalRequest(SignedRequest r, List<String> signedHeaders, String payloadHash, String excludeParam) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(r.method()).append('\n');
        sb.append(SigV4.uriEncode(r.path(), false)).append('\n');
        sb.append(SigV4.canonicalQuery(r.query(), excludeParam)).append('\n');
        for (String h : signedHeaders) {
            List<String> values = r.headers().get(h);
            if (values == null) throw S3Exception.accessDenied("There were headers present in the request which were not signed: " + h);
            List<String> canon = new ArrayList<>(values.size());
            for (String v : values) canon.add(SigV4.canonicalHeaderValue(v));
            sb.append(h).append(':').append(String.join(",", canon)).append('\n');
        }
        sb.append('\n').append(String.join(";", signedHeaders)).append('\n').append(payloadHash);
        return sb.toString();
    }

    static Instant parseAmzDate(String s) {
        try {
            return LocalDateTime.parse(s, AMZ_DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw S3Exception.accessDenied("AWS authentication requires a valid Date or x-amz-date header");
        }
    }

    private static Instant parseHttpDate(String s) {
        if (s == null) throw S3Exception.accessDenied("AWS authentication requires a valid Date or x-amz-date header");
        try {
            return java.time.ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw S3Exception.accessDenied("AWS authentication requires a valid Date or x-amz-date header");
        }
    }

    private record Credential(String accessKey, String date, String region, String service) {
        String scope() {
            return date + "/" + region + "/" + service + "/aws4_request";
        }

        static Credential parse(String credential, String amzDate) {
            String[] p = credential.split("/");
            if (p.length != 5 || !p[4].equals("aws4_request")) {
                throw S3Exception.authorizationMalformed("The authorization header is malformed; the Credential is mal-formed; expecting \"<YOUR-AKID>/YYYYMMDD/REGION/SERVICE/aws4_request\".");
            }
            if (!p[3].equals("s3")) throw S3Exception.authorizationMalformed("The authorization header is malformed; incorrect service \"" + p[3] + "\". This endpoint belongs to \"s3\".");
            if (!amzDate.startsWith(p[1])) throw S3Exception.authorizationMalformed("The authorization header is malformed; Invalid credential date. Date is not the same as X-Amz-Date: \"" + p[1] + "\".");
            return new Credential(p[0], p[1], p[2], p[3]);
        }
    }
}
