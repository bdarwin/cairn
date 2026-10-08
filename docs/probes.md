# Probes

Before cairn depended on anything it had not seen work, each unknown got a small throwaway program
and a written result. The programs are in `examples/`, each with its real output pasted at the
bottom. The figures here are copied from those outputs. The machine was an Apple M1 Max, 32 GiB of
memory, the internal SSD, macOS 26.5 on APFS, and JDK 21.0.11, all on 2026-10-08. Other machines
and other disks will give other numbers.

## 1. `Expect: 100-continue`

**Question.** The AWS SDK sends `Expect: 100-continue` on PUT. Does the JDK's
`com.sun.net.httpserver.HttpServer` answer it, and when: before the handler runs, or only once the
handler reads the body?

**Probe.** `ProbeExpectContinue`: a raw socket client sends the request headers and waits up to 2 s
before sending the body. The handler sleeps 500 ms before reading the body.

**Result.**

- The server sends `HTTP/1.1 100 Continue` **as soon as it has parsed the headers, before the
  handler runs**. The client had it at 13 ms. The handler did not read the body until 518 ms. In
  the JDK source (`sun.net.httpserver.ServerImpl.Exchange.run`), the reply is written
  unconditionally before the filter chain runs.
- The handler cannot decline the body. A handler that answers 403 without reading still lets the
  client send it, because `100 Continue` has already gone out.
- The interim response carries `Content-Length: 0`. RFC 9110 says a 1xx response must not. The
  SDK, the `aws` CLI, `mc` and DuckDB all continued past it in probe 2.
- If the handler answers without reading the body, the server drains up to 64 KiB of it on close
  (`sun.net.httpserver.drainAmount`) and keeps the connection. With a 1,000-byte unread body, a
  second request on the same connection was served. With a 1,000,000-byte body the server closed
  the connection, and the client's next write failed with `Broken pipe`.

**Consequence.** The JDK server is good enough. cairn cannot save the client an upload by refusing
early, which costs bandwidth on bad requests and nothing else. When cairn rejects a request whose
body it has not read (bad signature, missing bucket), it closes the connection rather than leaving
the client mid-upload on a socket the server will drop. The connection is not reused anyway.

## 2. What clients send on PUT

**Probe.** `ProbeCaptureServer` is an in-memory recording stub. It answers just enough of S3 to let
a client finish an upload and a read, and logs every request with its headers and its body framing.
`scripts/capture-clients.sh` drives four clients at it. The full logs are in `examples/captures/`.

| Client | Version |
|---|---|
| AWS SDK for Java v2 (default Apache 5 client) | 2.55.12 |
| `aws` CLI | 2.37.10 |
| `mc` (minio-go 7.0.90) | RELEASE.2025-08-13T08-35-41Z |
| DuckDB `httpfs` | 1.5.6 |

**Result: four different PUT bodies.**

| Client | `x-amz-content-sha256` | Body on the wire | Checksum |
|---|---|---|---|
| SDK v2, default | `STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER` | `aws-chunked`, a signature per chunk, signed trailer | CRC32, in the trailer `x-amz-checksum-crc32` |
| SDK v2, `requestChecksumCalculation(WHEN_REQUIRED)` | `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` | `aws-chunked`, a signature per chunk, no trailer | none |
| `aws` CLI | hex SHA-256 of the body | plain | CRC64NVME, in the header `x-amz-checksum-crc64nvme` |
| `mc` | `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` | `aws-chunked`, a signature per chunk, no trailer | none |
| DuckDB | hex SHA-256 of the body | plain | none |

The SDK's default PUT looks like this (`captures/aws-sdk-java-v2.log`, request #1):

```
PUT /probe/sdk/bytes-10k
  Content-encoding: aws-chunked
  Content-length: 10536
  X-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER
  X-amz-decoded-content-length: 10240
  X-amz-sdk-checksum-algorithm: CRC32
  X-amz-trailer: x-amz-checksum-crc32
  body:
    2800;chunk-signature=4ddfec75...   (10240 bytes)
    0;chunk-signature=feec2ade...      (0 bytes)
    x-amz-checksum-crc32:9+yNBQ==
    x-amz-trailer-signature:dd6c98f9...
    <empty line>
```

