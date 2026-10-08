package io.github.bdarwin.cairn.internal.auth;

import io.github.bdarwin.cairn.internal.S3Exception;
import io.github.bdarwin.cairn.internal.Uris;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatorTest {

    static final String AK = "AKIAIOSFODNN7EXAMPLE";
    static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    static final Clock MAY_2013 = Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC);

    /** The GET Object example from the S3 API reference ("Signature Calculations for the Authorization Header"). */
    @Test
    void awsDocumentationGetObjectExample() {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("examplebucket.s3.amazonaws.com"));
        h.put("range", List.of("bytes=0-9"));
        h.put("x-amz-content-sha256", List.of(SigV4.EMPTY_SHA256));
        h.put("x-amz-date", List.of("20130524T000000Z"));
        h.put("authorization", List.of("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
        AuthContext a = new Authenticator(Map.of(AK, SECRET), MAY_2013).authenticate(new SignedRequest("GET", "/test.txt", List.of(), h));
        assertEquals(AK, a.accessKey());
    }

    @Test
    void wrongSecretUnknownKeyAndSkewAreRefused() {
        Map<String, List<String>> h = new HashMap<>();
        h.put("host", List.of("examplebucket.s3.amazonaws.com"));
        h.put("range", List.of("bytes=0-9"));
        h.put("x-amz-content-sha256", List.of(SigV4.EMPTY_SHA256));
        h.put("x-amz-date", List.of("20130524T000000Z"));
        h.put("authorization", List.of("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
        SignedRequest r = new SignedRequest("GET", "/test.txt", List.of(), h);
        assertEquals("SignatureDoesNotMatch", code(() -> new Authenticator(Map.of(AK, "other"), MAY_2013).authenticate(r)));
        assertEquals("InvalidAccessKeyId", code(() -> new Authenticator(Map.of("OTHER", SECRET), MAY_2013).authenticate(r)));
        Clock later = Clock.offset(MAY_2013, java.time.Duration.ofMinutes(16));
        assertEquals("RequestTimeTooSkewed", code(() -> new Authenticator(Map.of(AK, SECRET), later).authenticate(r)));
        assertEquals("AccessDenied", code(() -> new Authenticator(Map.of(AK, SECRET), MAY_2013)
                .authenticate(new SignedRequest("GET", "/test.txt", List.of(), Map.of()))));
    }

    /** Requests signed by the AWS SDK's own SigV4 signer, with awkward paths, queries and headers, verify. */
    @Test
    void acceptsWhatTheSdkSignerSigns() {
        Random rnd = new Random(11);
        Clock now = Clock.systemUTC();
        Authenticator auth = new Authenticator(Map.of(AK, SECRET), now);
        AwsV4HttpSigner signer = AwsV4HttpSigner.create();
        String[] pieces = {"a", "B", " ", "+", "%", "é", "日本", "~", "!", "'", "(", ")", "*", "=", "&", "$", "@", ":", ",", ";", "//", ".", "..", "-", "_"};
        for (int i = 0; i < 300; i++) {
            StringBuilder key = new StringBuilder();
            for (int j = 0, n = 1 + rnd.nextInt(8); j < n; j++) key.append(pieces[rnd.nextInt(pieces.length)]);
            String encodedPath = "/bucket/" + SigV4.uriEncode(key.toString(), false);
            SdkHttpRequest.Builder b = SdkHttpRequest.builder().method(SdkHttpMethod.PUT)
                    .uri(URI.create("http://localhost:9000" + encodedPath))
                    .putHeader("Host", "localhost:9000")
                    .putHeader("x-amz-meta-note", "  spaced   value  " + i)
                    .putHeader("Content-Type", "text/plain");
            if (rnd.nextBoolean()) b.putRawQueryParameter("prefix", key.toString());
            if (rnd.nextBoolean()) b.putRawQueryParameter("uploads", (String) null);
            SdkHttpRequest req = b.build();
            String body = "body " + i;
            var signed = signer.sign(s -> s.identity(AwsCredentialsIdentity.create(AK, SECRET))
                    .request(req)
                    .payload(ContentStreamProvider.fromUtf8String(body))
                    .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                    .putProperty(AwsV4HttpSigner.REGION_NAME, "eu-west-2")
                    .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                    .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                    .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, true));
            SdkHttpRequest s = signed.request();
            SignedRequest ours = new SignedRequest("PUT", Uris.decode(s.encodedPath()), Uris.parseQuery(rawQuery(s)),
                    SignedRequest.lowerCase(s.headers()));
            assertEquals(AK, auth.authenticate(ours).accessKey(), () -> "path " + s.encodedPath() + " query " + rawQuery(s));
            Map<String, List<String>> tampered = new HashMap<>(ours.headers());
            tampered.put("x-amz-meta-note", List.of("other"));
            assertEquals("SignatureDoesNotMatch", code(() -> auth.authenticate(new SignedRequest("PUT", ours.path(), ours.query(), tampered))));
        }
    }

    private static String rawQuery(SdkHttpRequest s) {
        List<String> parts = new ArrayList<>();
        s.rawQueryParameters().forEach((k, vs) -> {
            for (String v : vs) parts.add(SigV4.uriEncode(k, true) + (v == null ? "" : "=" + SigV4.uriEncode(v, true)));
        });
        return String.join("&", parts);
    }

    static String code(Runnable r) {
        return assertThrows(S3Exception.class, r::run).code();
    }
}
