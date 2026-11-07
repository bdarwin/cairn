package io.github.bdarwin.cairn.examples;

import io.github.bdarwin.cairn.CairnServer;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.session.QuerySession;
import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.measure.MeasureBatch;
import io.quackjvm.core.measure.MeasureTable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.LongStream;

/**
 * Milestone 6, acceptance test 3: quackjvm (io.github.bdarwin:quackjvm-core and quackjvm-agent
 * 1.6.1, from Maven Central) as a client of cairn. quackjvm stores objects in a DuckDB table, the
 * table goes to an {@code s3://} location on cairn as Parquet, and quackjvm reads it back. Every
 * route quackjvm offers is tried in turn, and each attempt prints what happened, or the exception
 * it threw, as it is. The findings are written up in docs/quackjvm.md.
 *
 * <p>Run: {@code java --enable-native-access=ALL-UNNAMED -cp ... QuackjvmOnCairn <empty dir>}.
 */
public final class QuackjvmOnCairn {

    static final String KEY = "example-key", SECRET = "example-secret";
    static String secretSql;

    /** One reading of an item: the first component is the table's key. */
    public record Reading(Long id, Integer item, Double value, String note) {
    }

    public record Total(Long rows, Double sum) {
    }

    public static void main(String[] args) throws Exception {
        Path data = Files.createDirectories(Path.of(args[0]));
        try (CairnServer server = CairnServer.builder(data).bindAddress("127.0.0.1").port(0).credentials(KEY, SECRET).start();
             CountingProxy proxy = new CountingProxy(server.port());
             S3Client s3 = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + server.port())).region(Region.US_EAST_1)
                     .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(KEY, SECRET))).build()) {
            s3.createBucket(b -> b.bucket("items"));
            secretSql = "CREATE SECRET (TYPE s3, KEY_ID '" + KEY + "', SECRET '" + SECRET + "', ENDPOINT '127.0.0.1:" + proxy.port()
                    + "', URL_STYLE 'path', USE_SSL false)";
            String url = "s3://items/quackjvm/reading.parquet";
            ColumnarLayout<Reading> layout = ColumnarLayout.ofRecord(Reading.class);
            List<Reading> readings = LongStream.range(0, 1_000_000)
                    .mapToObj(i -> new Reading(i, (int) (i % 1000), (i % 977) / 4.0, "note " + i)).toList();

            System.out.println("quackjvm-core 1.6.1 from Maven Central, java " + System.getProperty("java.version"));
            System.out.println();
            System.out.println("1. Objects into a table, the table to s3:// on cairn, and back");
            try (Quack quack = Quack.inMemory()) {
                System.out.println("   DuckDB " + quack.query("SELECT version()").scalar(String.class));
                attempt("createTable + write 1,000,000 records", () -> {
                    quack.createTable("reading", layout);
                    return quack.write("reading", layout, readings) + " written";
                });
                attempt("execute(\"LOAD httpfs\")", () -> quack.execute("LOAD httpfs"));
                attempt("DuckDB settings for an S3 endpoint or keys, which Quack.builder().settings(...) could set", () ->
                        quack.query("SELECT count(*) FROM duckdb_settings() WHERE name IN ('s3_endpoint', 's3_access_key_id', 's3_secret_access_key', 's3_url_style', 's3_use_ssl')").scalar(Long.class));
                attempt("query(\"SELECT NULL::VARCHAR\").scalar(String.class): one row, holding NULL", () -> quack.query("SELECT NULL::VARCHAR").scalar(String.class));
                attempt("execute(\"CREATE SECRET (TYPE s3, ... ENDPOINT cairn ...)\")", () -> quack.execute(secretSql));
                attempt("query(\"COPY reading TO 's3://...'\")", () -> quack.query("COPY reading TO '" + url + "' (FORMAT parquet)").count());
                proxy.drain();
                attempt("execute(\"COPY reading TO '" + url + "' (FORMAT parquet)\")", () -> {
                    long t = System.nanoTime();
                    int n = quack.execute("COPY reading TO '" + url + "' (FORMAT parquet)");
                    return n + ", " + summary(proxy.drain()) + String.format(", %.2f s", (System.nanoTime() - t) / 1e9);
                });
                attempt("query(sum over read_parquet('s3://.../*.parquet')).records(Total.class)", () -> {
                    Total total = quack.query("SELECT count(*), sum(value) FROM read_parquet('s3://items/quackjvm/*.parquet')").records(Total.class).getFirst();
                    return total + ", " + summary(proxy.drain());
                });
                attempt("query(one row from s3).records(Reading.class), equal to what was written", () -> {
                    Reading back = quack.query("SELECT * FROM read_parquet(?) WHERE id = ?", url, 123_456L).records(Reading.class).getFirst();
                    return back + ", equal: " + back.equals(readings.get(123_456)) + ", " + summary(proxy.drain());
                });
                attempt("in a transaction: COPY from Tx.execute", () -> {
                    quack.inTransaction(tx -> tx.execute("COPY (SELECT * FROM reading WHERE item = 7) TO 's3://items/quackjvm/item-7.parquet'"));
                    return "committed; " + quack.query("SELECT count(*) FROM 's3://items/quackjvm/item-7.parquet'").scalar(Long.class) + " rows in the file";
                });
                proxy.drain();
            }

            System.out.println();
            System.out.println("2. A measure table archived to s3:// and queried from there");
            String archived;
            MeasureTable measure = MeasureTable.named("readings").fields("item", "point").order("point", "a", "b", "c").writtenBy("example").build();
            try (Quack quack = Quack.inMemory()) {
                quack.execute("LOAD httpfs");
                quack.execute(secretSql);
                MeasureBatch.Builder batch = measure.batch();
                for (long r = 0; r < 20_000; r++) {
                    batch.record(r);
                    for (String point : List.of("a", "b", "c")) batch.put(r % 7 + point.charAt(0) - 'a', "item" + (r % 10), point);
                }
                quack.create(measure);
                try (Connection c = quack.connection()) {
                    measure.append(c, batch.build(), "2026-11-07");
                }
                proxy.drain();
                archived = attempt("archive(connection, \"s3://items/measures\", \"2026-11-07\")", () -> {
                    try (Connection c = quack.connection()) {
                        return measure.archive(c, "s3://items/measures", "2026-11-07");
                    }
                });
                if (archived != null) System.out.println("     " + summary(proxy.drain()));
            }
            if (archived != null) {
                try (Quack fresh = Quack.inMemory()) {
                    fresh.execute("LOAD httpfs");
                    fresh.execute(secretSql);
                    proxy.drain();
                    attempt("query().from(the s3:// file).rows(\"item\").columns(\"point\"), first row", () -> {
                        try (Connection c = fresh.connection()) {
                            var rows = measure.query().from(archived).rows("item").columns("point").run(c);
                            StringBuilder sb = new StringBuilder();
                            rows.forEachRow(r -> {
                                if (sb.isEmpty()) sb.append(java.util.Arrays.toString(r.toArray()));
                            });
                            return sb + ", " + summary(proxy.drain());
                        }
                    });
                    attempt("MeasureTable.restore(connection, the s3:// file), then valueCount", () -> {
                        try (Connection c = fresh.connection()) {
                            MeasureTable restored = MeasureTable.restore(c, archived);
                            return restored.getName() + ": " + restored.valueCount(c) + " values, " + summary(proxy.drain());
                        }
                    });
                }
            }

            System.out.println();
            System.out.println("3. quackjvm-agent: hardening, guarded queries, query sessions");
            try (Quack quack = Quack.inMemory()) {
                quack.execute("LOAD httpfs");
                quack.execute(secretSql);
                attempt("GuardedQuery.on(quack, readOnly()).run(read_parquet('s3://...'))", () ->
                        text(GuardedQuery.on(quack, QueryPolicy.readOnly()).run("SELECT count(*) FROM read_parquet('" + url + "')")));
                GuardedQuery guard = GuardedQuery.on(quack, QueryPolicy.readOnly());
                for (String sql : List.of("CREATE SECRET (TYPE s3, KEY_ID 'k', SECRET 's')", "SELECT * FROM duckdb_secrets()",
                        "COPY reading TO 's3://items/elsewhere.parquet'")) {
                    attempt("guard.whyRejected(\"" + sql + "\")", () -> String.valueOf(guard.whyRejected(sql)));
                }
                attempt("guard.run(\"SELECT * FROM duckdb_secrets()\"): does the secret key come back?", () -> {
                    String all = guard.run("SELECT * FROM duckdb_secrets()").toText();
                    return all.contains(SECRET) + (all.contains("redacted") ? " (the result says 'redacted')" : "");
                });
                attempt("Hardening.options().allowedFolders(\"s3://items/\").getAllowedFolders()", () ->
                        Hardening.options().allowedFolders("s3://items/").getAllowedFolders().toString().replace(System.getProperty("user.dir"), "<cwd>"));
                attempt("Hardening.options().allowedFolders(\"s3://items/\")", () -> {
                    Hardening.apply(quack, Hardening.options().allowedFolders("s3://items/"));
                    return "applied";
                });
                attempt("  then read_parquet('" + url + "')", () -> quack.query("SELECT count(*) FROM read_parquet('" + url + "')").scalar(Long.class));
            }
            try (Quack quack = Quack.inMemory()) {
                quack.execute("LOAD httpfs");
                quack.execute(secretSql);
                attempt("Hardening.apply(quack) after LOAD httpfs and CREATE SECRET", () -> {
                    Hardening.apply(quack);
                    return "applied";
                });
                attempt("  then read_parquet('" + url + "')", () -> quack.query("SELECT count(*) FROM read_parquet('" + url + "')").scalar(Long.class));
            }
            try (Quack sandbox = Hardening.openSandbox()) {
                attempt("Hardening.openSandbox(), then execute(\"LOAD httpfs\")", () -> sandbox.execute("LOAD httpfs"));
            }
            try (QuerySession session = QuerySession.open(Hardening.options(), QueryPolicy.readOnly())) {
                attempt("QuerySession.register(\"remote\", \"SELECT * FROM read_parquet('s3://...')\")", () -> {
                    session.register("remote", "SELECT * FROM read_parquet('" + url + "')");
                    return "registered";
                });
                attempt("  then run(\"SELECT count(*) FROM remote\")", () -> text(session.run("SELECT count(*) FROM remote")));
                attempt("QuerySession.run(read_parquet('s3://...'))", () -> text(session.run("SELECT count(*) FROM read_parquet('" + url + "')")));
            }

            System.out.println();
            System.out.println("4. What DuckDB itself allows: quackjvm's lockdown settings, with s3://items/ allowed as it is");
            try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
                for (String sql : List.of("LOAD httpfs", secretSql, "SET allowed_directories = ['s3://items/']",
                        "SET autoload_known_extensions = false", "SET autoinstall_known_extensions = false",
                        "SET enable_external_access = false", "SET lock_configuration = true")) {
                    try (var st = c.createStatement()) {
                        st.execute(sql);
                    }
                }
                for (String sql : List.of("SELECT count(*) FROM read_parquet('" + url + "')",
                        "SELECT count(*) FROM read_parquet('s3://other/reading.parquet')",
                        "SELECT count(*) FROM read_csv('/etc/hosts')")) {
                    attempt(sql, () -> {
                        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
                            rs.next();
                            return rs.getLong(1);
                        }
                    });
                }
            }
            proxy.drain();

            System.out.println();
            System.out.println("objects on cairn:");
            for (S3Object o : s3.listObjectsV2Paginator(b -> b.bucket("items")).contents()) {
                System.out.printf("   %-70s %,12d bytes  ETag %s%n", o.key(), o.size(), o.eTag());
            }
        }
    }

    static String summary(List<CountingProxy.Exchange> exchanges) {
        long up = exchanges.stream().mapToLong(CountingProxy.Exchange::requestBody).sum();
        long down = exchanges.stream().mapToLong(CountingProxy.Exchange::responseBody).sum();
        long parts = exchanges.stream().filter(e -> e.target().contains("partNumber=")).count();
        return String.format("%d requests to cairn (%s%,d bytes up, %,d down)", exchanges.size(), parts > 0 ? parts + " parts, " : "", up, down);
    }

    static String text(GuardedResult r) {
        return r.toText().strip().replace('\n', ' ');
    }

    static <T> T attempt(String label, Callable<T> work) {
        try {
            T result = work.call();
            System.out.println("   ok      " + label + "\n           -> " + result);
            return result;
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                List<String> lines = String.valueOf(t.getMessage()).strip().lines().filter(l -> !l.isBlank()).limit(3).toList();
                sb.append(t == e ? "" : "\n              caused by ").append(t.getClass().getName()).append(": ").append(String.join(" / ", lines));
            }
            System.out.println("   FAILED  " + label + "\n           -> " + sb);
            return null;
        }
    }
}

