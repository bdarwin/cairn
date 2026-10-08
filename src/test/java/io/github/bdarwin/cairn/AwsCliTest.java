package io.github.bdarwin.cairn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The {@code aws} CLI against cairn, unmodified. Skipped when {@code aws} is not on the PATH. */
class AwsCliTest {

    @TempDir
    Path dir;
    @TempDir
    Path work;
    TestServer server;

    @BeforeEach
    void start() throws Exception {
        assumeTrue(onPath("aws"), "aws CLI not installed");
        server = new TestServer(dir);
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void roundTripsAFileByteForByte() throws Exception {
        byte[] data = new byte[3 * 1024 * 1024 + 11];
        new Random(4).nextBytes(data);
        Path file = Files.write(work.resolve("reading.bin"), data);
        Path back = work.resolve("back.bin");

        aws("s3", "mb", "s3://items");
        aws("s3", "cp", file.toString(), "s3://items/dir/reading.bin");
        aws("s3", "cp", "s3://items/dir/reading.bin", back.toString());
        assertArrayEquals(data, Files.readAllBytes(back));

        String head = aws("s3api", "head-object", "--bucket", "items", "--key", "dir/reading.bin", "--checksum-mode", "ENABLED");
        assertTrue(head.contains("\"ContentLength\": " + data.length), head);
        assertTrue(head.contains("\"ChecksumCRC64NVME\""), "the CLI's CRC64NVME was verified and stored: " + head);

        aws("s3", "cp", file.toString(), "s3://items/dir/sub/other.bin");
        aws("s3", "cp", file.toString(), "s3://items/dir-x");
        String ls = aws("s3", "ls", "s3://items/dir/");
        assertTrue(ls.contains("PRE sub/") && ls.contains(data.length + " reading.bin"), ls);
        String recursive = aws("s3", "ls", "--recursive", "s3://items");
        List<String> listed = recursive.lines().map(l -> l.substring(l.lastIndexOf(' ') + 1)).toList();
        assertEquals(List.of("dir-x", "dir/reading.bin", "dir/sub/other.bin"), listed, "'-' sorts before '/'");

        aws("s3", "rm", "s3://items/dir-x");
        aws("s3", "rm", "s3://items/dir/sub/other.bin");
        aws("s3", "rm", "s3://items/dir/reading.bin");
        aws("s3", "rb", "s3://items");
    }

    private String aws(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("aws", "--endpoint-url", server.endpoint().toString()));
        cmd.addAll(List.of(args));
        if (args[1].equals("cp")) cmd.add("--no-progress");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("AWS_ACCESS_KEY_ID", TestServer.ACCESS_KEY);
        pb.environment().put("AWS_SECRET_ACCESS_KEY", TestServer.SECRET_KEY);
        pb.environment().put("AWS_DEFAULT_REGION", "us-east-1");
        pb.environment().put("AWS_EC2_METADATA_DISABLED", "true");
        pb.environment().put("AWS_CONFIG_FILE", work.resolve("no-config").toString());
        pb.environment().put("AWS_SHARED_CREDENTIALS_FILE", work.resolve("no-credentials").toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), () -> String.join(" ", cmd) + "\n" + out);
        return out;
    }

    static boolean onPath(String program) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(d, program))) return true;
        }
        return false;
    }
}
