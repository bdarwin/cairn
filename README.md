# cairn

An S3-compatible object server in Java.

Single node first, done properly: buckets, objects with ranged reads, ListObjectsV2, multipart
upload, SigV4 and presigned URLs, written so a crash never leaves a torn object. The test of it is
that unmodified clients - the AWS SDK, the `aws` CLI, `mc` and DuckDB - work against it without
noticing.

Status: just started. Nothing serves requests yet; the probes that decided the design are in `docs/probes.md`.

Java 21. Apache 2.0.
