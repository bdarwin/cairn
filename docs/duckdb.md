# DuckDB

DuckDB's `httpfs` extension writes and reads Parquet, CSV and JSON on cairn unmodified, and reads
a Parquet file by byte range, fetching little more than the columns and row groups a query needs.
Getting there found two bugs in cairn, both fixed with a test that failed first.

## Connecting

```sql
LOAD httpfs;   -- INSTALL httpfs first, once, if it is not installed
CREATE SECRET (TYPE s3, KEY_ID 'example-key', SECRET 'example-secret',
               ENDPOINT 'localhost:9000', URL_STYLE 'path', USE_SSL false);

COPY (SELECT range AS id, 'note ' || range AS note FROM range(1000)) TO 's3://items/x.parquet';
SELECT count(*), max(note) FROM read_parquet('s3://items/*.parquet');
```

`URL_STYLE 'path'` is required: cairn serves path-style addressing only. DuckDB 2.0 has no
`s3_endpoint`-style settings any more; the secret is the only way to point it at cairn.

## What works

`DuckDbTest` runs DuckDB 2.0.0 in process through its JDBC driver (`org.duckdb:duckdb_jdbc`
2.0.0.0, a test dependency) against a cairn server, and the `duckdb` 1.5 command-line program as
well when it is installed. Each line below is a test that passes:

| | |
|---|---|
| `COPY ... TO 's3://...'`, then `read_parquet` of the file and of a `*.parquet` glob | |
| A file big enough that DuckDB writes it by multipart upload | the ETag has S3's `md5-N` form, and no upload is left open |
| Globs: `*.parquet`, `dir/*.parquet`, `dir/*/*.parquet`, `dir*/*.parquet`, `**/*.parquet`, `glob('...**')` | `dir-x/` and `dir/` kept apart; an empty match is DuckDB's "No files found" |
| A glob over 1,100 files | the listing comes back in two pages, joined by a continuation token |
| `PARTITION_BY` writes, `hive_partitioning` reads | keys hold `=`, which DuckDB escapes in the URL |
| `PARTITION_BY ... OVERWRITE` | DuckDB deletes the old files with one DeleteObjects request |
| Keys with a space, `+`, `=`, `&`, `,`, `;`, `~` and `'` | |
| CSV and JSON, written and read | |
| `ATTACH 's3://.../items.duckdb' (READ_ONLY)` | a DuckDB database file read by range |
| A missing file | DuckDB reports the 404 |
| The `duckdb` 1.5 CLI writes by multipart upload and reads back | |

## What DuckDB sends

- **Writes:** `HEAD` of the key, twice, then one `PUT`, or `POST ?uploads`, the parts, and
  `POST ?uploadId=` to complete. DuckDB signs the payload (`x-amz-content-sha256` is the body's
  SHA-256) and sends no checksum header. It switches to multipart above a part size of
  `s3_uploader_max_filesize / s3_uploader_max_parts_per_file`. DuckDB 2.0's defaults make that much
  smaller than 1.5's: `DuckDbReadsWhatItNeeds` below wrote 196,825,774 bytes as 20 parts, where
  probe 2 saw DuckDB 1.5.6 write 132 MB as two parts.
- **Globs:** `ListObjectsV2` with `encoding-type=url` and the prefix up to the first wildcard, no
  delimiter, so `**` costs no more than `*`. It matches the keys itself.
- **Reads:** `HEAD` for the size, a ranged `GET` of the file's tail (footer and metadata), then one
  ranged `GET` per column chunk it needs, several at once.
- **Deletes:** only for `OVERWRITE`: a `POST ?delete` with `Content-MD5`, `<Quiet>true</Quiet>`
  and every key under the target.

## DuckDB reads only what it needs

`examples/.../DuckDbReadsWhatItNeeds.java` writes 4,000,000 rows of five columns through DuckDB
(`ROW_GROUP_SIZE 100000`). It then runs four queries, each on a fresh in-memory database so nothing
is cached between them. A pass-through proxy between DuckDB and cairn (`CountingProxy`) counts the
body bytes cairn served for each.

The file: 196,825,774 bytes, 40 row groups. Row group 7's `id` column chunk is 401,629 bytes
compressed, and the footer is 23,511 bytes.

| Query | Requests | Bytes served | Of the file |
|---|---|---|---|
| every column, every row group | 121 | 196,825,774 | 100.00 % |
| one column (`reading`), every row group | 42 | 19,710,638 | 10.01 % |
| one column (`id`), one row group (7) | 3 | 668,334 | 0.34 % |
| `count(*)`: the footer only | 2 | 262,830 | 0.13 % |

