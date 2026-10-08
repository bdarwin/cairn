package io.github.bdarwin.cairn.examples.probes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Probe 5: which S3 keys cannot be held by the filesystem as they are? S3 keys are case-sensitive
 * byte strings (valid UTF-8, at most 1024 bytes). Each case below tries the naive mapping, key
 * segment = directory name, through {@code java.nio.file}, and reports what happens.
 *
 * <p>Run: {@code java -cp examples/target/classes io.github.bdarwin.cairn.examples.probes.ProbeKeyNames <empty dir>}
 */
public final class ProbeKeyNames {

    public static void main(String[] args) throws IOException {
        Path root = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        System.out.println("java " + System.getProperty("java.version") + ", sun.jnu.encoding "
                + System.getProperty("sun.jnu.encoding") + ", root " + root + " (" + utf8(root.toString()) + " bytes)");
        System.out.println("filesystem: " + Files.getFileStore(root).type());

        section("Segment length");
        for (String seg : List.of("a".repeat(255), "a".repeat(256), "é".repeat(127), "é".repeat(128),
                "é".repeat(255), "あ".repeat(85), "あ".repeat(86), "あ".repeat(255))) {
            tryCreate(root, seg, describe(seg));
        }

        section("'.' and '..' as segments, trailing '/', '//'");
        for (String key : List.of("x/./y", "x/../y", "x/", "x//y", "./z", "../z")) {
            Path p = root.resolve(key);
            System.out.printf("  key %-8s -> Path %-40s normalize() -> %s%n", quote(key), rel(root, p), rel(root, p.normalize()));
        }
        tryCreate(root, ".", "segment '.'");
        tryCreate(root, "..", "segment '..'");
        tryCreate(root, "", "empty segment (from '//' or a trailing '/')");

        section("Control characters and other bytes");
        for (String seg : List.of("ctl\u0001", "tab\t", "nl\n", "cr\r", "del\u007f", "nul\u0000", "colon:", "back\\slash",
                "star*?", "trailing-space ", "trailing-dot.", "-dash", ".hidden", " nbsp", "emoji😀")) {
            tryCreate(root, seg, quote(seg));
        }

        section("Keys differing only in case");
        Path lower = root.resolve("case-note");
        Files.createDirectory(lower);
        System.out.println("  created 'case-note'; exists('Case-Note') = " + Files.exists(root.resolve("Case-Note")));
        tryCreate(root, "Case-Note", "then create 'Case-Note'");
        System.out.println("  listing shows: " + list(root, "ase-"));

        section("Keys differing only in Unicode normalisation");
        String nfc = Normalizer.normalize("café", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("café", Normalizer.Form.NFD);
        System.out.println("  NFC 'café' = " + hex(nfc) + ", NFD 'café' = " + hex(nfd));
        Files.createDirectory(root.resolve(nfd));
        System.out.println("  created NFD; exists(NFC) = " + Files.exists(root.resolve(nfc)));
        tryCreate(root, nfc, "then create NFC");
        for (String name : list(root, "caf")) System.out.println("  listing returns " + hex(name) + "  (the form first written)");

        section("Total path length (PATH_MAX is 1024 on macOS)");
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) rel.append('/');
            rel.append(String.valueOf((char) ('p' + i)).repeat(200));
            Path p = root.resolve(rel.toString());
            tryCreatePath(p, "depth " + (i + 1) + ", absolute path " + utf8(p.toString()) + " bytes");
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            System.out.println("  SecureDirectoryStream (openat-relative access) available: " + (ds instanceof SecureDirectoryStream));
        }
    }

    static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    static void tryCreate(Path root, String segment, String label) {
        try {
            Path p = root.resolve(segment);
            tryCreatePath(p, label);
        } catch (Exception e) {
            System.out.printf("  %-58s FAILS: %s%n", label, e);
        }
    }

    static void tryCreatePath(Path p, String label) {
        try {
            Files.createDirectory(p);
            boolean back = Files.isDirectory(p);
            System.out.printf("  %-58s ok, reads back: %s%n", label, back);
        } catch (Exception e) {
            System.out.printf("  %-58s FAILS: %s%n", label, e.getClass().getSimpleName() + ": " + shorten(e.getMessage()));
        }
    }

    static String shorten(String m) {
        if (m == null) return "";
        int i = m.lastIndexOf(": ");
        return m.length() > 90 ? "..." + (i >= 0 ? m.substring(i) : m.substring(m.length() - 60)) : m;
    }

    static String describe(String s) {
        return s.codePointCount(0, s.length()) + " x '" + new String(Character.toChars(s.codePointAt(0))) + "' = "
                + utf8(s) + " UTF-8 bytes, " + s.length() + " UTF-16 units";
    }

    static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) sb.append(c < 0x20 || c == 0x7f ? String.format("\\x%02x", (int) c) : String.valueOf(c));
        return sb.append("'").toString();
    }

    static String hex(String s) {
        return HexFormat.ofDelimiter(" ").formatHex(s.getBytes(StandardCharsets.UTF_8));
    }

    static String rel(Path root, Path p) {
        return quote(p.startsWith(root) ? root.relativize(p).toString() : p.toString());
    }

    static List<String> list(Path root, String contains) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.contains(contains)).sorted().forEach(out::add);
        }
        return out;
    }
}

