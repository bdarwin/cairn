# cairn

An S3-compatible object server in Java.

Single node first, done properly: buckets, objects with ranged reads, ListObjectsV2, multipart
upload, SigV4 and presigned URLs, written so a crash never leaves a torn object. The test of it is
that unmodified clients - the AWS SDK, the `aws` CLI, `mc` and DuckDB - work against it without
noticing.

Status: buckets, objects, listing and multipart upload work end to end (PUT, GET with ranges, HEAD,
DELETE, DeleteObjects, ListObjectsV2, multipart, SigV4, checksums); `mc` and the `aws` CLI upload
5 GiB files; DuckDB writes and reads Parquet through `s3://`, fetching 0.34 % of a 197 MB file for
one column of one row group. Docs in `docs/`, found-and-not-done in `TODO.md`.

Java 21. Apache 2.0.
