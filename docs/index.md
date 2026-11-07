# cairn

An object server that speaks the S3 API, written in Java 21 with no runtime dependencies beyond the
JDK. It keeps objects on a local disk, one directory per object, with no database.

The goal is that unmodified S3 clients work against it without noticing: the AWS SDK for Java v2,
the `aws` CLI, MinIO's `mc`, and DuckDB's `httpfs`.

**Status:** buckets, objects (PUT, GET with ranges, HEAD, DELETE, DeleteObjects), listing and
multipart upload work end to end with SigV4. The AWS SDK for Java v2, the `aws` CLI and `mc`
round-trip files byte for byte, 5 GiB ones included. DuckDB writes and reads Parquet, CSV and JSON
through `s3://`, and a query on one column of one row group fetched 0.34 % of a 197 MB file. See
[Getting started](getting-started.md).

- [DuckDB](duckdb.md): `COPY ... TO 's3://...'` and `read_parquet`, what DuckDB sends, the bytes it
  reads, and the two bugs it found.
- [Storage](storage.md): how objects sit on disk, and why a crash never tears one.
- [Listing](listing.md): S3's key order from a directory walk, and a million keys measured.
- [Multipart upload](multipart.md): parts kept as they are, and 5 GiB through `mc` and the `aws` CLI.
- [Probes](probes.md): what each unknown turned out to be on a real machine, and what that decided.
