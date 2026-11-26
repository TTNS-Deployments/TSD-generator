import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/*
 * Container start-up helper for Azure App Service (Java SE on Linux).
 *
 * The Java image has no curl, wget or tar, so this downloads the TSD Generator package (the GitHub Release zip
 * in TSD_PACKAGE_URL) with Java's own HTTP client and unpacks it into TSD_APP_DIR (default
 * /home/site/wwwroot/app). If the download fails, the last unpacked copy is kept and started.
 *
 * deploy.sh stores this file (newlines removed) in the TSD_BOOTSTRAP app setting; the startup command writes it
 * to /tmp/Boot.java and runs it with "java /tmp/Boot.java" (single-file source launch, no compile step).
 * Keep it valid as ONE line: block comments only, never line comments.
 */
public class Boot {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("TSD_PACKAGE_URL");
        String dir = System.getenv("TSD_APP_DIR");
        Path app = Paths.get(dir == null || dir.isBlank() ? "/home/site/wwwroot/app" : dir);
        Path staging = app.resolveSibling(app.getFileName() + ".new");
        Path zip = Paths.get(System.getProperty("java.io.tmpdir"), "tsd-package.zip");
        try {
            if (url == null || url.isBlank()) throw new IOException("TSD_PACKAGE_URL is not set");
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofFile(zip));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);

            deleteTree(staging);
            Files.createDirectories(staging);
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    Path out = staging.resolve(e.getName()).normalize();
                    if (!out.startsWith(staging)) throw new IOException("Unsafe zip entry: " + e.getName());
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (!Files.exists(staging.resolve("app.jar"))) throw new IOException("The package has no app.jar");
            deleteTree(app);
            Files.move(staging, app);
            System.out.println("Boot: unpacked " + url + " into " + app);
        } catch (Exception ex) {
            System.out.println("Boot: package download failed (" + ex + "), starting the last copy");
            if (!Files.exists(app.resolve("app.jar"))) {
                System.out.println("Boot: no previous copy in " + app + ", cannot start");
                System.exit(1);
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
