package io.github.bdarwin.cairn.internal;

/** An S3 error: the code and status a client maps to its own exception. */
public final class S3Exception extends RuntimeException {

    private final String code;
    private final int status;
    private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

    public S3Exception(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    /** Headers to send with the error response, such as {@code Content-Range} on a 416. */
    public java.util.Map<String, String> headers() {
        return headers;
    }

    public S3Exception withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public static S3Exception accessDenied(String message) {
        return new S3Exception(403, "AccessDenied", message);
    }

    public static S3Exception signatureDoesNotMatch() {
        return new S3Exception(403, "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided. Check your key and signing method.");
    }

    public static S3Exception invalidAccessKeyId() {
        return new S3Exception(403, "InvalidAccessKeyId", "The AWS Access Key Id you provided does not exist in our records.");
    }

    public static S3Exception authorizationMalformed(String message) {
        return new S3Exception(400, "AuthorizationHeaderMalformed", message);
    }

    public static S3Exception invalidArgument(String message) {
        return new S3Exception(400, "InvalidArgument", message);
    }

    public static S3Exception invalidRequest(String message) {
        return new S3Exception(400, "InvalidRequest", message);
    }

    public static S3Exception badDigest(String message) {
        return new S3Exception(400, "BadDigest", message);
    }

    public static S3Exception incompleteBody() {
        return new S3Exception(400, "IncompleteBody", "You did not provide the number of bytes specified by the Content-Length HTTP header.");
    }

    public static S3Exception noSuchBucket() {
        return new S3Exception(404, "NoSuchBucket", "The specified bucket does not exist");
    }

    public static S3Exception noSuchKey() {
        return new S3Exception(404, "NoSuchKey", "The specified key does not exist.");
    }

    public static S3Exception notImplemented(String what) {
        return new S3Exception(501, "NotImplemented", "A header or query you provided implies functionality that is not implemented: " + what);
    }

    public static S3Exception internal(String message) {
        return new S3Exception(500, "InternalError", message);
    }
}