/*
Output (2026-10-08, macOS 26.5, APFS default (case-insensitive) volume, JDK 21.0.11):

   java 21.0.11, sun.jnu.encoding UTF-8, root /private/tmp/claude-501/-Users-bdarwin-my-source-cairn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/keys (107 bytes)
   filesystem: apfs
   
   Segment length
     255 x 'a' = 255 UTF-8 bytes, 255 UTF-16 units              ok, reads back: true
     256 x 'a' = 256 UTF-8 bytes, 256 UTF-16 units              FAILS: FileSystemException: ...: File name too long
     127 x 'é' = 254 UTF-8 bytes, 127 UTF-16 units              ok, reads back: true
     128 x 'é' = 256 UTF-8 bytes, 128 UTF-16 units              ok, reads back: true
     255 x 'é' = 510 UTF-8 bytes, 255 UTF-16 units              ok, reads back: true
     85 x 'あ' = 255 UTF-8 bytes, 85 UTF-16 units                ok, reads back: true
     86 x 'あ' = 258 UTF-8 bytes, 86 UTF-16 units                ok, reads back: true
     255 x 'あ' = 765 UTF-8 bytes, 255 UTF-16 units              ok, reads back: true
   
   '.' and '..' as segments, trailing '/', '//'
     key 'x/./y'  -> Path 'x/y'                                    normalize() -> 'x/y'
     key 'x/../y' -> Path 'y'                                      normalize() -> 'y'
     key 'x/'     -> Path 'x'                                      normalize() -> 'x'
     key 'x//y'   -> Path 'x/y'                                    normalize() -> 'x/y'
     key './z'    -> Path 'z'                                      normalize() -> 'z'
     key '../z'   -> Path '../z'                                   normalize() -> '/private/tmp/claude-501/-Users-bdarwin-my-source-cairn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/z'
     segment '.'                                                FAILS: FileAlreadyExistsException: ...cairn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/keys/.
     segment '..'                                               FAILS: FileAlreadyExistsException: ...airn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/keys/..
     empty segment (from '//' or a trailing '/')                FAILS: FileAlreadyExistsException: ...e-cairn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/keys
   
   Control characters and other bytes
     'ctl\x01'                                                  ok, reads back: true
     'tab\x09'                                                  ok, reads back: true
     'nl\x0a'                                                   ok, reads back: true
     'cr\x0d'                                                   ok, reads back: true
     'del\x7f'                                                  ok, reads back: true
     'nul\x00'                                                  FAILS: java.nio.file.InvalidPathException: Nul character not allowed: nul 
     'colon:'                                                   ok, reads back: true
     'back\slash'                                               ok, reads back: true
     'star*?'                                                   ok, reads back: true
     'trailing-space '                                          ok, reads back: true
     'trailing-dot.'                                            ok, reads back: true
     '-dash'                                                    ok, reads back: true
     '.hidden'                                                  ok, reads back: true
     ' nbsp'                                                    ok, reads back: true
     'emoji😀'                                                  ok, reads back: true
   
   Keys differing only in case
     created 'case-note'; exists('Case-Note') = true
     then create 'Case-Note'                                    FAILS: FileAlreadyExistsException: ...9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/keys/Case-Note
     listing shows: [case-note]
   
   Keys differing only in Unicode normalisation
     NFC 'café' = 63 61 66 c3 a9, NFD 'café' = 63 61 66 65 cc 81
     created NFD; exists(NFC) = true
     then create NFC                                            FAILS: FileAlreadyExistsException: ...rn/5c9569f9-8e5f-4c34-b8a2-0dfc81df1453/scratchpad/keys/café
     listing returns 63 61 66 65 cc 81  (the form first written)
   
   Total path length (PATH_MAX is 1024 on macOS)
     depth 1, absolute path 308 bytes                           ok, reads back: true
     depth 2, absolute path 509 bytes                           ok, reads back: true
     depth 3, absolute path 710 bytes                           ok, reads back: true
     depth 4, absolute path 911 bytes                           ok, reads back: true
     depth 5, absolute path 1112 bytes                          FAILS: FileSystemException: ...: File name too long
     depth 6, absolute path 1313 bytes                          FAILS: FileSystemException: ...: File name too long
     SecureDirectoryStream (openat-relative access) available: false
*/
