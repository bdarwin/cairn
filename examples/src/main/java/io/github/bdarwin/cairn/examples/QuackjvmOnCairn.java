package io.github.bdarwin.cairn.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bdarwin.cairn.CairnServer;
import io.github.bdarwin.cairn.internal.auth.SigV4;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.session.QuerySession;
import io.quackjvm.core.ObjectStore;
import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.measure.MeasureBatch;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sql.Rows;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Milestone 6, acceptance test 3, on quackjvm 1.7.0 (io.github.bdarwin:quackjvm-core and
 * quackjvm-agent, from Maven Central): quackjvm as a client of cairn. An {@link ObjectStore} names
 * cairn and its keys; a table goes to {@code s3://} on cairn as Parquet and is read back as records;
 * a hardened database and a query session read the allowed bucket and are refused on others. A
 * location with {@code ..} in it passes the allow-list and goes to another bucket; section 6 shows why
 * cairn still refused it (DuckDB signs the path as written and sends it collapsed). Each attempt
 * prints what happened, or the exception it threw, as it is. The findings are in docs/quackjvm.md.
 *
 * <p>Run: {@code java --enable-native-access=ALL-UNNAMED -cp ... QuackjvmOnCairn <empty dir>}.
 */
public final class QuackjvmOnCairn {

    static final String KEY = "example-key", SECRET = "example-secret";

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
            for (String bucket : List.of("items", "items2", "other")) s3.createBucket(b -> b.bucket(bucket));
            s3.putObject(b -> b.bucket("other").key("a.csv"), RequestBody.fromString("who,what\nother,not for this database\n"));
            s3.putObject(b -> b.bucket("items2").key("a.csv"), RequestBody.fromString("who,what\nitems2,a neighbour\n"));

            ObjectStore store = ObjectStore.s3("http://127.0.0.1:" + proxy.port()).keys(KEY, SECRET).scope("s3://items/");
            String url = "s3://items/quackjvm/reading.parquet";
            ColumnarLayout<Reading> layout = ColumnarLayout.ofRecord(Reading.class);
            List<Reading> readings = LongStream.range(0, 1_000_000)
                    .mapToObj(i -> new Reading(i, (int) (i % 1000), (i % 977) / 4.0, "note " + i)).toList();

            System.out.println("quackjvm-core and quackjvm-agent 1.7.0 from Maven Central, java " + System.getProperty("java.version"));
            System.out.println();
            System.out.println("1. Quack.builder(null).objectStore(store): a table to s3:// on cairn, and back as records");
            attempt("store.toString()", store::toString);
            try (Quack quack = Quack.builder(null).objectStore(store).open()) {
                System.out.println("   DuckDB " + quack.query("SELECT version()").scalar(String.class) + ", opened: " + summary(proxy.drain()));
                attempt("createTable + write 1,000,000 records", () -> {
                    quack.createTable("reading", layout);
                    return quack.write("reading", layout, readings) + " written";
                });
                attempt("exportTable(\"reading\", \"" + url + "\")", () -> {
                    long t = System.nanoTime();
                    long n = quack.exportTable("reading", url);
                    return n + " rows, " + summary(proxy.drain()) + String.format(", %.2f s", (System.nanoTime() - t) / 1e9);
                });
                attempt("query(sum over read_parquet('s3://items/quackjvm/*.parquet')).records(Total.class)", () -> {
                    Total total = quack.query("SELECT count(*), sum(value) FROM read_parquet('s3://items/quackjvm/*.parquet')").records(Total.class).getFirst();
                    return total + ", " + summary(proxy.drain());
                });
                attempt("query(\"SELECT * FROM read_parquet(?) WHERE id = ?\", url, 123_456L).records(Reading.class)", () -> {
                    Reading back = quack.query("SELECT * FROM read_parquet(?) WHERE id = ?", url, 123_456L).records(Reading.class).getFirst();
                    return back + ", equal to what was written: " + back.equals(readings.get(123_456)) + ", " + summary(proxy.drain());
                });
                attempt("SQL for what exportTable does not do: COPY (SELECT ... WHERE item = 7) TO 's3://...' in a transaction", () -> {
                    quack.inTransaction(tx -> tx.execute("COPY (SELECT * FROM reading WHERE item = 7) TO 's3://items/quackjvm/item-7.parquet'"));
                    return "committed; " + quack.query("SELECT count(*) FROM 's3://items/quackjvm/item-7.parquet'").scalar(Long.class) + " rows in the file";
                });
                attempt("query(\"SELECT * FROM duckdb_secrets()\"): does the secret key come back?", () -> {
                    String all = rows(quack.query("SELECT * FROM duckdb_secrets()"));
                    return all.contains(SECRET) + (all.contains("redacted") ? " (the result says 'redacted')" : "");
                });
                attempt("query(\"SELECT sum(value) FROM reading WHERE id < 0\").scalar(Double.class): one row, holding NULL",
                        () -> quack.query("SELECT sum(value) FROM reading WHERE id < 0").scalar(Double.class));
                attempt("a missing object: the message says why", () -> quack.query("SELECT count(*) FROM read_parquet('s3://items/missing.parquet')").scalar(Long.class));
                proxy.drain();
            }

            System.out.println();
            System.out.println("2. A measure table archived to s3:// and queried from there");
            String archived;
            MeasureTable measure = MeasureTable.named("readings").fields("item", "point").order("point", "a", "b", "c").writtenBy("example").build();
            try (Quack quack = Quack.builder(null).objectStore(store).open()) {
                MeasureBatch.Builder batch = measure.batch();
                for (long r = 0; r < 20_000; r++) {
                    batch.record(r);
                    for (String point : List.of("a", "b", "c")) batch.put(r % 7 + point.charAt(0) - 'a', "item" + (r % 10), point);
                }
                quack.create(measure);
                try (Connection c = quack.connection()) {
                    measure.append(c, batch.build(), "2026-11-08");
                }
                proxy.drain();
                archived = attempt("archive(connection, \"s3://items/measures\", \"2026-11-08\")", () -> {
                    try (Connection c = quack.connection()) {
                        return measure.archive(c, "s3://items/measures", "2026-11-08");
                    }
                });
                if (archived != null) System.out.println("     " + summary(proxy.drain()));
            }
            if (archived != null) {
                try (Quack fresh = Quack.builder(null).objectStore(store).open()) {
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

            Hardening.Options options = Hardening.options().allowedFolders("s3://items/").objectStore(store);
            List<String> reads = List.of(
                    "SELECT count(*) FROM read_parquet('" + url + "')",
                    "SELECT * FROM read_csv('s3://other/a.csv')",
                    "SELECT * FROM read_csv('s3://items2/a.csv')",
                    "SELECT count(*) FROM read_csv('/etc/hosts')",
                    "SELECT * FROM read_csv('s3://items/../other/a.csv')",
                    "SELECT * FROM read_csv('s3://items/' || chr(46) || chr(46) || '/other/a.csv')",
                    "SELECT * FROM read_csv('s3://items/%2e%2e/other/a.csv')");

            System.out.println();
            System.out.println("3. A hardened Quack: Hardening.options().allowedFolders(\"s3://items/\").objectStore(store)");
            attempt("options.getAllowedFolders()", options::getAllowedFolders);
            Quack.Builder builder = Quack.builder(null).settings(options.properties());
            options.getObjectStores().forEach(builder::objectStore);
            proxy.drain();
            try (Quack hardened = builder.open()) {
                System.out.println("   opened: " + summary(proxy.drain()));
                attempt("settings after open", () -> hardened.query("SELECT name || '=' || value FROM duckdb_settings() WHERE name IN "
                        + "('enable_external_access', 'lock_configuration', 'allowed_directories', 'autoload_known_extensions') ORDER BY name")
                        .list(String.class).toString().replace(System.getProperty("user.dir"), "<cwd>"));
                for (String sql : reads) {
                    attempt(sql, () -> rows(hardened.query(sql)));
                    System.out.println("           " + requests(proxy.drain()));
                }
                attempt("execute(\"SET enable_external_access = true\")", () -> hardened.execute("SET enable_external_access = true"));
            }

            System.out.println();
            System.out.println("4. QuerySession.open(options, QueryPolicy.readOnly())");
            try (QuerySession session = QuerySession.open(options, QueryPolicy.readOnly())) {
                proxy.drain();
                attempt("register(\"reading\", \"SELECT * FROM read_parquet('" + url + "')\")", () -> {
                    session.register("reading", "SELECT * FROM read_parquet('" + url + "')");
                    return "registered, " + requests(proxy.drain());
                });
                attempt("run(\"SELECT count(*), sum(value) FROM reading\")", () -> text(session.run("SELECT count(*), sum(value) FROM reading")) + ", " + summary(proxy.drain()));
                for (String sql : reads.subList(1, 6)) {
                    attempt("run(\"" + sql + "\")", () -> text(session.run(sql)));
                    System.out.println("           " + requests(proxy.drain()));
                }
                attempt("register(\"elsewhere\", \"SELECT * FROM read_csv('s3://other/a.csv')\")", () -> {
                    session.register("elsewhere", "SELECT * FROM read_csv('s3://other/a.csv')");
                    return "registered";
                });
            }

            System.out.println();
            System.out.println("5. GuardedQuery with readOnly() on a database that is not hardened");
            try (Quack quack = Quack.builder(null).objectStore(store).open()) {
                GuardedQuery guard = GuardedQuery.on(quack, QueryPolicy.readOnly());
                attempt("run(\"SELECT count(*) FROM read_parquet('" + url + "')\")", () -> text(guard.run("SELECT count(*) FROM read_parquet('" + url + "')")));
                attempt("whyRejected(\"COPY (SELECT 1) TO 's3://items/elsewhere.parquet'\")", () -> String.valueOf(guard.whyRejected("COPY (SELECT 1) TO 's3://items/elsewhere.parquet'")));
            }
            proxy.drain();

            System.out.println();
            System.out.println("6. Why cairn refused the .. reads: the path DuckDB signs, and the path it sends");
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            List<HttpExchange> seen = new CopyOnWriteArrayList<>();
            stub.createContext("/", ex -> {
                seen.add(ex);
                ex.sendResponseHeaders(404, -1);
                ex.close();
            });
            stub.start();
            try (Quack quack = Quack.builder(null).objectStore(ObjectStore.s3("http://127.0.0.1:" + stub.getAddress().getPort())
                    .keys(KEY, SECRET).scope("s3://items/")).open()) {
                attempt("read_csv('s3://items/../other/a.csv') against a stub that records the request", () ->
                        quack.query("SELECT * FROM read_csv('s3://items/../other/a.csv')").count());
            } finally {
                stub.stop(0);
            }
            HttpExchange first = seen.getFirst();
            String sent = first.getRequestURI().getRawPath();
            for (String path : List.of(sent, "/items/../other/a.csv")) {
                System.out.println("   " + first.getRequestMethod() + " sent as " + sent + "; signature computed over " + path
                        + " equals the one sent: " + signedOver(first, path));
            }

            System.out.println();
            System.out.println("objects on cairn:");
            for (String bucket : List.of("items", "items2", "other")) {
                for (S3Object o : s3.listObjectsV2Paginator(b -> b.bucket(bucket)).contents()) {
                    System.out.printf("   %-78s %,12d bytes  ETag %s%n", bucket + "/" + o.key(), o.size(), o.eTag());
                }
            }
        }
    }

    static String summary(List<CountingProxy.Exchange> exchanges) {
        long up = exchanges.stream().mapToLong(CountingProxy.Exchange::requestBody).sum();
        long down = exchanges.stream().mapToLong(CountingProxy.Exchange::responseBody).sum();
        long parts = exchanges.stream().filter(e -> e.target().contains("partNumber=")).count();
        return String.format("%d requests to cairn (%s%,d bytes up, %,d down)", exchanges.size(), parts > 0 ? parts + " parts, " : "", up, down);
    }

    /** The requests themselves, for the reads where which object was asked for is the point. */
    static String requests(List<CountingProxy.Exchange> exchanges) {
        if (exchanges.isEmpty()) return "no request to cairn";
        return "cairn saw " + exchanges.stream().map(e -> e.method() + " " + e.target() + " " + e.status()).distinct().collect(Collectors.joining("; "));
    }

    static String rows(Rows rows) {
        return rows.stream().map(r -> java.util.Arrays.stream(r.toArray()).map(String::valueOf).collect(Collectors.joining(",")))
                .collect(Collectors.joining("; ", "[", "]"));
    }

    /** Recomputes the request's SigV4 signature, with cairn's own helpers, as if its path were {@code path}. */
    static boolean signedOver(HttpExchange ex, String path) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String credential = auth.replaceAll(".*Credential=([^,]+),.*", "$1");
        String signedHeaders = auth.replaceAll(".*SignedHeaders=([^,]+),.*", "$1");
        String signature = auth.replaceAll(".*Signature=(\\p{XDigit}+).*", "$1");
        String[] c = credential.split("/", -1);
        StringBuilder canonical = new StringBuilder(ex.getRequestMethod()).append('\n').append(path).append("\n\n");
        for (String h : signedHeaders.split(";")) canonical.append(h).append(':').append(SigV4.canonicalHeaderValue(ex.getRequestHeaders().getFirst(h))).append('\n');
        canonical.append('\n').append(signedHeaders).append('\n').append(ex.getRequestHeaders().getFirst("x-amz-content-sha256"));
        String amzDate = ex.getRequestHeaders().getFirst("x-amz-date");
        byte[] key = SigV4.signingKey(SECRET, c[1], c[2], c[3]);
        String scope = c[1] + "/" + c[2] + "/" + c[3] + "/" + c[4];
        return SigV4.hex(SigV4.hmac(key, SigV4.stringToSign(amzDate, scope, canonical.toString()))).equals(signature);
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
Output (2026-11-08, Apple M1 Max, JDK 25.0.5, quackjvm-core and quackjvm-agent 1.7.0 from Maven Central, DuckDB 2.0.0; java ... QuackjvmOnCairn <dir>):

   quackjvm-core and quackjvm-agent 1.7.0 from Maven Central, java 25.0.5.0.3

   1. Quack.builder(null).objectStore(store): a table to s3:// on cairn, and back as records
      ok      store.toString()
              -> ObjectStore[http://127.0.0.1:54418, key example-key, for s3://items/]
      DuckDB v2.0.0, opened: 0 requests to cairn (0 bytes up, 0 down)
      ok      createTable + write 1,000,000 records
              -> 1000000 written
      ok      exportTable("reading", "s3://items/quackjvm/reading.parquet")
              -> 1000000 rows, 6 requests to cairn (2 parts, 9,481,763 bytes up, 578 down), 0.23 s
      ok      query(sum over read_parquet('s3://items/quackjvm/*.parquet')).records(Total.class)
              -> Total[rows=1000000, sum=1.21970376E8], 11 requests to cairn (0 bytes up, 433,930 down)
      ok      query("SELECT * FROM read_parquet(?) WHERE id = ?", url, 123_456L).records(Reading.class)
              -> Reading[id=123456, item=456, value=88.5, note=note 123456], equal to what was written: true, 2 requests to cairn (0 bytes up, 1,159,168 down)
      ok      SQL for what exportTable does not do: COPY (SELECT ... WHERE item = 7) TO 's3://...' in a transaction
              -> committed; 1000 rows in the file
      ok      query("SELECT * FROM duckdb_secrets()"): does the secret key come back?
              -> false (the result says 'redacted')
      ok      query("SELECT sum(value) FROM reading WHERE id < 0").scalar(Double.class): one row, holding NULL
              -> null
      FAILED  a missing object: the message says why
              -> java.lang.IllegalStateException: Failed to run: SELECT count(*) FROM read_parquet('s3://items/missing.parquet') / HTTP Error: HTTP HEAD error checking 's3://items/missing.parquet' (HTTP 404 Not Found)
                 caused by java.sql.SQLException: HTTP Error: HTTP HEAD error checking 's3://items/missing.parquet' (HTTP 404 Not Found) / LINE 1: SELECT count(*) FROM read_parquet('s3://items/missing.parquet') /                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

   2. A measure table archived to s3:// and queried from there
      ok      archive(connection, "s3://items/measures", "2026-11-08")
              -> s3://items/measures/measure=readings/part=2026-11-08/example-1794069501334.parquet
        3 requests to cairn (156,026 bytes up, 0 down)
      ok      query().from(the s3:// file).rows("item").columns("point"), first row
              -> [item0, 6001.0, 8001.0, 10001.0], 8 requests to cairn (0 bytes up, 156,026 down)
      ok      MeasureTable.restore(connection, the s3:// file), then valueCount
              -> readings: 60000 values, 4 requests to cairn (0 bytes up, 0 down)

   3. A hardened Quack: Hardening.options().allowedFolders("s3://items/").objectStore(store)
      ok      options.getAllowedFolders()
              -> [s3://items/]
      opened: 0 requests to cairn (0 bytes up, 0 down)
      ok      settings after open
              -> [allowed_directories=[<cwd>/.tmp/, 's3://items/'], autoload_known_extensions=false, enable_external_access=false, lock_configuration=true]
      ok      SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet')
              -> [1000000]
              cairn saw HEAD /items/quackjvm/reading.parquet 200; GET /items/quackjvm/reading.parquet 206
      FAILED  SELECT * FROM read_csv('s3://other/a.csv')
              -> java.lang.IllegalStateException: Failed to run SQL: SELECT * FROM read_csv('s3://other/a.csv') / Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration / LINE 1: SELECT * FROM read_csv('s3://other/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
              no request to cairn
      FAILED  SELECT * FROM read_csv('s3://items2/a.csv')
              -> java.lang.IllegalStateException: Failed to run SQL: SELECT * FROM read_csv('s3://items2/a.csv') / Permission Error: Cannot access file "s3://items2/a.csv" - file system operations are disabled by configuration
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://items2/a.csv" - file system operations are disabled by configuration / LINE 1: SELECT * FROM read_csv('s3://items2/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
              no request to cairn
      FAILED  SELECT count(*) FROM read_csv('/etc/hosts')
              -> java.lang.IllegalStateException: Failed to run SQL: SELECT count(*) FROM read_csv('/etc/hosts') / Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration
                 caused by java.sql.SQLException: Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_csv('/etc/hosts') /                              ^^^^^^^^^^^^^^^^^^^^^^
              no request to cairn
      FAILED  SELECT * FROM read_csv('s3://items/../other/a.csv')
              -> java.lang.IllegalStateException: Failed to run SQL: SELECT * FROM read_csv('s3://items/../other/a.csv') / HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden)
                 caused by java.sql.SQLException: HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden) / SignatureDoesNotMatch: The request signature we calculated does not match the signature you provided. Check your key and signing method. / Authentication Failure - this is usually caused by invalid or missing credentials.
              cairn saw HEAD /other/a.csv 403; GET /other/a.csv 403
      FAILED  SELECT * FROM read_csv('s3://items/' || chr(46) || chr(46) || '/other/a.csv')
              -> java.lang.IllegalStateException: Failed to run SQL: SELECT * FROM read_csv('s3://items/' || chr(46) || chr(46) || '/other/a.csv') / HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden)
                 caused by java.sql.SQLException: HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden) / SignatureDoesNotMatch: The request signature we calculated does not match the signature you provided. Check your key and signing method. / Authentication Failure - this is usually caused by invalid or missing credentials.
              cairn saw HEAD /other/a.csv 403; GET /other/a.csv 403
      FAILED  SELECT * FROM read_csv('s3://items/%2e%2e/other/a.csv')
              -> java.lang.IllegalStateException: Failed to run SQL: SELECT * FROM read_csv('s3://items/%2e%2e/other/a.csv') / HTTP Error: HTTP HEAD error checking 's3://items/%2e%2e/other/a.csv' (HTTP 404 Not Found)
                 caused by java.sql.SQLException: HTTP Error: HTTP HEAD error checking 's3://items/%2e%2e/other/a.csv' (HTTP 404 Not Found) / LINE 1: SELECT * FROM read_csv('s3://items/%2e%2e/other/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
              cairn saw HEAD /items/%252e%252e/other/a.csv 404
      FAILED  execute("SET enable_external_access = true")
              -> java.lang.IllegalStateException: Failed to execute: SET enable_external_access = true / Invalid Input Error: Cannot change configuration option ""enable_external_access"" - the configuration has been locked
                 caused by java.sql.SQLException: Invalid Input Error: Cannot change configuration option ""enable_external_access"" - the configuration has been locked

   4. QuerySession.open(options, QueryPolicy.readOnly())
      ok      register("reading", "SELECT * FROM read_parquet('s3://items/quackjvm/reading.parquet')")
              -> registered, cairn saw HEAD /items/quackjvm/reading.parquet 200; GET /items/quackjvm/reading.parquet 206
      ok      run("SELECT count(*), sum(value) FROM reading")
              -> count_star()  sum("value") 1000000       1.21970376E8, 10 requests to cairn (0 bytes up, 413,696 down)
      FAILED  run("SELECT * FROM read_csv('s3://other/a.csv')")
              -> io.quackjvm.agent.guard.QueryFailed: Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration / LINE 1: SELECT * FROM read_csv('s3://other/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration / LINE 2: SELECT * FROM read_csv('s3://other/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
              no request to cairn
      FAILED  run("SELECT * FROM read_csv('s3://items2/a.csv')")
              -> io.quackjvm.agent.guard.QueryFailed: Permission Error: Cannot access file "s3://items2/a.csv" - file system operations are disabled by configuration / LINE 1: SELECT * FROM read_csv('s3://items2/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://items2/a.csv" - file system operations are disabled by configuration / LINE 2: SELECT * FROM read_csv('s3://items2/a.csv') /                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
              no request to cairn
      FAILED  run("SELECT count(*) FROM read_csv('/etc/hosts')")
              -> io.quackjvm.agent.guard.QueryFailed: Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration / LINE 1: SELECT count(*) FROM read_csv('/etc/hosts') /                              ^^^^^^^^^^^^^^^^^^^^^^
                 caused by java.sql.SQLException: Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration / LINE 2: SELECT count(*) FROM read_csv('/etc/hosts') /                              ^^^^^^^^^^^^^^^^^^^^^^
              no request to cairn
      FAILED  run("SELECT * FROM read_csv('s3://items/../other/a.csv')")
              -> io.quackjvm.agent.guard.QueryFailed: HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden) / SignatureDoesNotMatch: The request signature we calculated does not match the signature you provided. Check your key and signing method. / Authentication Failure - this is usually caused by invalid or missing credentials.
                 caused by java.sql.SQLException: HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden) / SignatureDoesNotMatch: The request signature we calculated does not match the signature you provided. Check your key and signing method. / Authentication Failure - this is usually caused by invalid or missing credentials.
              cairn saw HEAD /other/a.csv 403; GET /other/a.csv 403
      FAILED  run("SELECT * FROM read_csv('s3://items/' || chr(46) || chr(46) || '/other/a.csv')")
              -> io.quackjvm.agent.guard.QueryFailed: HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden) / SignatureDoesNotMatch: The request signature we calculated does not match the signature you provided. Check your key and signing method. / Authentication Failure - this is usually caused by invalid or missing credentials.
                 caused by java.sql.SQLException: HTTP Error: HTTP GET error reading 's3://items/../other/a.csv' (HTTP 403 Forbidden) / SignatureDoesNotMatch: The request signature we calculated does not match the signature you provided. Check your key and signing method. / Authentication Failure - this is usually caused by invalid or missing credentials.
              cairn saw HEAD /other/a.csv 403; GET /other/a.csv 403
      FAILED  register("elsewhere", "SELECT * FROM read_csv('s3://other/a.csv')")
              -> java.lang.IllegalArgumentException: Could not register elsewhere: Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration / LINE 1: CREATE OR REPLACE VIEW "elsewhere" AS SELECT * FROM read_csv('s3://other/a.csv') /                                                             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                 caused by java.lang.IllegalStateException: Failed to execute: CREATE OR REPLACE VIEW "elsewhere" AS SELECT * FROM read_csv('s3://other/a.csv') / Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration
                 caused by java.sql.SQLException: Permission Error: Cannot access file "s3://other/a.csv" - file system operations are disabled by configuration / LINE 1: CREATE OR REPLACE VIEW "elsewhere" AS SELECT * FROM read_csv('s3://other/a.csv') /                                                             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^

   5. GuardedQuery with readOnly() on a database that is not hardened
      ok      run("SELECT count(*) FROM read_parquet('s3://items/quackjvm/reading.parquet')")
              -> count_star() 1000000
      ok      whyRejected("COPY (SELECT 1) TO 's3://items/elsewhere.parquet'")
              -> COPY is not a query, so it will not be run. Only statements that read are allowed: SELECT, WITH, FROM, TABLE, VALUES, DESCRIBE, SUMMARIZE, UNPIVOT and EXPLAIN. DuckDB's parser said: Only SELECT statements can be serialized to json!

   6. Why cairn refused the .. reads: the path DuckDB signs, and the path it sends
      FAILED  read_csv('s3://items/../other/a.csv') against a stub that records the request
              -> java.lang.IllegalStateException: Failed to count the rows of: SELECT * FROM read_csv('s3://items/../other/a.csv')
                 caused by java.sql.SQLException: HTTP Error: HTTP HEAD error checking 's3://items/../other/a.csv' (HTTP 404 Not Found) / LINE 1: SELECT count(*) FROM (SELECT * FROM read_csv('s3://items/../other/a.csv')) /                                             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      HEAD sent as /other/a.csv; signature computed over /other/a.csv equals the one sent: false
      HEAD sent as /other/a.csv; signature computed over /items/../other/a.csv equals the one sent: true

   objects on cairn:
      items/measures/measure=readings/part=2026-11-08/example-1794069501334.parquet       156,026 bytes  ETag "d31eb1554edd60d640bf59b3fe25653f"
      items/quackjvm/item-7.parquet                                                        14,458 bytes  ETag "8b5e0f94ca68c219696799bc229cc354"
      items/quackjvm/reading.parquet                                                    9,481,492 bytes  ETag "56a70cc4738eb3a2ea97ed7fd4e8b137-2"
      items2/a.csv                                                                             28 bytes  ETag "65e76b627d04900bfe8f0a33c3106ab1"
      other/a.csv                                                                              37 bytes  ETag "5a56aadea430745b18ef2aafbb7a1fc8"
*/
