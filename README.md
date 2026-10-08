# cairn

An S3-compatible object server in Java.

Single node first, done properly: buckets, objects with ranged reads, ListObjectsV2, multipart
upload, SigV4 and presigned URLs, written so a crash never leaves a torn object. The test of it is
that unmodified clients - the AWS SDK, the `aws` CLI, `mc` and DuckDB - work against it without
noticing.

Status: buckets and single objects work end to end (PUT, GET with ranges, HEAD, DELETE, SigV4,
checksums). Listing and multipart upload are next. Docs in `docs/`, found-and-not-done in `TODO.md`.

Java 21. Apache 2.0.
