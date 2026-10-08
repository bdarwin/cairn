package io.github.bdarwin.cairn;

import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

/** Requests the SDK will not send (bad names, odd sub-resources), signed with the SDK's own SigV4 signer. */
final class RawRequests {

    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "expect", "connection");
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    RawRequests(URI endpoint) {
        this.endpoint = endpoint;
    }

    /** Sends a signed request; {@code pathAndQuery} is already URI-encoded. */
    HttpResponse<String> send(String method, String pathAndQuery, byte[] body, Map<String, String> headers) throws IOException, InterruptedException {
        return sendSignedFor(method, pathAndQuery, body, body, headers);
    }

    /** Signs the request for {@code signedBody} but sends {@code sentBody}. */
    HttpResponse<String> sendSignedFor(String method, String pathAndQuery, byte[] signedBody, byte[] sentBody, Map<String, String> headers)
            throws IOException, InterruptedException {
        URI uri = endpoint.resolve(pathAndQuery);
        SdkHttpRequest.Builder b = SdkHttpRequest.builder().method(SdkHttpMethod.fromValue(method)).uri(uri)
                .putHeader("Host", uri.getHost() + ":" + uri.getPort());
        headers.forEach(b::putHeader);
        byte[] payload = signedBody == null ? new byte[0] : signedBody;
        var signed = AwsV4HttpSigner.create().sign(s -> s.identity(AwsCredentialsIdentity.create(TestServer.ACCESS_KEY, TestServer.SECRET_KEY))
                .request(b.build())
                .payload(ContentStreamProvider.fromByteArray(payload))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, "us-east-1")
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, true));
        return sendAsIs(method, uri, sentBody, signed.request().headers());
    }

    HttpResponse<String> sendAsIs(String method, URI uri, byte[] body, Map<String, java.util.List<String>> headers) throws IOException, InterruptedException {
        HttpRequest.Builder r = HttpRequest.newBuilder(uri).method(method, body == null || body.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((k, vs) -> {
            if (!RESTRICTED.contains(k.toLowerCase())) vs.forEach(v -> r.header(k, v));
        });
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }
}
