# Changelog

## Unreleased

### Added

- Objects end to end: buckets (create, delete, head, list) and objects (PUT, GET with `Range`,
  HEAD, DELETE) over HTTP, on the JDK's `HttpServer` with virtual threads.
- SigV4 in the `Authorization` header, verified against the AWS documentation's examples and the
  AWS SDK's own signer; `aws-chunked` bodies with chunk signatures, with signed trailers, and with
  unsigned trailers.
- `Content-MD5` and `x-amz-checksum-*` (CRC32, CRC32C, CRC64NVME, SHA-1, SHA-256), as headers or
  trailers, checked before a write commits, stored, and returned with `x-amz-checksum-mode`.
- On-disk layout: one directory per object, escaped key segments, metadata as a versioned JSON line
  with small objects inline. Writes commit with a rename. `CrashTest` kills the server mid-write
  and at each step of the commit.
- `CairnServer` (embedding) and `Main` (command line).
- ListObjectsV2 and ListObjects: prefix, delimiter, max-keys, continuation token, start-after,
  marker, `encoding-type=url`, in UTF-8 key order. Large directories keep their sorted entries in
  memory, keyed by modification time; measured on 1,000,000 keys in `docs/listing.md`.
- Multipart upload: create, upload part, complete, abort, ListParts, ListMultipartUploads. S3's
  `md5-N` ETag; parts kept as hard-linked files rather than copied (measured: 0.22 s against 16.8 s
  to complete 5 GiB); full-object CRC checksums combined from the parts', composite ones for the
  rest. `mc` and the `aws` CLI upload 5 GiB files and read them back byte for byte.

- Maven project (`io.github.bdarwin:cairn`, Java 21, no runtime dependencies).
- Probes 1-5 with their measured results in `docs/probes.md`: `Expect: 100-continue` in the JDK
  `HttpServer`, the request bodies four S3 clients send on PUT, 5 GiB bodies through the JDK server,
  the cost of `fsync` on macOS, and the keys APFS cannot hold as they are. The probe programs and
  their output are in `examples/`.
