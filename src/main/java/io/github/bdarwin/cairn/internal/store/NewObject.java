package io.github.bdarwin.cairn.internal.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a writer supplies besides the bytes.
 *
 * @param contentType  the {@code Content-Type} to store
 * @param userMetadata {@code x-amz-meta-*} headers, names in lower case
 * @param headers      other stored response headers ({@code Cache-Control}, {@code Content-Disposition}, ...)
 * @param checksums    {@code x-amz-checksum-*} values by algorithm name; may be filled in by the commit check
 */
public record NewObject(String contentType, Map<String, String> userMetadata, Map<String, String> headers, Map<String, String> checksums) {

    public NewObject {
        userMetadata = new LinkedHashMap<>(userMetadata);
        headers = new LinkedHashMap<>(headers);
        checksums = new LinkedHashMap<>(checksums);
    }
}
