package io.github.bdarwin.cairn.internal.store;

/**
 * A part as named in CompleteMultipartUpload.
 *
 * @param number   part number
 * @param etag     the ETag the client got for it, without quotes
 * @param checksum the checksum the client sent for it in the upload's algorithm, or null
 */
public record CompletedPart(int number, String etag, String checksum) {
}