Details the parser and signer have to get right:

- **The SDK signs the payload because the endpoint is plain HTTP.** It never sent
  `STREAMING-UNSIGNED-PAYLOAD-TRAILER` here, though that is the form it uses over HTTPS. cairn sits
  behind a TLS proxy, so the unsigned variant will arrive in practice too. It has to be supported,
  but this probe did not capture it.
- **`mc` sends `aws-chunked` framing without a `Content-Encoding: aws-chunked` header.** The only
  sign of it is `x-amz-content-sha256`. Detect chunked bodies from that header, not from
  `Content-Encoding`.
- **`mc` writes the `Authorization` header without spaces after the commas**
  (`Credential=...,SignedHeaders=...,Signature=...`). The SDK and the CLI put a space after each.
- **Chunk sizes:** the SDK uses 128 KiB chunks (`20000` hex), `mc` 64 KiB. The final chunk has size
  0. With a trailer, the trailer lines follow the zero chunk and end with an empty line.
- **Checksums arrive in three ways:** a CRC32 trailer (SDK), a CRC64NVME header (CLI), or none.
  CRC64NVME is not in the JDK, so cairn needs its own implementation.
- **`Expect: 100-continue`:** the SDK sends it on bodies of 1 MiB but not on 10 KiB. The CLI and
  DuckDB send it on every PUT, `mc` on none.
- **Multipart:** the CLI (8 MiB parts), `mc` (16 MiB parts) and DuckDB all use it. DuckDB wrote an
  8 MB Parquet file with a single PUT and a 132 MB file as two parts (80,213,616 and 52,416,842
  bytes). The CLI's `CreateMultipartUpload` declares `x-amz-checksum-algorithm: CRC64NVME`, but its
  `CompleteMultipartUpload` carries no checksums. `mc` sends part ETags unquoted in
  `CompleteMultipartUpload`; the others quote them.
- **Reads:** the CLI sends `x-amz-checksum-mode: ENABLED` on HEAD and GET. The SDK's `GetObject`
  sends `x-amz-te: append-md5` and `x-amz-checksum-crc32: AAAAAA==`, both of which a server that
  does not recognise them can ignore. DuckDB reads Parquet with `Range: bytes=a-b` GETs, the footer
  first.
- **Calls besides PUT and GET:** `mc cp` calls `GET ?location`, `GET ?object-lock`, and a
  `ListObjectsV2` with `max-keys=1` before uploading. DuckDB lists with `encoding-type=url` to
  expand a glob. Without a glob it uses `HEAD` for the size.

**Consequence.** One body decoder covers three framings: plain, `aws-chunked` with chunk
signatures, and `aws-chunked` with a trailer (signed or unsigned). The decoder streams, checks each
chunk signature as it goes, and computes MD5 and the declared CRC on the decoded bytes. Checksums:
CRC32, CRC32C and CRC64NVME, plus SHA-1 and SHA-256 for completeness. `GET ?location` and
`GET ?object-lock` need real answers for `mc`. Object lock is out of scope, so the answer is S3's
`ObjectLockConfigurationNotFoundError`.

## 3. Large bodies

**Question.** Can the JDK server on virtual threads stream a 5 GB PUT to disk and a 5 GB GET back
with a flat heap, at a throughput near a plain file copy on the same disk?

**Probe.** `ProbeLargeBody`, run with `-Xmx64m` so that buffering a body would fail. The handler
streams the body to a file through a 1 MiB buffer. `curl` is the client, running in its own
process. The baselines are `dd bs=1m` copying the same file on the same disk, and `dd` reading it
to `/dev/null`.

**Result** (5,120 MiB of random bytes):

