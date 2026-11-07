package io.github.bdarwin.cairn.examples;

import io.github.bdarwin.cairn.CairnServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Milestone 5, acceptance test 2: DuckDB reads only the bytes a query needs from a Parquet file on
 * cairn. DuckDB writes the file with {@code COPY ... TO 's3://...'}, then runs four queries, each on
 * a fresh in-memory database so nothing is cached between them. A {@link CountingProxy} between
 * DuckDB and cairn counts the body bytes cairn served for each query, against the file's size and
 * against the size of the column chunk the narrowest query needs (from {@code parquet_metadata}).
 *
 * <p>Run: {@code java --enable-native-access=ALL-UNNAMED -cp ... DuckDbReadsWhatItNeeds <empty dir> [rows]}.
 * DuckDB downloads its httpfs extension on first use.
 */
public final class DuckDbReadsWhatItNeeds {

    static final String KEY = "example-key", SECRET = "example-secret";
    static final int ROW_GROUP = 100_000;

    public static void main(String[] args) throws Exception {
        Path data = Files.createDirectories(Path.of(args[0]));
        long rows = args.length > 1 ? Long.parseLong(args[1]) : 4_000_000;
        try (CairnServer server = CairnServer.builder(data).bindAddress("127.0.0.1").port(0).credentials(KEY, SECRET).start();
             CountingProxy proxy = new CountingProxy(server.port());
             S3Client s3 = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + server.port())).region(Region.US_EAST_1)
                     .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(KEY, SECRET))).build()) {
            String url = "s3://items/readings.parquet";
            try (Connection duck = open(proxy)) {
                System.out.println("DuckDB " + one(duck, "SELECT version()") + ", java " + System.getProperty("java.version"));
                s3.createBucket(b -> b.bucket("items"));
                proxy.drain();
                long t = System.nanoTime();
                execute(duck, "COPY (SELECT range AS id, range % 1000 AS item, (hash(range) % 100000) / 100.0 AS reading, "
                        + "md5(range::VARCHAR) AS note, hash(range * 7) AS code FROM range(" + rows + ")) TO '" + url + "' (FORMAT parquet, ROW_GROUP_SIZE " + ROW_GROUP + ")");
                double seconds = (System.nanoTime() - t) / 1e9;
                List<CountingProxy.Exchange> write = proxy.drain();
                long parts = write.stream().filter(e -> e.method().equals("PUT") && e.target().contains("partNumber=")).count();
                long size = s3.headObject(b -> b.bucket("items").key("readings.parquet")).contentLength();
                proxy.drain();
                System.out.printf("wrote %,d rows in %.1f s: %,d bytes, %d parts by multipart upload, %s row groups (ROW_GROUP_SIZE %,d), 5 columns%n",
                        rows, seconds, size, parts, one(duck, "SELECT count(DISTINCT row_group_id) FROM parquet_metadata('" + url + "')"), ROW_GROUP);
                // DuckDB rounds row groups up to a multiple of its 2,048-row vectors, so take row group 7's bounds from the file.
                String[] chunk = one(duck, "SELECT total_compressed_size, stats_min, stats_max, row_group_num_rows FROM parquet_metadata('" + url + "') WHERE row_group_id = 7 AND path_in_schema = 'id'").split(" ");
                String footer = one(duck, "SELECT footer_size FROM parquet_file_metadata('" + url + "')");
                proxy.drain();
                long lo = Long.parseLong(chunk[1]), hi = Long.parseLong(chunk[2]);
                System.out.printf("row group 7: %,d rows, id %,d to %,d; its id column chunk %,d bytes compressed; footer %,d bytes%n%n",
                        Long.parseLong(chunk[3]), lo, hi, Long.parseLong(chunk[0]), Long.parseLong(footer));
                System.out.printf("%-58s %9s %14s %8s%n", "query", "requests", "bytes served", "of file");
                measure(proxy, size, "every column, every row group", "SELECT sum(id), sum(item), sum(reading), max(note), sum(code) FROM '" + url + "'");
                measure(proxy, size, "one column (reading), every row group", "SELECT sum(reading) FROM '" + url + "'");
                measure(proxy, size, "one column (id), one row group (7)", "SELECT sum(id) FROM '" + url + "' WHERE id BETWEEN " + lo + " AND " + hi);
                measure(proxy, size, "count(*): the footer only", "SELECT count(*) FROM '" + url + "'");
                System.out.println();
                System.out.println("requests for one column of one row group:");
                execute(open(proxy), "SELECT sum(id) FROM '" + url + "' WHERE id BETWEEN " + lo + " AND " + hi);
                for (CountingProxy.Exchange e : proxy.drain()) System.out.println("  " + e);
            }
            System.out.printf("%nwire bytes from cairn, headers included, whole run: %,d%n", proxy.wireBytesDown());
        }
    }

    static void measure(CountingProxy proxy, long size, String label, String sql) throws SQLException {
        proxy.drain();
        String answer;
        try (Connection fresh = open(proxy)) {
            answer = one(fresh, sql);
        } catch (SQLException e) {
            proxy.drain().forEach(x -> System.out.println("  " + x));
            throw e;
        }
        List<CountingProxy.Exchange> ex = proxy.drain();
        long bytes = ex.stream().mapToLong(CountingProxy.Exchange::responseBody).sum();
        System.out.printf("%-58s %9d %,14d %7.2f%%   (answer %s)%n", label, ex.size(), bytes, 100.0 * bytes / size, answer);
    }

    static Connection open(CountingProxy proxy) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        execute(c, "LOAD httpfs");
        execute(c, "CREATE SECRET (TYPE s3, KEY_ID '" + KEY + "', SECRET '" + SECRET + "', ENDPOINT '127.0.0.1:" + proxy.port() + "', URL_STYLE 'path', USE_SSL false)");
        return c;
    }

    static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** The first row, columns joined by spaces. */
    static String one(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) sb.append(i > 1 ? " " : "").append(rs.getString(i));
            return sb.toString();
        }
    }
}

/*
Output (2026-11-07, Apple M1 Max, internal SSD, APFS, JDK 21.0.11, DuckDB JDBC 2.0.0.0; java ... DuckDbReadsWhatItNeeds <dir>):

   DuckDB v2.0.0, java 21.0.11
   wrote 4,000,000 rows in 1.3 s: 196,825,774 bytes, 20 parts by multipart upload, 40 row groups (ROW_GROUP_SIZE 100,000), 5 columns
   row group 7: 100,352 rows, id 702,464 to 802,815; its id column chunk 401,629 bytes compressed; footer 23,511 bytes

   query                                                       requests   bytes served  of file
   every column, every row group                                    121    196,825,774  100.00%   (answer 7999998000000 1998000000 1.9990739063100176E9 fffffe98d0963d27015c198262d97221 36882525911003092376438456)
   one column (reading), every row group                             42     19,710,638   10.01%   (answer 1.9990739063100104E9)
   one column (id), one row group (7)                                 3        668,334    0.34%   (answer 75528879104)
   count(*): the footer only                                          2        262,830    0.13%   (answer 4000000)

   requests for one column of one row group:
     HEAD /items/readings.parquet -> 200, 0 bytes
     GET /items/readings.parquet Range: bytes=196562944-196825773 -> 206, 262830 bytes
     GET /items/readings.parquet Range: bytes=34545664-34951167 -> 206, 405504 bytes

   wire bytes from cairn, headers included, whole run: 218,469,083
*/
