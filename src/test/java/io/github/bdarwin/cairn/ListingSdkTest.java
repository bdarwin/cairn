package io.github.bdarwin.cairn;

import io.github.bdarwin.cairn.internal.store.Utf8Order;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListingSdkTest {

    TestServer server;
    S3Client s3;
    final TreeSet<String> keys = new TreeSet<>(Utf8Order::compare);

    @BeforeAll
    void start(@TempDir Path dir) throws Exception {
        server = new TestServer(dir, false);
        s3 = server.client();
        s3.createBucket(b -> b.bucket("items"));
        for (String k : List.of("a", "a-c", "a/b", "a/b/c", "a/d", "a0", "b/x", "b/y/z", "café", "Note", "note", "😀", "\uFFFD", "dir/", "dir//x", "sp ace+plus")) put(k);
        for (int i = 0; i < 2500; i++) put(String.format("readings/%02d/item-%04d", i % 25, i));
    }

    @AfterAll
    void stop() {
        s3.close();
        server.close();
    }

    private void put(String k) {
        s3.putObject(b -> b.bucket("items").key(k), RequestBody.fromString(k));
        keys.add(k);
    }

    @Test
    void paginatorReturnsEveryKeyOnceInUtf8Order() {
        List<String> got = new ArrayList<>();
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> b.bucket("items").maxKeys(333))) {
            assertTrue(page.keyCount() <= 333);
            page.contents().forEach(o -> got.add(o.key()));
        }
        assertEquals(new ArrayList<>(keys), got);
    }

    @Test
    void delimiterAndPrefix() {
        ListObjectsV2Response top = s3.listObjectsV2(b -> b.bucket("items").delimiter("/"));
        assertEquals(List.of("a", "a-c", "a0", "café", "Note", "note", "sp ace+plus", "😀", "\uFFFD").stream().sorted(Utf8Order::compare).toList(),
                top.contents().stream().map(S3Object::key).toList());
        assertEquals(List.of("a/", "b/", "dir/", "readings/"), top.commonPrefixes().stream().map(CommonPrefix::prefix).toList());
        assertEquals(top.contents().size() + top.commonPrefixes().size(), top.keyCount());

        ListObjectsV2Response readings = s3.listObjectsV2(b -> b.bucket("items").prefix("readings/").delimiter("/"));
        assertEquals(25, readings.commonPrefixes().size());
        assertEquals("readings/00/", readings.commonPrefixes().getFirst().prefix());
        assertTrue(readings.contents().isEmpty());

        ListObjectsV2Response one = s3.listObjectsV2(b -> b.bucket("items").prefix("readings/07/").delimiter("/"));
        assertEquals(100, one.contents().size());
        assertEquals("readings/07/item-0007", one.contents().getFirst().key());
        assertEquals("readings/07/item-0007".length(), one.contents().getFirst().size());
        assertEquals(one.contents().getFirst().eTag(), s3.headObject(b -> b.bucket("items").key("readings/07/item-0007")).eTag());

        ListObjectsV2Response dirs = s3.listObjectsV2(b -> b.bucket("items").prefix("dir/").delimiter("/"));
        assertEquals(List.of("dir/"), dirs.contents().stream().map(S3Object::key).toList());
        assertEquals(List.of("dir//"), dirs.commonPrefixes().stream().map(CommonPrefix::prefix).toList());
    }

    @Test
    void commonPrefixesPageCorrectly() {
        List<String> prefixes = new ArrayList<>();
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> b.bucket("items").prefix("readings/").delimiter("/").maxKeys(7))) {
            page.commonPrefixes().forEach(p -> prefixes.add(p.prefix()));
        }
        assertEquals(25, prefixes.size());
        assertEquals(25, new TreeSet<>(prefixes).size());
    }

    @Test
    void startAfter() {
        ListObjectsV2Response r = s3.listObjectsV2(b -> b.bucket("items").startAfter("a/b").maxKeys(4));
        assertEquals(List.of("a/b/c", "a/d", "a0", "b/x"), r.contents().stream().map(S3Object::key).toList());
        assertTrue(r.isTruncated());
        assertEquals("a/b", r.startAfter());
    }

    /**
     * XML 1.0 cannot carry U+0001 even as a character reference, so a listing that holds such a key
     * breaks XML parsers unless the client asks for {@code encoding-type=url}. S3 behaves the same.
     */
    @Test
    void urlEncodingCarriesAnyKey() {
        s3.createBucket(b -> b.bucket("odd"));
        s3.putObject(b -> b.bucket("odd").key("ctl\u0001key"), RequestBody.fromString("x"));
        ListObjectsV2Response r = s3.listObjectsV2(b -> b.bucket("odd").encodingType(EncodingType.URL));
        assertEquals(1, r.contents().size());
        String key = r.contents().getFirst().key();
        assertEquals("ctl\u0001key", key.contains("%") ? URLDecoder.decode(key.replace("+", "%2B"), StandardCharsets.UTF_8) : key);
        ListObjectsV2Response sp = s3.listObjectsV2(b -> b.bucket("items").encodingType(EncodingType.URL).prefix("sp"));
        String k2 = sp.contents().getFirst().key();
        assertEquals("sp ace+plus", k2.contains("%") ? URLDecoder.decode(k2.replace("+", "%2B"), StandardCharsets.UTF_8) : k2);
    }

    @Test
    void listObjectsV1WithMarkers() {
        List<String> got = new ArrayList<>();
        String marker = null;
        while (true) {
            String m = marker;
            ListObjectsResponse r = s3.listObjects(b -> b.bucket("items").maxKeys(500).marker(m));
            r.contents().forEach(o -> got.add(o.key()));
            if (!r.isTruncated()) break;
            marker = r.contents().getLast().key();
        }
        assertEquals(new ArrayList<>(keys), got);
        ListObjectsResponse d = s3.listObjects(b -> b.bucket("items").delimiter("/").maxKeys(3));
        assertTrue(d.isTruncated());
        assertFalse(d.nextMarker() == null);
    }

    @Test
    void missingBucket() {
        assertThrows(NoSuchBucketException.class, () -> s3.listObjectsV2(b -> b.bucket("absent")));
    }
}
