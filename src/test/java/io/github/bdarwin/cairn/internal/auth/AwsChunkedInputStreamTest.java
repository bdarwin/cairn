package io.github.bdarwin.cairn.internal.auth;

import io.github.bdarwin.cairn.internal.S3Exception;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.bdarwin.cairn.internal.auth.AuthenticatorTest.AK;
import static io.github.bdarwin.cairn.internal.auth.AuthenticatorTest.MAY_2013;
import static io.github.bdarwin.cairn.internal.auth.AuthenticatorTest.SECRET;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsChunkedInputStreamTest {

    /**
     * The chunked upload example from the S3 API reference ("Signature Calculations for the
     * Authorization Header: Transferring Payload in Multiple Chunks"): 66,560 bytes of 'a' in a 64 KiB
     * chunk and a 1 KiB chunk, with the seed and chunk signatures it publishes.
     */
    @Test
    void awsDocumentationChunkedExample() throws IOException {
        AuthContext auth = docExampleAuth();
        assertEquals("4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9", auth.signature());
        byte[] body = docExampleBody(new String[] {
                "ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648",
                "0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497",
                "b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9"});
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body), auth);
        byte[] decoded = in.readAllBytes();
        byte[] expected = new byte[66560];
        Arrays.fill(expected, (byte) 'a');
        assertArrayEquals(expected, decoded);
        assertEquals(66560, in.decodedLength());
    }

    @Test
    void aChangedByteOrSignatureIsRefused() {
        AuthContext auth = docExampleAuth();
        String[] sigs = {"ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648",
                "0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497",
                "b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9"};
        byte[] body = docExampleBody(sigs);
        body[body.length / 2] = 'b';
        assertEquals("SignatureDoesNotMatch", assertThrows(S3Exception.class,
                () -> new AwsChunkedInputStream(new ByteArrayInputStream(body), auth).readAllBytes()).code());
        String[] badFinal = sigs.clone();
        badFinal[2] = "0".repeat(64);
        assertEquals("SignatureDoesNotMatch", assertThrows(S3Exception.class,
                () -> new AwsChunkedInputStream(new ByteArrayInputStream(docExampleBody(badFinal)), auth).readAllBytes()).code());
    }

    @Test
    void truncatedBodyIsIncomplete() {
        AuthContext auth = docExampleAuth();
        byte[] body = docExampleBody(new String[] {"ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648",
                "0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497",
                "b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9"});
        byte[] cut = Arrays.copyOf(body, 40_000);
        assertEquals("IncompleteBody", assertThrows(S3Exception.class,
                () -> new AwsChunkedInputStream(new ByteArrayInputStream(cut), auth).readAllBytes()).code());
    }

    /** STREAMING-UNSIGNED-PAYLOAD-TRAILER, the form SDKs use over HTTPS: no signatures, a checksum trailer. */
    @Test
    void unsignedTrailer() throws IOException {
        AuthContext auth = new AuthContext(AK, new byte[32], "20130524T000000Z", "20130524/us-east-1/s3/aws4_request", "x",
                "STREAMING-UNSIGNED-PAYLOAD-TRAILER");
        String wire = "5\r\nhello\r\n6\r\n world\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n\r\n";
        AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), auth);
        assertEquals("hello world", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        assertEquals(Map.of("x-amz-checksum-crc32", "DUoRhQ=="), in.trailers());
    }

    @Test
    void readsInSmallPiecesGiveTheSameBytes() throws IOException {
        AuthContext auth = new AuthContext(AK, new byte[32], "20130524T000000Z", "20130524/us-east-1/s3/aws4_request", "x",
                "STREAMING-UNSIGNED-PAYLOAD-TRAILER");
        String wire = "3\r\nabc\r\n1\r\nd\r\n0\r\n\r\n";
        InputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), auth);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int c; (c = in.read()) >= 0; ) out.write(c);
        assertEquals("abcd", out.toString(StandardCharsets.US_ASCII));
    }

    static AuthContext docExampleAuth() {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("s3.amazonaws.com"));
        h.put("x-amz-date", List.of("20130524T000000Z"));
        h.put("x-amz-storage-class", List.of("REDUCED_REDUNDANCY"));
        h.put("x-amz-content-sha256", List.of("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"));
        h.put("content-encoding", List.of("aws-chunked"));
        h.put("x-amz-decoded-content-length", List.of("66560"));
        h.put("content-length", List.of("66824"));
        h.put("authorization", List.of("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=content-encoding;content-length;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length;x-amz-storage-class,"
                + "Signature=4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));
        return new Authenticator(Map.of(AK, SECRET), MAY_2013).authenticate(
                new SignedRequest("PUT", "/examplebucket/chunkObject.txt", List.of(), h));
    }

    static byte[] docExampleBody(String[] sigs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] sizes = {65536, 1024, 0};
        for (int i = 0; i < 3; i++) {
            out.writeBytes((Integer.toHexString(sizes[i]) + ";chunk-signature=" + sigs[i] + "\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] data = new byte[sizes[i]];
            Arrays.fill(data, (byte) 'a');
            out.writeBytes(data);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }
}
