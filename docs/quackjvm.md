# quackjvm

[quackjvm](https://github.com/bdarwin/quackjvm) runs DuckDB inside a JVM program. Here it is a
client of cairn: objects go into a quackjvm table, the table goes to `s3://` on cairn as Parquet,
and quackjvm reads it back, from a plain database, a hardened one, and a query session. cairn
needed no change.

Everything here comes from `examples/.../QuackjvmOnCairn.java`. It uses `quackjvm-core` and
`quackjvm-agent` 1.7.0 from Maven Central (DuckDB 2.0.0), as a dependency of the examples
project only, never of cairn. Its output is pasted at the bottom of the file. Milestone 6 first ran
it on 1.6.1 and wrote six findings for quackjvm; 1.7.0 resolved findings 1-4 and 6.

## What works

```java
ObjectStore store = ObjectStore.s3("http://127.0.0.1:9000").keys(keyId, secret).scope("s3://items/");

try (Quack quack = Quack.builder(null).objectStore(store).open()) {
    quack.createTable("reading", layout);
    quack.write("reading", layout, readings);                            // 1,000,000 records
    long rows = quack.exportTable("reading", "s3://items/quackjvm/reading.parquet");
    List<Reading> back = quack.query("SELECT * FROM read_parquet(?) WHERE id = ?", url, 123_456L).records(Reading.class);
}
```

| Step | Result |
|---|---|
| `exportTable("reading", "s3://...")`, 1,000,000 records | returns 1,000,000; a 9,481,492-byte file, written as 2 parts by multipart upload, in 0.22 s |
| `query("SELECT count(*), sum(value) FROM read_parquet('s3://.../*.parquet')").records(Total.class)` | right answer; 433,930 bytes read from cairn (4.6 % of the file) |
| `query(...).records(Reading.class)` for one id | the record equals the one written; 1,159,168 bytes read (12.2 %) |
| `inTransaction(tx -> tx.execute("COPY (SELECT ... WHERE item = 7) TO 's3://...'"))` | committed; 1,000 rows in the file. SQL stays the way for a filtered or partitioned export |
| `MeasureTable.archive(connection, "s3://items/measures", part)` | 156,026 bytes up; returns `s3://items/measures/measure=readings/part=.../example-....parquet` |
| `measure.query().from(that file).rows(...).columns(...)` and `MeasureTable.restore(connection, that file)` | both answer from the file on cairn |
| a hardened `Quack` and a `QuerySession`, `s3://items/` allowed | read `s3://items/`; refuse `s3://other/`, `s3://items2/` and `/etc/hosts` without a request to cairn |
| `GuardedQuery.on(quack, readOnly()).run("SELECT ... FROM read_parquet('s3://...')")` | answers, on a database that is not hardened |

Opening a database with a store sends nothing to cairn; the first query does.

## The findings, on 1.7.0

1. **Resolved: an object store is configuration, not SQL text.** `ObjectStore.s3(url).keys(id,
   secret).scope(prefix)` and `Quack.builder(...).objectStore(store)` load httpfs and create the
   secret when the database opens, on the database's own connection rather than through the pool.
   `store.toString()` is `ObjectStore[http://127.0.0.1:..., key example-key, for s3://items/]`, with
   no secret; `duckdb_secrets()` shows it `redacted`. The example no longer runs `LOAD httpfs` or
   `CREATE SECRET` anywhere.

2. **Resolved: `quack.exportTable(table, location)`** writes one Parquet file and returns the rows
   written (1,000,000 above). Anything else, such as a filtered or partitioned `COPY`, is
   `execute("COPY ...")` as before, which quackjvm documents.

3. **Resolved: hardening allows `s3://` prefixes.**
   `Hardening.options().allowedFolders("s3://items/")` keeps the URL as written
   (`getAllowedFolders()` is `[s3://items/]`; on 1.6.1 it was `[<cwd>/s3:/items]`), and
   `.objectStore(store)` puts the store on the options:

   ```java
   Hardening.Options options = Hardening.options().allowedFolders("s3://items/").objectStore(store);
   Quack.Builder builder = Quack.builder(null).settings(options.properties());
   options.getObjectStores().forEach(builder::objectStore);
   try (Quack hardened = builder.open()) { ... }
   ```

   After open, `allowed_directories=[<cwd>/.tmp/, 's3://items/']`, `enable_external_access=false`,
   `lock_configuration=true` and `autoload_known_extensions=false`: the store is set up first and
   the lock comes last. The database reads `s3://items/...` and refuses `s3://other/a.csv`,
   `s3://items2/a.csv` (a neighbour sharing the prefix) and `/etc/hosts` with `Permission Error`,
   sending no request to cairn. `SET enable_external_access = true` is refused: the configuration is
   locked.

4. **Resolved: a `QuerySession` reaches `s3://`.**
   `QuerySession.open(options, QueryPolicy.readOnly())` with the options above, then
   `session.register("reading", "SELECT * FROM read_parquet('s3://items/quackjvm/reading.parquet')")`
   registers (one HEAD and one GET to cairn), and `run("SELECT count(*), sum(value) FROM reading")`
   answers from 413,696 bytes (4.4 % of the file). `run` over `s3://other/`, `s3://items2/` or
   `/etc/hosts` is refused, and so is `register("elsewhere", "SELECT * FROM read_csv('s3://other/a.csv')")`.

5. **Unchanged, by design: `GuardedQuery` with `QueryPolicy.readOnly()` does not stop network reads
   by itself.** On a database that is not hardened it still runs `read_parquet('s3://...')`, and
   refuses `COPY ... TO`. quackjvm leaves network access to `Hardening`, and since finding 3 the two
   can be had together.

6. **Resolved, with one gap left.**
   - A failed query says why in its message: `Failed to run: SELECT count(*) FROM
     read_parquet('s3://items/missing.parquet') / HTTP Error: HTTP HEAD error checking ... (HTTP 404
     Not Found)`. On a hardened database the prefix is `Failed to run SQL:`, otherwise the same.
   - `scalar()` of one row holding NULL returns null (`SELECT sum(value) FROM reading WHERE id < 0`).
   - **Still open:** `query(...).count()` failing says only `Failed to count the rows of: <sql>`;
     the reason is in `getCause()` alone (section 6 of the example).

## An allowed folder is not a boundary; the keys are

DuckDB 2.0.0 checks a location against `allowed_directories` as written, then collapses `..` before
it sends the request. quackjvm measured and documents this. Against cairn, with `s3://items/`
allowed:

| Location | Allow-list | What cairn received | Result |
|---|---|---|---|
| `s3://items/../other/a.csv` | passed | `HEAD /other/a.csv`, `GET /other/a.csv` | 403 `SignatureDoesNotMatch` |
| `'s3://items/' \|\| chr(46) \|\| chr(46) \|\| '/other/a.csv'`, built as the query runs | passed | the same | 403 `SignatureDoesNotMatch` |
| `s3://items/%2e%2e/other/a.csv` | passed | `HEAD /items/%252e%252e/other/a.csv` | 404: the `%` is sent encoded, and cairn takes it as part of the key |

The same, from a hardened `Quack` and from a `QuerySession`. So the allow-list let the read go to
another bucket, and only the signature stopped it. Section 6 of the example shows why: against a
stub that records the request, DuckDB sends `HEAD /other/a.csv`, but its signature, recomputed with
cairn's own SigV4 code, is over `/items/../other/a.csv`. SigV4 for S3 does not normalise the path,
so cairn computes the signature over the path it received and refuses.

That is luck, not a boundary. It holds only while DuckDB signs one path and sends another, and it
does not hold for a server that skips the check or for a bucket read without keys. **The boundary is
the keys**: a database that runs SQL it did not write (a query session, an agent's queries) should
hold keys that cairn lets read only what that SQL may read. cairn cannot do that yet. Its keys are
static and every key can do everything in every bucket (`TODO.md`).

## What this means for cairn + quackjvm

The data path works with quackjvm's own API: a store as configuration, `exportTable` by multipart
upload, reads that fetch only the byte ranges they need ([DuckDB](duckdb.md)), and a hardened query
node that reads the lake and refuses other locations. What is missing for running SQL from
someone else is on cairn's side: keys limited to buckets, or prefixes, and to reading.

## Running it

```
mvn -q install -DskipTests && cd examples && mvn -q compile
java --enable-native-access=ALL-UNNAMED -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  io.github.bdarwin.cairn.examples.QuackjvmOnCairn /tmp/cairn-quackjvm
```