For one column of one row group, DuckDB made three requests:

```
HEAD /items/readings.parquet -> 200, 0 bytes
GET  /items/readings.parquet Range: bytes=196562944-196825773 -> 206, 262830 bytes
GET  /items/readings.parquet Range: bytes=34545664-34951167 -> 206, 405504 bytes
```

The column chunk is 401,629 bytes, and DuckDB asked for 405,504. Its first read takes the file's
last 262,830 bytes, more than the 23,511-byte footer, before it knows where anything is. Row-group
pruning works from the footer's statistics. DuckDB rounds row groups up to a multiple of its 2,048-row vectors (100,352 rows here,
not 100,000), so the example takes row group 7's bounds from `parquet_metadata` rather than
computing them.

## Bugs it found

### DeleteObjects was refused

`COPY ... (PARTITION_BY (part), OVERWRITE)` failed with `HTTP 405 Method Not Allowed`. DuckDB
deletes the old files first with DeleteObjects (`POST /bucket?delete`), which cairn did not serve.
It does now:

- up to 1,000 keys;
- quiet and verbose replies;
- a key that is not there counts as deleted, as in S3.

The body is checked like a PUT's: the signed SHA-256, `Content-MD5` and `x-amz-checksum-*`.
S3 insists on `Content-MD5` or a checksum header; cairn checks one when it is sent and does not
insist. Tests: `DuckDbTest.overwritingPartitionsDeletesTheOldFiles`,
`ObjectsSdkTest.deleteObjectsInOneRequest`.

Writing it showed that `CompleteMultipartUpload` took its XML body without checking it against
the signed `x-amz-content-sha256`. It checks it now
(`RawRequestsTest.xmlBodiesAreCheckedAgainstTheirSignature`).

### A server that froze

Measuring through the proxy, a full scan sometimes hung until DuckDB's timeout (`http_timeout`,
30 s by default). A loop of scans, each on a fresh database, hung through the proxy and never
without it. A thread dump explained it:

- More of cairn's request threads than the machine has cores were blocked writing response
  bodies, each **pinned** to its carrier thread (`VirtualThread.parkOnCarrierThread`).
- Up to JDK 23, a virtual thread that blocks inside a `synchronized` block cannot unmount
  (JEP 491 removed this in JDK 24). The JDK `HttpServer` writes bodies inside one
  (`sun.net.httpserver.Request$WriteStream.write`; `-Djdk.tracePinnedThreads` names it).
- Once as many downloads as there are cores are blocked on a slow reader, every carrier is held.
  No virtual thread in the JVM runs again until one of those readers reads.
- The proxy shared cairn's JVM, so it was starved too, and stopped reading: a deadlock. DuckDB
  alone only made it rarer. Any ten clients that read slowly, or stop, would stall cairn for
  everyone.

`ProbeStalledDownloads` shows it on the bare JDK server. It opens downloads that never read, then
times one more small request:

| Stalled downloads (10 cores) | JDK 21, virtual threads | JDK 21, platform threads | JDK 25, virtual threads |
|---|---|---|---|
| 5 | 17.1 ms | 8.1 ms | 13.1 ms |
| 9 | 7.8 ms | 8.2 ms | 4.7 ms |
| 10 | no answer after 5 s | 7.6 ms | 7.6 ms |
| 20 | no answer after 5 s | 1.8 ms | 3.2 ms |

**The fix:** cairn runs requests on virtual threads on JDK 24 and later, and on platform threads
(one per request, as before) on JDK 21 to 23. `SlowReadersTest` holds twice as many stalled
downloads as there are cores and needs another client to be served within 10 s. It failed on JDK
21 before the fix and passes on 21 and 25 after it. The loop of scans through the proxy no longer
hangs.

`ObjectsEndToEnd` was run again on JDK 21 with platform threads. The results are in the same range
as before: 1 GiB PUT 342 against 318 MiB/s, GET 988 against 874 MiB/s; 1 MiB GET 233 against
319 MiB/s; 154 against 161 small PUTs a second from 16 clients. Both outputs are at the bottom of
the example.

## Running it

```
mvn test -Dtest=DuckDbTest                         # downloads httpfs on first use
mvn -q install -DskipTests && cd examples && mvn -q compile
java --enable-native-access=ALL-UNNAMED -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  io.github.bdarwin.cairn.examples.DuckDbReadsWhatItNeeds /tmp/cairn-duckdb
```

`DuckDbTest` skips itself if the httpfs extension can be neither loaded nor installed, for example
with no network and no cached copy.
