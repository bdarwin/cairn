# quackjvm

[quackjvm](https://github.com/bdarwin/quackjvm) runs DuckDB inside a JVM program. Here it is a
client of cairn: objects go into a quackjvm table, the table goes to `s3://` on cairn as Parquet,
and quackjvm reads it back. It works, but only through raw SQL, and not at all once quackjvm's
hardening is on. The gaps are quackjvm's, written down below for it; cairn needed no change.

Everything here comes from `examples/.../QuackjvmOnCairn.java`. It uses `quackjvm-core` and
`quackjvm-agent` 1.6.1 from Maven Central (DuckDB 2.0.0), as a dependency of the examples
project only, never of cairn. Its output is pasted at the bottom of the file.

## What works

```java
try (Quack quack = Quack.inMemory()) {
    quack.createTable("reading", layout);
    quack.write("reading", layout, readings);                  // 1,000,000 records
    quack.execute("LOAD httpfs");
    quack.execute("CREATE SECRET (TYPE s3, KEY_ID '...', SECRET '...', ENDPOINT 'localhost:9000', URL_STYLE 'path', USE_SSL false)");
    quack.execute("COPY reading TO 's3://items/quackjvm/reading.parquet' (FORMAT parquet)");
    List<Reading> back = quack.query("SELECT * FROM read_parquet(?) WHERE id = ?", url, 123_456L).records(Reading.class);
}
```

| Step | Result |
|---|---|
| `execute("COPY reading TO 's3://...'")`, 1,000,000 records | a 9,481,492-byte file, written as 2 parts by multipart upload, in 0.21 s |
| `query("SELECT count(*), sum(value) FROM read_parquet('s3://.../*.parquet')").records(Total.class)` | right answer; 433,930 bytes read from cairn (4.6 % of the file) |
| `query(...).records(Reading.class)` for one id | the record equals the one written; 1,159,168 bytes read (12.2 %) |
| `inTransaction(tx -> tx.execute("COPY ... TO 's3://...'"))` | committed; the file is there |
| `MeasureTable.archive(connection, "s3://items/measures", part)` | returns `s3://items/measures/measure=readings/part=.../example-....parquet` |
| `measure.query().from(that file).rows(...).columns(...)` and `MeasureTable.restore(connection, that file)` | both answer from the file on cairn |
| `GuardedQuery.on(quack, readOnly()).run("SELECT ... FROM read_parquet('s3://...')")` | answers, on a database that is not hardened |

## Findings for quackjvm

Each finding names the call, what happened and what would be needed. None of them was changed from
this repository.

1. **No way to say where an object store is, except SQL with the keys in it.** quackjvm has no API
   for S3: the program has to `execute("LOAD httpfs")` and
   `execute("CREATE SECRET (TYPE s3, KEY_ID '...', SECRET '...', ENDPOINT ..., URL_STYLE 'path', USE_SSL false)")`
   itself, with the secret key in the statement's text. `Quack.builder(path).settings(properties)`
   cannot do it: DuckDB 2.0 has none of `s3_endpoint`, `s3_access_key_id`, `s3_secret_access_key`,
   `s3_url_style`, `s3_use_ssl` as settings (the example counts 0 of them in `duckdb_settings()`).
   Secrets are the only way.
   **Needed:** something like `Quack.builder(path).objectStore(endpoint, keyId, secret, pathStyle, ssl)`
   that loads httpfs and creates the secret at open, with the keys never in SQL text quackjvm keeps.
   Whether quackjvm's metrics or dashboard record the `CREATE SECRET` text was not checked.

2. **No call exports a table to a location.** `COPY reading TO 's3://...'` through `execute` works;
   `query("COPY ...")` is refused by design (`ReadCheck$Refused: Not run: this statement returns no
   rows ... Run it with execute()`). Only measure tables have a Parquet export
   (`MeasureTable.archive`, `Quack.export`).
   **Needed, if wanted:** `quack.export(table, location)` / `quack.read(location)`, or leave it to
   SQL and document `execute("COPY ...")` as the way.

3. **Hardening turns `s3://` off, and its allow-list cannot turn it back on.**
   - `Hardening.options().allowedFolders("s3://items/")` treats the URL as a local path. It comes
     back from `getAllowedFolders()` as `[<cwd>/s3:/items]`, and that is what goes into DuckDB's
     `allowed_directories`. The read that follows fails with `Permission Error: Cannot access file
     "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration`.
   - `Hardening.apply(quack)` after `LOAD httpfs` and `CREATE SECRET` fails the same way. That one
     is expected: it allows nothing.
   - DuckDB itself can do what is wanted. With quackjvm's own lockdown settings
     (`autoload_known_extensions` and `autoinstall_known_extensions` off,
     `enable_external_access = false`, `lock_configuration = true`) and
     `allowed_directories = ['s3://items/']` set as it is, DuckDB 2.0 reads `s3://items/...`, and
     refuses `s3://other/...` and `/etc/hosts` (section 4 of the example).

   **Needed:** `allowedFolders` and `allowedFiles` to pass URL prefixes (`s3://`, `https://`, ...)
   through unresolved. Also a way to load httpfs and create the secret before the lock, because
   afterwards `LOAD httpfs` is refused (`Hardening.openSandbox()` then `execute("LOAD httpfs")`:
   `Permission Error: Loading external extensions is disabled through configuration`).

4. **A `QuerySession` cannot reach `s3://` at all.** It is hardened from the start
   (`QuerySession.open(Hardening.Options, QueryPolicy)`), so it has finding 3's problem and no
   moment at which to load httpfs.
   - `session.register("remote", "SELECT * FROM read_parquet('s3://...')")` fails with
     `IllegalArgumentException: Could not register remote: Permission Error: ... file system
     operations are disabled by configuration`.
   - `session.run("SELECT ... FROM read_parquet('s3://...')")` fails the same way.
   - `register(Path)` takes only a local `Path`.

   **Needed:** the same as finding 3, through `QuerySession.open`, and a `register(name, URI)`.

5. **`GuardedQuery` with `QueryPolicy.readOnly()` does not stop network reads by itself.** On a
   database that is not hardened, it ran `read_parquet('s3://...')`. It refuses `COPY ... TO` and
   `CREATE SECRET` ("COPY is not a query, so it will not be run"). It allows
   `SELECT * FROM duckdb_secrets()`, whose result shows the secret as `redacted`. This is
   consistent with quackjvm's docs, which leave network access to `Hardening`. Worth knowing, since
   finding 3 means hardening and S3 cannot be had together today.

6. **Small API points met on the way.**
   - `Quack.query(...)` wraps DuckDB's error as `IllegalStateException: Failed to run: <sql>`.
     The reason (`Permission Error: ...`) is only in `getCause()`.
   - `query("SELECT NULL::VARCHAR").scalar(String.class)`, a result of one row holding NULL, throws
     `Query returned no rows, so it has no scalar value`. A `list(...)` over no rows gives such a
     row.

## What this means for cairn + quackjvm

For tables as Parquet on cairn and quackjvm nodes reading them, the data path works today. Writes go
by multipart upload, and reads fetch only the needed byte ranges ([DuckDB](duckdb.md)). Two things
stand between that and a system someone can run:

- quackjvm needs to know about an object store: endpoint and keys as configuration, not SQL text
  (finding 1).
- Its sandbox needs to allow-list `s3://` prefixes (findings 3 and 4), so that a query node serving
  SQL it did not write can read the lake and nothing else.

## Running it

```
mvn -q install -DskipTests && cd examples && mvn -q compile
java --enable-native-access=ALL-UNNAMED -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  io.github.bdarwin.cairn.examples.QuackjvmOnCairn /tmp/cairn-quackjvm
```
