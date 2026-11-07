package io.github.bdarwin.cairn;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DuckDB's {@code httpfs} against cairn, unmodified: acceptance test 1 of the brief. DuckDB runs in
 * process through its JDBC driver; the httpfs extension is downloaded on first use, and the tests are
 * skipped if it cannot be loaded (no network and no cached copy).
 */
class DuckDbTest {

    @TempDir
    static Path dir;
    static TestServer server;
    static S3Client s3;
    static Connection duck;
    String bucket;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer(dir);
        s3 = server.client();
        duck = DriverManager.getConnection("jdbc:duckdb:");
        assumeTrue(loadHttpfs(duck), "DuckDB's httpfs extension could not be installed or loaded");
        execute(duck, createSecret(server.server.port()));
    }

    @AfterAll
    static void stop() throws Exception {
        if (duck != null) duck.close();
        if (s3 != null) s3.close();
        if (server != null) server.close();
    }

    @BeforeEach
    void bucket(TestInfo test) {
        bucket = test.getTestMethod().orElseThrow().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
        s3.createBucket(b -> b.bucket(bucket));
    }

    static boolean loadHttpfs(Connection c) {
        try {
            execute(c, "LOAD httpfs");
            return true;
        } catch (SQLException notInstalled) {
            try {
                execute(c, "INSTALL httpfs");
                execute(c, "LOAD httpfs");
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    static String createSecret(int port) {
        return "CREATE SECRET (TYPE s3, KEY_ID '" + TestServer.ACCESS_KEY + "', SECRET '" + TestServer.SECRET_KEY
                + "', ENDPOINT '127.0.0.1:" + port + "', URL_STYLE 'path', USE_SSL false)";
    }

    @Test
    void copyToS3AndReadParquetBack() throws Exception {
        execute(duck, "COPY (SELECT range AS id, range * 2 AS reading, 'note ' || range AS note FROM range(1000)) TO 's3://" + bucket + "/x.parquet'");
        HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key("x.parquet"));
        assertTrue(head.contentLength() > 1000, "a Parquet file of " + head.contentLength() + " bytes");
        assertEquals("1000 999000 note 999", query(duck, "SELECT count(*), sum(reading), max(note) FROM read_parquet('s3://" + bucket + "/x.parquet')"));
        assertEquals("1000 999000", query(duck, "SELECT count(*), sum(reading) FROM read_parquet('s3://" + bucket + "/*.parquet')"));
    }

    @Test
    void aLargeFileIsWrittenByMultipartUpload() throws Exception {
        // DuckDB 2.0 switches to multipart above its part size, s3_uploader_max_filesize / s3_uploader_max_parts_per_file.
        execute(duck, "COPY (SELECT range AS id, md5(range::VARCHAR) AS note FROM range(1000000)) TO 's3://" + bucket + "/big.parquet'");
        HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key("big.parquet"));
        assertTrue(head.eTag().matches("\"[0-9a-f]{32}-([2-9]|\\d{2,})\""), "a multipart ETag: " + head.eTag() + ", " + head.contentLength() + " bytes");
        assertEquals("1000000 499999500000", query(duck, "SELECT count(*), sum(id) FROM read_parquet('s3://" + bucket + "/big.parquet')"));
        assertEquals("1000000 " + md5Of(999_999), query(duck, "SELECT count(DISTINCT note), max_by(note, id) FROM read_parquet('s3://" + bucket + "/big.parquet')"));
        assertTrue(s3.listMultipartUploads(b -> b.bucket(bucket)).uploads().isEmpty(), "the upload was completed, not left open");
    }

    @Test
    void globsListTheBucket() throws Exception {
        String root = "s3://" + bucket + "/";
        execute(duck, "COPY (SELECT range AS id FROM range(10)) TO '" + root + "a.parquet'");
        execute(duck, "COPY (SELECT range AS id FROM range(20)) TO '" + root + "b.parquet'");
        execute(duck, "COPY (SELECT range AS id FROM range(100)) TO '" + root + "dir/c.parquet'");
        execute(duck, "COPY (SELECT range AS id FROM range(1000)) TO '" + root + "dir/sub/d.parquet'");
        execute(duck, "COPY (SELECT range AS id FROM range(5)) TO '" + root + "dir-x/e.parquet'");
        assertEquals("30", query(duck, "SELECT count(*) FROM read_parquet('" + root + "*.parquet')"));
        assertEquals("100", query(duck, "SELECT count(*) FROM read_parquet('" + root + "dir/*.parquet')"));
        assertEquals("1000", query(duck, "SELECT count(*) FROM read_parquet('" + root + "dir/*/*.parquet')"));
        assertEquals("105", query(duck, "SELECT count(*) FROM read_parquet('" + root + "dir*/*.parquet')"));
        assertEquals("1135", query(duck, "SELECT count(*) FROM read_parquet('" + root + "**/*.parquet')"));
        assertEquals("a.parquet b.parquet dir-x/e.parquet dir/c.parquet dir/sub/d.parquet",
                query(duck, "SELECT string_agg(replace(file, '" + root + "', ''), ' ' ORDER BY file) FROM glob('" + root + "**')"));
        SQLException none = assertThrows(SQLException.class, () -> query(duck, "SELECT count(*) FROM read_parquet('" + root + "nothing/*.parquet')"));
        assertTrue(none.getMessage().contains("No files found"), none.getMessage());
    }

    @Test
    void aGlobOverMoreThanOnePageOfKeys() throws Exception {
        // 1,100 files: the listing comes back in two pages, joined by a continuation token.
        execute(duck, "COPY (SELECT range AS id, range AS part FROM range(1100)) TO 's3://" + bucket + "/many' (FORMAT parquet, PARTITION_BY (part))");
        assertEquals(1100, s3.listObjectsV2Paginator(b -> b.bucket(bucket)).contents().stream().count());
        assertEquals("1100 604450", query(duck, "SELECT count(*), sum(id) FROM read_parquet('s3://" + bucket + "/many/*/*.parquet')"));
    }

    @Test
    void hivePartitionsAreWrittenAndReadBack() throws Exception {
        execute(duck, "COPY (SELECT range AS id, range % 3 AS part FROM range(30)) TO 's3://" + bucket + "/hive' (FORMAT parquet, PARTITION_BY (part))");
        assertEquals(List.of("hive/part=0/data_0.parquet", "hive/part=1/data_0.parquet", "hive/part=2/data_0.parquet"), keys());
        assertEquals("20 30", query(duck, "SELECT count(*), sum(part) FROM read_parquet('s3://" + bucket + "/hive/*/*.parquet', hive_partitioning = true) WHERE part > 0"));
    }

    @Test
    void overwritingPartitionsDeletesTheOldFiles() throws Exception {
        // OVERWRITE removes what is under the target first, with one DeleteObjects request.
        execute(duck, "COPY (SELECT range AS id, range % 2 AS part FROM range(10)) TO 's3://" + bucket + "/ow' (FORMAT parquet, PARTITION_BY (part))");
        execute(duck, "COPY (SELECT range AS id FROM range(3)) TO 's3://" + bucket + "/ow/part=7/data_0.parquet'");
        execute(duck, "COPY (SELECT range AS id, range % 2 AS part FROM range(4)) TO 's3://" + bucket + "/ow' (FORMAT parquet, PARTITION_BY (part), OVERWRITE)");
        assertEquals(List.of("ow/part=0/data_0.parquet", "ow/part=1/data_0.parquet"), keys());
        assertEquals("4", query(duck, "SELECT count(*) FROM read_parquet('s3://" + bucket + "/ow/*/*.parquet')"));
    }

    @Test
    void keysWithCharactersThatAreEscapedInAUrl() throws Exception {
        String key = "odd keys/a b+c=d&e,f;g~h'.parquet";
        String url = "s3://" + bucket + "/" + key;
        execute(duck, "COPY (SELECT 42 AS id) TO '" + url.replace("'", "''") + "'");
        assertEquals(List.of(key), keys());
        assertEquals("42", query(duck, "SELECT max(id) FROM read_parquet('" + url.replace("'", "''") + "')"));
        assertEquals("42", query(duck, "SELECT max(id) FROM read_parquet('s3://" + bucket + "/odd keys/*.parquet')"));
    }

    @Test
    void csvAndJsonRoundTrip() throws Exception {
        execute(duck, "COPY (SELECT range AS id, 'note ' || range AS note FROM range(1000)) TO 's3://" + bucket + "/r.csv'");
        execute(duck, "COPY (SELECT range AS id FROM range(1000)) TO 's3://" + bucket + "/r.json'");
        assertEquals("1000 note 999", query(duck, "SELECT count(*), max(note) FROM 's3://" + bucket + "/r.csv'"));
        assertEquals("1000 499500", query(duck, "SELECT count(*), sum(id) FROM read_json('s3://" + bucket + "/r.json')"));
    }

    @Test
    void attachADatabaseFileReadOnly() throws Exception {
        Path db = dir.resolve("items.duckdb");
        try (Connection w = DriverManager.getConnection("jdbc:duckdb:" + db)) {
            execute(w, "CREATE TABLE item AS SELECT range AS id, range % 7 AS reading FROM range(100000)");
        }
        s3.putObject(b -> b.bucket(bucket).key("db/items.duckdb"), db);
        execute(duck, "ATTACH 's3://" + bucket + "/db/items.duckdb' AS remote (READ_ONLY)");
        try {
            assertEquals("100000 299995", query(duck, "SELECT count(*), sum(reading) FROM remote.item"));
        } finally {
            execute(duck, "DETACH remote");
        }
    }

    @Test
    void aMissingFileIsAnError() {
        SQLException e = assertThrows(SQLException.class, () -> query(duck, "SELECT count(*) FROM read_parquet('s3://" + bucket + "/missing.parquet')"));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    /** The {@code duckdb} command-line program (1.5.x here), when it is installed: a second httpfs, with its own part size. */
    @Test
    void theCommandLineProgram() throws Exception {
        assumeTrue(AwsCliTest.onPath("duckdb"), "duckdb CLI not installed");
        String sql = "LOAD httpfs;\n" + createSecret(server.server.port()) + ";\n"
                + "COPY (SELECT range AS id, md5(range::VARCHAR) AS note FROM range(2500000)) TO 's3://" + bucket + "/cli.parquet';\n"
                + "SELECT count(*), sum(id) FROM read_parquet('s3://" + bucket + "/*.parquet');\n";
        Path script = Files.writeString(dir.resolve(bucket + ".sql"), sql);
        Process p = new ProcessBuilder("duckdb", "-noheader", "-csv", "-f", script.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assumeTrue(!out.contains("httpfs") || p.exitValue() == 0, "the CLI's httpfs is not installed: " + out);
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("2500000,3124998750000"), out);
        String etag = s3.headObject(b -> b.bucket(bucket).key("cli.parquet")).eTag();
        assertTrue(etag.contains("-"), "the CLI wrote by multipart upload: " + etag);
    }

    // ---- helpers ----

    private List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(b -> b.bucket(bucket)).contents()) keys.add(o.key());
        return keys;
    }

    static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** The first row, its columns joined by spaces. */
    static String query(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "no row: " + sql);
            int n = rs.getMetaData().getColumnCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) sb.append(i > 1 ? " " : "").append(rs.getString(i));
            return sb.toString();
        }
    }

    private static String md5Of(long n) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(Long.toString(n).getBytes(StandardCharsets.US_ASCII)));
    }
}
