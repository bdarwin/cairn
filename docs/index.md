# cairn

An object server that speaks the S3 API, written in Java 21 with no runtime dependencies beyond the
JDK. It keeps objects on a local disk, one directory per object, with no database.

The goal is that unmodified S3 clients work against it without noticing: the AWS SDK for Java v2,
the `aws` CLI, MinIO's `mc`, and DuckDB's `httpfs`.

**Status:** buckets and single objects work end to end with SigV4: PUT, GET with ranges, HEAD and
DELETE. The AWS SDK for Java v2 and the `aws` CLI round-trip files byte for byte. Listing,
multipart upload and the rest are next. See [Getting started](getting-started.md).

- [Storage](storage.md): how objects sit on disk, and why a crash never tears one.
- [Probes](probes.md): what each unknown turned out to be on a real machine, and what that decided.
