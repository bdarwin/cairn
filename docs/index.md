# cairn

An object server that speaks the S3 API, written in Java 21 with no runtime dependencies beyond the
JDK. It keeps objects on a local disk, one directory per object, with no database.

The goal is that unmodified S3 clients work against it without noticing: the AWS SDK for Java v2,
the `aws` CLI, MinIO's `mc`, and DuckDB's `httpfs`.

**Status:** nothing serves requests yet. The groundwork is done: [Probes](probes.md) records what
each unknown turned out to be on a real machine, and what that decided about the design.
