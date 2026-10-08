package io.github.bdarwin.cairn.internal.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An object's metadata as stored.
 *
 * @param key          the full key
 * @param size         bytes
 * @param etag         S3's ETag without quotes: MD5 hex, or for a multipart upload MD5-of-MD5s and {@code -N}
 * @param lastModified when it was written
 * @param contentType  stored {@code Content-Type}
 * @param userMetadata {@code x-amz-meta-*}, names in lower case
 * @param headers      other stored response headers
 * @param checksums    {@code x-amz-checksum-*} values by algorithm name
 * @param parts        the object's parts; one for a plain PUT
 */
public record ObjectInfo(String key, long size, String etag, Instant lastModified, String contentType,
                         Map<String, String> userMetadata, Map<String, String> headers, Map<String, String> checksums,
                         List<PartInfo> parts) {
}
