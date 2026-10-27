package com.afklive.streamer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SEO through a real Tomcat, where errors and forwards are real dispatches (MockMvc only records them):
 * a crawler gets the styled 404 page, and a page's caching survives Spring's forward to the built file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeoHttpTest extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "text/html")
                .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnknownAddressIsTheSitesOwn404Page() throws Exception {
        for (String path : new String[]{"/blog", "/Pricing", "/site/", "/site/other.html"}) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(404);
            assertThat(r.headers().firstValue("Location")).as(path).isEmpty();
            assertThat(r.body()).as(path).contains("This page is off air.").contains("noindex");
        }
        HttpResponse<String> slash = get("/pricing/");
        assertThat(slash.statusCode()).isEqualTo(301);
        assertThat(slash.headers().firstValue("Location")).hasValue("/pricing");
        assertThat(get("/studio").statusCode()).isEqualTo(302);
    }

    @Test
    void pagesAreRecheckedRatherThanNeverStored() throws Exception {
        assumeTrue(new ClassPathResource("static/site/index.html").exists(), "needs the site build (not -DskipUi)");
        for (String path : new String[]{"/", "/pricing", "/for/lofi", "/help/youtube-stream-key"}) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).as(path).hasValue("no-cache, public");
            assertThat(r.headers().firstValue("Pragma")).as(path).isEmpty();
        }
    }
}
