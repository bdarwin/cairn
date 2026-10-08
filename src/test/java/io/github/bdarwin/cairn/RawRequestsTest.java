package io.github.bdarwin.cairn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawRequestsTest {

    @TempDir
    Path dir;
    TestServer server;
    RawRequests raw;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(dir);
        raw = new RawRequests(server.endpoint());
        assertEquals(200, raw.send("PUT", "/items", null, Map.of()).statusCode());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void invalidBucketNames() throws Exception {
        for (String name : List.of("ab", "192.168.5.4", "a..b", "xn--abc", "-abc", "abc-", "a".repeat(64), "Upper")) {
            HttpResponse<String> r = raw.send("PUT", "/" + name, null, Map.of());
            assertEquals(400, r.statusCode(), name);
            assertTrue(r.body().contains("<Code>InvalidBucketName</Code>"), r.body());
        }
    }

    @Test
    void errorBodyIsS3Xml() throws Exception {
        HttpResponse<String> r = raw.send("GET", "/items/missing%20key", null, Map.of());
        assertEquals(404, r.statusCode());
        assertEquals("application/xml", r.headers().firstValue("Content-Type").orElse(""));
        assertTrue(r.body().startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), r.body());
        assertTrue(r.body().contains("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message><Key>missing key</Key><BucketName>items</BucketName>"), r.body());
        assertTrue(r.headers().firstValue("x-amz-request-id").isPresent());
        HttpResponse<String> head = raw.send("HEAD", "/items/missing", null, Map.of());
        assertEquals(404, head.statusCode());
        assertEquals("", head.body());
    }

    @Test
    void anonymousIsAccessDenied() throws Exception {
        HttpResponse<String> r = raw.sendAsIs("GET", server.endpoint().resolve("/items/x"), null, Map.of());
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("<Code>AccessDenied</Code>"), r.body());
    }

    @Test
    void bucketSubresourcesMcAsksFor() throws Exception {
        HttpResponse<String> loc = raw.send("GET", "/items?location", null, Map.of());
        assertEquals(200, loc.statusCode());
        assertTrue(loc.body().contains("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"></LocationConstraint>"), loc.body());
        HttpResponse<String> lock = raw.send("GET", "/items?object-lock", null, Map.of());
        assertEquals(404, lock.statusCode());
        assertTrue(lock.body().contains("<Code>ObjectLockConfigurationNotFoundError</Code>"), lock.body());
        HttpResponse<String> tagging = raw.send("PUT", "/items/x?tagging", "<Tagging/>".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals(501, tagging.statusCode());
        assertTrue(tagging.body().contains("<Code>NotImplemented</Code>"), tagging.body());
    }

    @Test
    void payloadThatDoesNotMatchItsSignedHashIsRefused() throws Exception {
        byte[] signedFor = "first!".getBytes(StandardCharsets.UTF_8);
        byte[] sent = "other!".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> r = raw.sendSignedFor("PUT", "/items/k", signedFor, sent, Map.of());
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("<Code>XAmzContentSHA256Mismatch</Code>"), r.body());
        assertEquals(404, raw.send("HEAD", "/items/k", null, Map.of()).statusCode());
        assertEquals(200, raw.send("PUT", "/items/k", signedFor, Map.of()).statusCode());
    }
}
