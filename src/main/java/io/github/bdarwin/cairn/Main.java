package io.github.bdarwin.cairn;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runs cairn from the command line.
 *
 * <pre>
 * java -jar cairn.jar --data /srv/cairn [--port 9000] [--bind 0.0.0.0] [--region us-east-1] [--config cairn.properties]
 * </pre>
 *
 * Access keys come from the config file ({@code credentials=KEY:SECRET,KEY2:SECRET2}) or from the
 * environment ({@code CAIRN_ACCESS_KEY} and {@code CAIRN_SECRET_KEY}). The config file may also set
 * {@code data}, {@code port}, {@code bind} and {@code region}; command-line options win.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Properties p = new Properties();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || i + 1 >= args.length) usage("unexpected argument: " + a);
            String name = a.substring(2), value = args[++i];
            if (name.equals("config")) {
                Properties file = new Properties();
                try (Reader r = Files.newBufferedReader(Path.of(value))) {
                    file.load(r);
                }
                file.forEach(p::putIfAbsent);
            } else {
                p.setProperty(name, value);
            }
        }
        String data = p.getProperty("data");
        if (data == null) usage("--data is required");
        CairnServer.Builder b = CairnServer.builder(Path.of(data))
                .port(Integer.parseInt(p.getProperty("port", "9000")))
                .bindAddress(p.getProperty("bind", "0.0.0.0"))
                .region(p.getProperty("region", "us-east-1"));
        int keys = 0;
        for (String pair : p.getProperty("credentials", "").split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0) {
                b.credentials(pair.substring(0, colon).trim(), pair.substring(colon + 1).trim());
                keys++;
            }
        }
        String envKey = System.getenv("CAIRN_ACCESS_KEY"), envSecret = System.getenv("CAIRN_SECRET_KEY");
        if (envKey != null && envSecret != null) {
            b.credentials(envKey, envSecret);
            keys++;
        }
        if (keys == 0) usage("no credentials: set credentials=KEY:SECRET in --config, or CAIRN_ACCESS_KEY and CAIRN_SECRET_KEY");
        CairnServer server = b.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("cairn listening on " + server.endpoint() + ", data in " + server.dataDirectory());
        Thread.currentThread().join();
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: cairn --data DIR [--port 9000] [--bind 0.0.0.0] [--region us-east-1] [--config FILE]");
        System.exit(2);
    }
}
