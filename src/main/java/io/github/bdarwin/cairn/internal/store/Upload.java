package io.github.bdarwin.cairn.internal.store;

import java.time.Instant;

/**
 * A multipart upload in progress.
 *
 * @param id                hex upload id
 * @param key               the object it will become
 * @param initiated         when it was created
 * @param attributes        content type and metadata for the object
 * @param checksumAlgorithm the declared {@code x-amz-checksum-algorithm} (an algorithm name), or null
 * @param checksumType      {@code FULL_OBJECT} or {@code COMPOSITE} when an algorithm is declared
 */
public record Upload(String id, String key, Instant initiated, NewObject attributes, String checksumAlgorithm, String checksumType) {
}