/*
Output (2026-11-07, Apple M1 Max, JDK 21.0.11, quackjvm-core and quackjvm-agent 1.6.1 from Maven Central; java ... QuackjvmOnCairn <dir>):

   quackjvm-core 1.6.1 from Maven Central, java 21.0.11

   1. Objects into a table, the table to s3:// on cairn, and back
      DuckDB v2.0.0
      ok      createTable + write 1,000,000 records
              -> 1000000 written
      ok      execute("LOAD httpfs")
              -> 0
      ok      DuckDB settings for an S3 endpoint or keys, which Quack.builder().settings(...) could set
              -> 0
      FAILED  query("SELECT NULL::VARCHAR").scalar(String.class): one row, holding NULL
              -> java.lang.IllegalStateException: Query returned no rows, so it has no scalar value: SELECT NULL::VARCHAR
      ok      execute("CREATE SECRET (TYPE s3, ... ENDPOINT cairn ...)")
              -> 0
      FAILED  query("COPY reading TO 's3://...'")
              -> io.quackjvm.core.internal.ReadCheck$Refused: Not run: this statement returns no rows, and a query only reads. Run it with execute(): COPY reading TO 's3://items/quackjvm/reading.parquet' (FORMAT parquet)
      ok      execute("COPY reading TO 's3://items/quackjvm/reading.parquet' (FORMAT parquet)")
              -> 1000000, 6 requests to cairn (2 parts, 9,481,763 bytes up, 578 down), 0.21 s
      ok      query(sum over read_parquet('s3://.../*.parquet')).records(Total.class)
              -> Total[rows=1000000, sum=1.21970376E8], 11 requests to cairn (0 bytes up, 433,930 down)
      ok      query(one row from s3).records(Reading.class), equal to what was written
              -> Reading[id=123456, item=456, value=88.5, note=note 123456], equal: true, 2 requests to cairn (0 bytes up, 1,159,168 down)
      ok      in a transaction: COPY from Tx.execute
              -> committed; 1000 rows in the file

   2. A measure table archived to s3:// and queried from there
      ok      archive(connection, "s3://items/measures", "2026-11-07")
              -> s3://items/measures/measure=readings/part=2026-11-07/example-1794066565864.parquet
        3 requests to cairn (156,026 bytes up, 0 down)
      ok      query().from(the s3:// file).rows("item").columns("point"), first row
              -> [item0, 6001.0, 8001.0, 10001.0], 8 requests to cairn (0 bytes up, 156,026 down)
      ok      MeasureTable.restore(connection, the s3:// file), then valueCount
              -> readings: 60000 values, 4 requests to cairn (0 bytes up, 0 down)

   3. quackjvm-agent: hardening, guarded queries, query sessions
      ok      GuardedQuery.on(quack, readOnly()).run(read_parquet('s3://...'))
              -> count_star() 1000000
      ok      guard.whyRejected("CREATE SECRET (TYPE s3, KEY_ID 'k', SECRET 's')")
              -> CREATE is not a query, so it will not be run. Only statements that read are allowed: SELECT, WITH, FROM, TABLE, VALUES, DESCRIBE, SUMMARIZE, UNPIVOT and EXPLAIN. DuckDB's parser said: Only SELECT statements can be serialized to json!
      ok      guard.whyRejected("SELECT * FROM duckdb_secrets()")
              -> null
      ok      guard.whyRejected("COPY reading TO 's3://items/elsewhere.parquet'")
              -> COPY is not a query, so it will not be run. Only statements that read are allowed: SELECT, WITH, FROM, TABLE, VALUES, DESCRIBE, SUMMARIZE, UNPIVOT and EXPLAIN. DuckDB's parser said: Only SELECT statements can be serialized to json!
      ok      guard.run("SELECT * FROM duckdb_secrets()"): does the secret key come back?
              -> false (the result says 'redacted')
      ok      Hardening.options().allowedFolders("s3://items/").getAllowedFolders()
              -> [<cwd>/s3:/items]
      ok      Hardening.options().allowedFolders("s3://items/")
              -> applied
      FAILED    then read_parquet('s3://items/quackjvm/reading.parquet')
              -> java.lang.IllegalStateException: Failed to run: SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet')
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet') /                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      ok      Hardening.apply(quack) after LOAD httpfs and CREATE SECRET
              -> applied
      FAILED    then read_parquet('s3://items/quackjvm/reading.parquet')
              -> java.lang.IllegalStateException: Failed to run: SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet')
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet') /                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      FAILED  Hardening.openSandbox(), then execute("LOAD httpfs")
              -> java.lang.IllegalStateException: Failed to execute: LOAD httpfs
                 caused by java.sql.SQLException: Permission Error: Loading external extensions is disabled through configuration
      FAILED  QuerySession.register("remote", "SELECT * FROM read_parquet('s3://...')")
              -> java.lang.IllegalArgumentException: Could not register remote: Permission Error: Cannot access file "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration / LINE 1: CREATE OR REPLACE VIEW "remote" AS SELECT * FROM read_parquet('s3://items/quackjvm/reading.parquet') /                                                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                 caused by java.lang.IllegalStateException: Failed to execute: CREATE OR REPLACE VIEW "remote" AS SELECT * FROM read_parquet('s3://items/quackjvm/reading.parquet')
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration / LINE 1: CREATE OR REPLACE VIEW "remote" AS SELECT * FROM read_parquet('s3://items/quackjvm/reading.parquet') /                                                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      FAILED    then run("SELECT count(*) FROM remote")
              -> io.quackjvm.agent.guard.QueryFailed: Catalog Error: Table with name remote does not exist! / Did you mean "pragma_database_list"? / LINE 1: SELECT count(*) FROM remote
                 caused by java.sql.SQLException: Catalog Error: Table with name remote does not exist! / Did you mean "pragma_database_list"? / LINE 2: SELECT count(*) FROM remote
      FAILED  QuerySession.run(read_parquet('s3://...'))
              -> io.quackjvm.agent.guard.QueryFailed: Permission Error: Cannot access file "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet') /                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://items/quackjvm/reading.parquet" - file system operations are disabled by configuration / LINE 2: SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet') /                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

   4. What DuckDB itself allows: quackjvm's lockdown settings, with s3://items/ allowed as it is
      ok      SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet')
              -> 1000000
      FAILED  SELECT count(*) FROM read_parquet('s3://other/reading.parquet')
              -> java.sql.SQLException: Permission Error: Cannot access file "s3://other/reading.parquet" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_parquet('s3://other/reading.parquet') /                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      FAILED  SELECT count(*) FROM read_csv('/etc/hosts')
              -> java.sql.SQLException: Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_csv('/etc/hosts') /                              ^^^^^^^^^^^^^^^^^^^^^^

   objects on cairn:
      measures/measure=readings/part=2026-11-07/example-1794066565864.parquet      156,026 bytes  ETag "8c9e51f6d675988fd946aa897f4767be"
      quackjvm/item-7.parquet                                                      14,458 bytes  ETag "8b5e0f94ca68c219696799bc229cc354"
      quackjvm/reading.parquet                                                  9,481,492 bytes  ETag "56a70cc4738eb3a2ea97ed7fd4e8b137-2"
*/
