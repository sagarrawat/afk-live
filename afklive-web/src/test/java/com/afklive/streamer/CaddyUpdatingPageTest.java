package com.afklive.streamer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * While the web app restarts, Caddy shows "AFK Live is updating" instead of the browser's bare 502
 * (afklive-scripts/caddy/errors/index.html; docs/operations.md, "While the web app restarts"). Checks both
 * Caddyfiles carry the same handle_errors block, the web deploy uploads the page, and the page stands on its
 * own (the app is down when it shows, so it may load nothing). With a caddy binary on PATH (or CADDY_BIN), the
 * files are validated and the block is run for real against a closed upstream port.
 */
class CaddyUpdatingPageTest {

    private static final Path TWO_SLOT = locate("afklive-scripts/caddy/Caddyfile");
    private static final Path SINGLE = locate("afklive-scripts/caddy/Caddyfile.single-upstream");
    private static final Path PAGE = locate("afklive-scripts/caddy/errors/index.html");
    private static final Path LOGO = locate("afklive-ui/site/public/brand/logo-on-light.svg");
    private static final Path WORKFLOW = locate(".github/workflows/web-build.yml");
    private static final Path OPERATIONS = locate("docs/operations.md");

    @TempDir
    Path dir;

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        return p == null ? Path.of(relative) : p.resolve(relative);
    }

    private static String block(Path caddyfile) throws Exception {
        String s = Files.readString(caddyfile);
        int from = s.indexOf("(afklive_updating) {");
        int to = s.indexOf("# --- end afklive_updating ---");
        assertThat(from).as(caddyfile + " has the afklive_updating snippet").isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return s.substring(from, to);
    }

    /** The site block for afklive.in (where visitors arrive). */
    private static String siteBlock(Path caddyfile) throws Exception {
        String s = Files.readString(caddyfile);
        int from = s.indexOf("\nafklive.in, www.afklive.in {");
        assertThat(from).isGreaterThanOrEqualTo(0);
        return s.substring(from, s.indexOf("\n}", from));
    }

    @Test
    void bothCaddyfilesServeThePageForCaddysOwnErrors() throws Exception {
        String block = block(TWO_SLOT);
        assertThat(block(SINGLE)).as("the same block in both files").isEqualTo(block);
        assertThat(block).contains(
                "handle_errors 502 503 504 {",
                "header Cache-Control \"no-store\"",
                "header Retry-After \"30\"",
                "@api path /api /api/*",
                "header Content-Type \"application/json\"",
                "respond `{\"error\":\"updating\",\"retryAfter\":30}` 503",
                "@page header Accept *text/html*",
                "root * /opt/afklive/errors",
                "rewrite * /index.html",
                "status 503");
        // Every branch answers 503 (temporarily unavailable), never 200: crawlers and monitors must not take it as the site.
        assertThat(block.lines().filter(l -> l.trim().startsWith("respond ")).toList()).allMatch(l -> l.trim().endsWith(" 503"));
        for (Path f : List.of(TWO_SLOT, SINGLE)) {
            assertThat(siteBlock(f)).as(f + " imports it into the site").contains("\timport afklive_updating");
        }
        assertThat(siteBlock(SINGLE)).contains("reverse_proxy 127.0.0.1:8080", "redir @www https://afklive.in{uri} permanent");
        assertThat(siteBlock(TWO_SLOT)).contains("import afklive_web");
    }

    @Test
    void theWebDeployUploadsThePageBeforeTheRestartAndNeverFailsForIt() throws Exception {
        String wf = Files.readString(WORKFLOW);
        int upload = wf.indexOf("- name: Copy updating page to DigitalOcean VM");
        int publish = wf.indexOf("- name: Publish updating page");
        int deploy = wf.indexOf("- name: Deploy\n");
        assertThat(upload).isPositive();
        assertThat(publish).isGreaterThan(upload);
        assertThat(deploy).isGreaterThan(publish);
        String uploadStep = wf.substring(upload, publish);
        assertThat(uploadStep).contains("continue-on-error: true", "source: \"afklive-scripts/caddy/errors/index.html\"",
                "target: \"/opt/afklive/incoming/errors/\"", "strip_components: 3");
        String publishStep = wf.substring(publish, wf.indexOf("- name:", publish + 1));
        // Swapped in with a rename (never half-written), and only a warning until the server is set up.
        assertThat(publishStep).contains("SRC=/opt/afklive/incoming/errors/index.html", "DIR=/opt/afklive/errors",
                "install -m 0644 \"$SRC\" \"$DIR/index.html.new\"", "mv -f \"$DIR/index.html.new\" \"$DIR/index.html\"",
                "::warning::", "exit 0");
        assertThat(publishStep).doesNotContain("sudo");
    }

    @Test
    void thePageStandsOnItsOwn() throws Exception {
        String page = Files.readString(PAGE);
        // No request anywhere: no external (or any) script, stylesheet, image, font or frame.
        Matcher m = Pattern.compile("(?i)\\b(src|href|action|srcset|poster)\\s*=\\s*[\"']([^\"']*)[\"']").matcher(page);
        List<String> links = new ArrayList<>();
        while (m.find()) {
            String attr = m.group(1).toLowerCase(), url = m.group(2);
            links.add(attr + "=" + url);
            assertThat(attr).as(url).isEqualTo("href");
            assertThat(url).as("only links to afklive.in, mail, or this page").matches("|mailto:[^\"]+|https://afklive\\.in(/.*)?");
        }
        assertThat(links).contains("href=", "href=mailto:support@afklive.in");
        assertThat(page).doesNotContainIgnoringCase("<link").doesNotContainIgnoringCase("@import").doesNotContainIgnoringCase("url(")
                .doesNotContainIgnoringCase("<iframe").doesNotContainIgnoringCase("<img");
        assertThat(page).contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        assertThat(page).contains("<noscript><meta http-equiv=\"refresh\"");
        // The retry: asks /healthz, backs off, reloads with a GET of the same address.
        assertThat(page).contains("fetch('/healthz'", "cache: 'no-store'", "location.replace(location.href)", "Math.min(30000",
                "id=\"retry\"", "Try again");
        assertThat(page).contains("AFK Live is updating", "Your live streams keep running.");
        assertThat(page).contains("<section lang=\"hi\">", "आपकी live streams चलती रहेंगी।", "फिर से कोशिश करें");
        assertThat(page).containsPattern("[\\u0900-\\u097F]{4,}");
    }

    @Test
    void thePageCarriesTheGeneratedLogo() throws Exception {
        String page = Files.readString(PAGE);
        int from = page.indexOf("<!-- brand:logo-on-light -->");
        int to = page.indexOf("<!-- /brand -->");
        assertThat(from).isPositive();
        assertThat(page.substring(from + "<!-- brand:logo-on-light -->".length(), to))
                .as("the logo exactly as afklive-ui/brand/build.py writes it (run it to refresh)")
                .isEqualTo(Files.readString(LOGO).strip());
    }

    @Test
    void operationsDocGivesTheOneTimeServerSteps() throws Exception {
        String doc = Files.readString(OPERATIONS);
        assertThat(doc).contains("## While the web app restarts", "install -d -o", "/opt/afklive/incoming/errors",
                "/opt/afklive/errors", "caddy validate --config /etc/caddy/Caddyfile", "systemctl reload caddy",
                "Caddyfile.single-upstream");
    }

    private static String caddy() throws Exception {
        String env = System.getenv("CADDY_BIN");
        if (env != null && !env.isBlank() && Files.isExecutable(Path.of(env))) return env;
        Process which = new ProcessBuilder("/bin/sh", "-c", "command -v caddy").start();
        String out = new String(which.getInputStream().readAllBytes()).trim();
        return which.waitFor() == 0 && !out.isEmpty() ? out : null;
    }

    @Test
    void caddyAcceptsBothFiles() throws Exception {
        String caddy = caddy();
        assumeTrue(caddy != null, "caddy not installed (set CADDY_BIN to run this)");
        for (Path f : List.of(TWO_SLOT, SINGLE)) {
            Process p = new ProcessBuilder(caddy, "validate", "--config", f.toString(), "--adapter", "caddyfile")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(out).isZero();
        }
    }

    @Test
    void caddyAnswersWithThePageJsonOrTextWhenTheAppIsDown() throws Exception {
        String caddy = caddy();
        assumeTrue(caddy != null, "caddy not installed (set CADDY_BIN to run this)");
        int port = freePort(), closed = freePort();
        String block = block(SINGLE).replace("/opt/afklive/errors", PAGE.getParent().toString());
        Path conf = dir.resolve("Caddyfile");
        Files.writeString(conf, "{\n\tadmin off\n\tauto_https off\n\tstorage file_system " + dir.resolve("data") + "\n}\n" + block
                + "http://127.0.0.1:" + port + " {\n\treverse_proxy 127.0.0.1:" + closed + "\n\timport afklive_updating\n}\n");
        Process p = new ProcessBuilder(caddy, "run", "--config", conf.toString(), "--adapter", "caddyfile")
                .redirectErrorStream(true).redirectOutput(dir.resolve("caddy.log").toFile()).start();
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String base = "http://127.0.0.1:" + port;
            HttpResponse<String> html = null;
            for (int i = 0; i < 50 && html == null; i++) {
                try {
                    html = http.send(HttpRequest.newBuilder(URI.create(base + "/studio/streams/3"))
                            .header("Accept", "text/html,application/xhtml+xml").build(), HttpResponse.BodyHandlers.ofString());
                } catch (java.io.IOException notYet) {
                    Thread.sleep(100);
                }
            }
            assertThat(html).as(Files.readString(dir.resolve("caddy.log"))).isNotNull();
            assertThat(html.statusCode()).isEqualTo(503);
            assertThat(html.body()).isEqualTo(Files.readString(PAGE));
            assertThat(html.headers().firstValue("Retry-After")).hasValue("30");
            assertThat(html.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(html.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");

            HttpResponse<String> api = http.send(HttpRequest.newBuilder(URI.create(base + "/api/me"))
                    .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(api.statusCode()).isEqualTo(503);
            assertThat(api.body()).isEqualTo("{\"error\":\"updating\",\"retryAfter\":30}");
            assertThat(api.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(api.headers().firstValue("Retry-After")).hasValue("30");

            HttpResponse<String> other = http.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(other.statusCode()).isEqualTo(503);
            assertThat(other.body()).doesNotContain("<html");
            assertThat(other.headers().firstValue("Cache-Control")).hasValue("no-store");
        } finally {
            p.destroy();
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