| Operation | Time | Throughput | Server peak heap |
|---|---|---|---|
| PUT, filling a 1 MiB buffer before each write | 3.9 s | 1,326 MiB/s | 5 MiB |
| PUT, writing whatever each socket read returns | 6.5 s | 791 MiB/s | |
| PUT, body read and discarded (no disk) | 1.4 s | 3,540 MiB/s | |
| GET | 1.8 s | 2,829 MiB/s | 7 MiB |
| `dd` copy, same disk | 3.1 s | 1,671 MiB/s | |
| `dd` read to `/dev/null` | 0.8 s | 6,038 MiB/s | |

The stored file was byte-for-byte identical to the source.

- The heap stays flat: 5 MiB peak for a 5 GiB upload, under a 64 MiB limit.
- The first version of the probe wrote whatever each `read` returned. A socket read returns far
  less than 1 MiB, so that version issued many small writes and ran at about half the speed of a
  full-buffer copy. Discarding the body showed the network side was not the limit. Filling the
  buffer with `readNBytes` before each write made PUT about 1.7 times faster (1,326 against
  791 MiB/s) and brought it to 79 % of `dd`.
- Reads here come mostly from the page cache. The machine has 32 GiB and the file was just
  written, so the GET and `dd` read figures measure memory and loopback, not the SSD.

**Consequence.** Keep the JDK server. Copy bodies in full buffers.

## 4. The cost of `fsync`

**Question.** What does making a file durable cost on this machine, at 4 KiB, 1 MiB and 64 MiB?
It decides what small-object writes can do.

**Probe.** `ProbeFsync` times `FileChannel.force(true)`, then a whole durable object write: data
file written and forced, metadata file written and forced, both renamed into place, the directory
forced. `native/fsync_probe.c` times plain `fsync()` and `fcntl(F_FULLFSYNC)` for comparison.

**Result** (mean per file):

| Size | `fsync()` (C) | `F_FULLFSYNC` (C) | `force(true)` (Java) | Whole object write (Java) |
|---|---|---|---|---|
| 4 KiB | 0.032 ms | 5.0 ms | 4.7 ms | 15.2 ms |
| 1 MiB | 0.198 ms | 7.3 ms | 7.0 ms | 18.5 ms |
| 64 MiB | 1.45 ms | 17.7 ms | 18.2 ms | 41.3 ms |

- On macOS, plain `fsync()` does not flush the drive's write cache. `F_FULLFSYNC` does, and it
  costs about 5 ms even for 4 KiB.
- **Java's `force(true)` costs what `F_FULLFSYNC` costs**, on JDK 21.0.11 and on JDK 25.0.2. Both
  runs are pasted in the probe. So `force` is genuinely durable here. It is also the expensive
  kind.
- A whole object write forces three times (data, metadata, directory), about 15 ms for a small
  object. Storing a small object's data inside its metadata file removes one force: 10.4 ms.
- Concurrency hardly helps. 4 KiB object writes at 1, 4, 16 and 64 concurrent writers ran at 59,
  76, 97 and 105 objects/s. `F_FULLFSYNC` flushes the whole drive cache, so writers queue behind
  each other.

**Consequence.** On this machine a durable small PUT costs about 10-15 ms, and the server tops out
near 100 durable small writes a second. That is a property of the disk, and cairn should not hide
it. Small objects are stored inline in the metadata file. MinIO does the same in `xl.meta`; its
threshold is `smallFileThreshold`, 128 KiB. Whether to
offer an opt-out from forcing (fast, not crash-safe) is a decision for later and is not built.

## 5. Keys the filesystem cannot hold as they are

**Question.** S3 keys are case-sensitive byte strings: any valid UTF-8, up to 1,024 bytes. Which
ones break the naive mapping, key segment = directory name, on APFS?

**Probe.** `ProbeKeyNames` tries each case through `java.nio.file` on the default APFS volume,
which is case-insensitive.

**Result.**

