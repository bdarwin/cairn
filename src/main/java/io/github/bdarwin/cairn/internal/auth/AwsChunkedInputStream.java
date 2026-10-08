package io.github.bdarwin.cairn.internal.auth;

import io.github.bdarwin.cairn.internal.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes an {@code aws-chunked} request body as it streams, in the three forms clients send (see
 * {@code docs/probes.md}, probe 2):
 * <ul>
 *   <li>{@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD}: {@code size;chunk-signature=sig CRLF data CRLF},
 *       ending with a zero-size chunk;</li>
 *   <li>{@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER}: the same, then trailer lines and a
 *       {@code x-amz-trailer-signature} line;</li>
 *   <li>{@code STREAMING-UNSIGNED-PAYLOAD-TRAILER}: {@code size CRLF data CRLF} with no signatures,
 *       then trailer lines.</li>
 * </ul>
 * Each chunk signature is checked when its chunk ends, chained from the request's signature. A bad
 * signature or malformed framing throws {@link S3Exception}. Nothing is buffered beyond one line, so
 * callers must not commit what they read until this stream has reached its end without error.
 */
public final class AwsChunkedInputStream extends InputStream {

    private static final int MAX_LINE = 4096;
    private static final int MAX_TRAILER_BYTES = 16 * 1024;

    private final InputStream in;
    private final AuthContext auth;
    private final boolean signed;
    private final boolean trailer;
    private final Map<String, String> trailers = new LinkedHashMap<>();

    private String previousSignature;
    private String chunkSignature;
    private MessageDigest chunkHash;
    private long remaining;
    private boolean done;
    private long decoded;

    /**
     * @param in      the raw body, positioned at the first chunk header
     * @param auth    the verified request; its payload hash names the framing
     */
    public AwsChunkedInputStream(InputStream in, AuthContext auth) {
        this.in = in.markSupported() ? in : new java.io.BufferedInputStream(in, 64 * 1024);
        this.auth = auth;
        String mode = auth.payloadHash();
        this.signed = mode.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
        this.trailer = mode.endsWith("-TRAILER");
        if (!signed && !mode.equals("STREAMING-UNSIGNED-PAYLOAD-TRAILER")) {
            throw S3Exception.invalidArgument("Unsupported x-amz-content-sha256: " + mode);
        }
        this.previousSignature = auth.signature();
    }

    public static boolean isChunked(String payloadHash) {
        return payloadHash != null && payloadHash.startsWith("STREAMING-");
    }

    /** Trailer headers by lower-case name; complete once the stream has returned -1. */
    public Map<String, String> trailers() {
        return trailers;
    }

    /** Decoded bytes returned so far. */
    public long decodedLength() {
        return decoded;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (remaining == 0) {
            if (done) return -1;
            nextChunk();
        }
        int n = in.read(b, off, (int) Math.min(len, remaining));
        if (n < 0) throw S3Exception.incompleteBody();
        if (chunkHash != null) chunkHash.update(b, off, n);
        remaining -= n;
        decoded += n;
        if (remaining == 0) endChunk();
        return n;
    }

    /** Reads the next chunk header; on the final chunk, reads the trailers and finishes. */
    private void nextChunk() throws IOException {
        String header = readLine();
        int semi = header.indexOf(';');
        String sizeHex = semi < 0 ? header : header.substring(0, semi);
        long size;
        try {
            size = Long.parseLong(sizeHex.trim(), 16);
        } catch (NumberFormatException e) {
            throw malformed("bad chunk size");
        }
        if (size < 0) throw malformed("bad chunk size");
        if (signed) {
            String ext = semi < 0 ? "" : header.substring(semi + 1);
            if (!ext.startsWith("chunk-signature=")) throw malformed("missing chunk-signature");
            chunkSignature = ext.substring("chunk-signature=".length()).trim();
            chunkHash = SigV4.sha256();
        }
        remaining = size;
        if (size == 0) {
            if (signed) verifyChunk(SigV4.EMPTY_SHA256);
            if (trailer) readTrailers();
            else if (!readLine().isEmpty()) throw malformed("expected CRLF after the final chunk");
            done = true;
        }
    }

    /** After a chunk's data: its CRLF, then its signature. */
    private void endChunk() throws IOException {
        if (in.read() != '\r' || in.read() != '\n') throw malformed("chunk data not followed by CRLF");
        if (signed) verifyChunk(SigV4.hex(chunkHash.digest()));
    }

    private void verifyChunk(String dataHashHex) {
        String toSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + auth.amzDate() + "\n" + auth.scope() + "\n" + previousSignature + "\n"
                + SigV4.EMPTY_SHA256 + "\n" + dataHashHex;
        String expected = SigV4.hex(SigV4.hmac(auth.signingKey(), toSign));
        if (!SigV4.sameSignature(expected, chunkSignature)) throw S3Exception.signatureDoesNotMatch();
        previousSignature = expected;
    }

    private void readTrailers() throws IOException {
        StringBuilder canonical = new StringBuilder();
        String trailerSignature = null;
        int total = 0;
        while (true) {
            String line = readLine();
            if (line.isEmpty()) break;
            total += line.length();
            if (total > MAX_TRAILER_BYTES) throw malformed("trailers too large");
            int colon = line.indexOf(':');
            if (colon <= 0) throw malformed("bad trailer line");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-amz-trailer-signature")) {
                trailerSignature = value;
            } else {
                trailers.put(name, value);
                canonical.append(name).append(':').append(value).append('\n');
            }
        }
        if (signed) {
            if (trailerSignature == null) throw malformed("missing x-amz-trailer-signature");
            String toSign = "AWS4-HMAC-SHA256-TRAILER\n" + auth.amzDate() + "\n" + auth.scope() + "\n" + previousSignature + "\n"
                    + SigV4.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
            String expected = SigV4.hex(SigV4.hmac(auth.signingKey(), toSign));
            if (!SigV4.sameSignature(expected, trailerSignature)) throw S3Exception.signatureDoesNotMatch();
        }
    }

    /** A CRLF-terminated line without its CRLF. The final empty line may arrive without its CRLF. */
    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(96);
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (line.size() == 0) return "";
                throw S3Exception.incompleteBody();
            }
            if (c == '\r') {
                if (in.read() != '\n') throw malformed("CR not followed by LF");
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (line.size() >= MAX_LINE) throw malformed("line too long");
            line.write(c);
        }
    }

    private static S3Exception malformed(String what) {
        return new S3Exception(400, "IncompleteBody", "The aws-chunked body is malformed: " + what);
    }
}
