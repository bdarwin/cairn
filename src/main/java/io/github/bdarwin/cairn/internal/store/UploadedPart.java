package io.github.bdarwin.cairn.internal.store;

import java.time.Instant;

/** A part stored for an upload in progress. */
public record UploadedPart(PartInfo part, Instant lastModified) {
}