| Case | What happens |
|---|---|
| Segment of 255 ASCII bytes | works |
| Segment of 256 ASCII bytes | `File name too long` |
| Segments of 255 `é` (510 bytes) or 255 `あ` (765 bytes) | work: the limit counts characters, not UTF-8 bytes |
| `.` or `..` as a segment | the directory already exists; `x/../y` means `y` |
| Trailing `/` (`x/`), or `//` (`x//y`) | `Path` drops the empty segment: `x/` is `x`, `x//y` is `x/y` |
| Control characters `\x01 \t \n \r \x7f` | work |
| NUL | `InvalidPathException` |
| `: \ * ?`, trailing space or dot, leading `-` or `.`, NBSP, emoji | work |
| `case-note`, then `Case-Note` | `exists` says yes; creating fails; the listing shows only `case-note` |
| `café` NFD, then `café` NFC | `exists` says yes; creating fails; the listing returns the bytes first written |
| Absolute path over 1,024 bytes | `File name too long` (the macOS `PATH_MAX`) |
| `SecureDirectoryStream` (`openat`-relative access, which would avoid `PATH_MAX`) | not available from Java on macOS |

Seven kinds of legal S3 key do not survive the naive mapping: case-only differences,
normalisation-only differences, `.` and `..`, empty segments (trailing `/` or `//`), long segments,
keys longer than the path budget, and NUL. MinIO answers most of these by rejecting the key. Its
`checkPathLength` refuses segments over 255 and, on macOS, paths over 1,016 bytes. Its
`IsValidObjectPrefix` refuses `//`, `.`, `..` and NUL. It also assumes a case-sensitive filesystem.
The brief asks that every legal key round-trip, so cairn encodes instead.

### The key-to-path encoding

Each segment of the key between `/` separators maps to one directory name:

1. **Literal characters** are `a-z`, `0-9`, `-`, `_`, `~`, and `.` anywhere except first.
2. **Every other byte** of the segment's UTF-8 is written as `%` and two upper-case hex digits.
   That covers upper-case letters (`Note` becomes `%4Eote`), a leading `.`, `%` itself, every
   non-ASCII byte, control characters and NUL.
3. **An empty segment** is written as a lone `%`.

The names this produces are pure ASCII, so normalisation cannot touch them. Every letter outside an
escape is lower-case and every escape is upper-case hex, so two different keys never fold to the
same name. No name is `.` or `..`, no name is empty, and no name starts with `.`. Names starting
with `.` are therefore free for cairn's own files (`.meta`, data files, temporary files), and
nothing the encoding produces can collide with them.

**Long keys.** A prefix gets a real directory only if each encoded segment fits in 240 bytes and the
encoded path from the bucket down stays within a fixed budget of 640 bytes. The budget is part of
the on-disk format. It does not depend on where the data directory is, so moving the data
directory never changes the layout. At startup cairn refuses a data directory whose own path is too
long to leave room for the budget. A key whose path does not fit is stored in an **overflow entry**
in the deepest directory that does fit. The entry is named `%h` followed by 32 lower-case hex
digits of the SHA-256 of the full key (`h` is not a hex digit, so no escape can produce the name),
and its metadata records the full key. Whether a prefix's directory fits depends only on the
prefix. So within one directory, any given first segment is either always a real directory or
always overflow. Overflow entries therefore sort correctly among their sibling directories when a
listing walk reads their keys from metadata. Only keys with very long or heavily escaped segments
pay this cost.

The encoding is tested exhaustively in milestone 2, and listing order in milestone 3.

## What the probes changed

- The JDK `HttpServer` stays: no probe found a case it cannot handle. The one thing to remember is
  that `100 Continue` goes out before cairn's code runs.
- The body decoder handles three framings, and chunked bodies are detected from
  `x-amz-content-sha256`.
- Bodies are copied in full 1 MiB buffers.
- Small objects are inlined into the metadata file to save a force.
- Keys are escaped to lower-case ASCII names, with an overflow entry for keys whose path is too
  long.
