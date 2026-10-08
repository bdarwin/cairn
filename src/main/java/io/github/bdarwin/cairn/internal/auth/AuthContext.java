package io.github.bdarwin.cairn.internal.auth;

/**
 * A verified request's signing context, which a streaming body's chunk signatures chain from.
 *
 * @param accessKey   who signed
 * @param signingKey  the derived key for the request's scope
 * @param amzDate     {@code x-amz-date}, e.g. {@code 20261008T132234Z}
 * @param scope       {@code date/region/s3/aws4_request}
 * @param signature   the request's own signature, the seed for chunk signatures
 * @param payloadHash {@code x-amz-content-sha256}: a hex SHA-256, {@code UNSIGNED-PAYLOAD} or a {@code STREAMING-*} mode
 */
public record AuthContext(String accessKey, byte[] signingKey, String amzDate, String scope, String signature, String payloadHash) {
}
