# Storage

The filesystem is the namespace and the source of truth. There is no database and no index, and
nothing is held in memory that a restart could not rebuild from the files.

## Layout

Each object is a directory named after its key. The directory holds a metadata file and, unless the
object is small, a data file:

```
data/
  .cairn/format.json            layout version
  items/                        bucket "items"
    .bucket                     bucket metadata
    notes/                      prefix "notes/"
      a/                        object "notes/a"
        .meta                   metadata (and the bytes, when under 128 KiB)
        .data-3f0c...           the bytes, when 128 KiB or more
        b/                      object "notes/a/b"
          .meta
```

Because an object is a directory, the keys `a` and `a/b` can both exist. A file-per-key layout cannot
hold both. MinIO lays objects out the same way, as a directory holding `xl.meta`, with small objects
stored inside it.

`.meta` is one line of JSON, a newline, then the object's bytes if they are inline. It records a
format number, so a reader that did not write it can tell what it holds:

```
{"format":1,"key":"notes/a","size":5,"etag":"5d41402abc4b2a76b9719d911017c592","lastModified":1791462000000,
 "contentType":"text/plain","meta":{"x-amz-meta-kind":"reading"},"headers":{},"checksums":{"CRC32":"NhCmhg=="},
 "parts":[{"number":1,"size":5,"etag":"5d41402abc4b2a76b9719d911017c592","crc32c":"mnG7TA=="}],"data":{"inline":5}}
hello
```

Every object records its parts, each with its own CRC32C. A plain PUT has one part. A completed
multipart upload keeps one data file per part (see [Multipart upload](multipart.md)), and
`"data"` lists them: `{"files":[".data-…-1",".data-…-2"]}`. The per-part
checksum is there so that a later layout spread over several disks can check each piece on its own.

## Writes

1. Stream the body to a new `.data-<uuid>` file, filling a 1 MiB buffer before each write. Objects
   under 128 KiB stay in memory instead.
2. Force the data file to disk.
3. Check the request's integrity: length, the signed SHA-256, `Content-MD5`, `x-amz-checksum-*`. On
   failure, delete the new file. Nothing has changed.
4. Write the new `.meta` to a temporary file and force it.
5. Rename it over `.meta`. **The rename is the commit.**
6. Force the directory, then delete the previous version's data file.

A crash before step 5 leaves the previous object. A crash after it leaves the new one. Either way
the object is whole. `CrashTest` proves this by killing a server process while it streams a body,
and at each step above, then restarting on the same directory and reading the object back. A killed
process leaves the page cache to the kernel. So the test proves the order of the steps; survival of
a power loss also rests on the forces, whose cost is in [Probes](probes.md#4-the-cost-of-fsync).

A crash can leave a stray `.data-*` or `.meta-*.tmp` file behind. These are never read, and nothing
removes them yet (see `TODO.md`).

Readers open the data file named by `.meta`. An open file stays readable after an overwrite or
delete unlinks it, so a GET racing a PUT returns one whole version.

## Keys and paths

S3 keys are case-sensitive byte strings. APFS by default folds case and Unicode normalisation,
cannot name a file `.` or `..` or the empty string, and limits names to 255 characters and paths to
1,024 bytes. Each key segment is therefore escaped into a lower-case ASCII name. The rules, and the
probe results behind them, are in [Probes](probes.md#the-key-to-path-encoding). Some examples:

| Key | Directories |
|---|---|
| `notes/a` | `notes/a` |
| `Notes/A` | `%4Eotes/%41` |
| `a/` | `a/%` |
| `a//b` | `a/%/b` |
| `./x` | `%2E/x` |
| `café` | `caf%C3%A9` |

A key whose escaped path would exceed 640 bytes, or that has a segment over 240 bytes once
escaped, is kept in an overflow entry. The entry is named `%h` plus 32 hex digits of its SHA-256 and
sits in the deepest directory that fits; its full key is in its `.meta`. To keep every path under
the 1,024-byte limit, the data directory's own path may be at most 200 bytes.

## What it costs

Measured with the AWS SDK for Java v2 on an M1 Max with an internal SSD
(`examples/.../ObjectsEndToEnd.java`):

| Object | PUT (mean) | GET (mean) |
|---|---|---|
| 1 KiB | 28.2 ms | 3.5 ms |
| 1 MiB | 35.7 ms | 3.1 ms |
| 64 MiB | 230 ms (279 MiB/s) | 56 ms (1,138 MiB/s) |
| 1 GiB | 3.2 s (318 MiB/s) | 1.2 s (874 MiB/s) |

A small PUT is slow because it is durable. On macOS a force is `F_FULLFSYNC`, about 5 ms, and a
new object directory needs three of them: its parent, its metadata, and itself. 1 KiB PUTs reached
49 a second from one client and 161 a second from 16.

A large PUT runs at about a quarter of what the bare HTTP server streamed in probe 3 (1,326 MiB/s).
The SDK signs every 128 KiB chunk, and cairn verifies those signatures and computes MD5, CRC32C and
the request's own checksum on the same thread. Where the time goes has not been measured yet.
