package io.github.bdarwin.cairn.internal.store;

/**
 * One part of an object's bytes, with its own checksum so it can be verified on its own.
 *
 * @param number   1-based part number
 * @param size     bytes
 * @param etag     MD5 of the part, hex
 * @param crc32c   CRC32C of the part, base64 of the big-endian value; always present
 * @param checksum the part's checksum in the algorithm its multipart upload declared, base64; null if none
 */
public record PartInfo(int number, long size, String etag, String crc32c, String checksum) {
}
