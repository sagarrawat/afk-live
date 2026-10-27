package com.afklive.streamer;

import com.afklive.streamer.endpoint.PublicPages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEO: one address per public page, a real 404 for addresses that don't exist (not a redirect to the
 * noindex sign-in page), the sitemap, and browser caching. Requests are signed out, like a crawler's.
 */
@SpringBootTest
class SeoRoutingTest extends PostgresIntegrationTest {

    static final String EMAIL = "seo-routing@example.com";

    @Autowired WebApplicationContext context;
    @Autowired com.afklive.streamer.repository.UserRepository users;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (users.findByEmail(EMAIL).isEmpty()) {
            users.save(new com.afklive.streamer.model.User(EMAIL));
        }
    }

    @Test
    void unknownPublicAddressesAreA404NotTheSignInPage() throws Exception {
        for (String path : new String[]{"/blog", "/Pricing", "/index.html", "/manifest.webmanifest", "/nope/deeper", "/pricing/extra"}) {
            mvc.perform(get(path).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
            mvc.perform(head(path)).andExpect(status().isNotFound());
        }
        // Everything that exists still asks a signed-out visitor to sign in, as before.
        for (String path : new String[]{"/studio", "/studio/streams/5", "/api/streams", "/api/mock/analytics", "/remote.html", "/app/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        }
        // Other methods keep the old behaviour too.
        mvc.perform(post("/blog")).andExpect(status().is4xxClientError());
        // Signed in, an unknown address was already a 404.
        mvc.perform(get("/blog").with(user(EMAIL).roles("USER"))).andExpect(status().isNotFound());
    }

    @Test
    void trailingSlashesRedirectForGoodToTheCleanAddress() throws Exception {
        String[][] cases = {
                {"/pricing/", "/pricing"}, {"/hi/", "/hi"}, {"/help/", "/help"}, {"/privacy/", "/privacy"}, {"/terms/", "/terms"}, {"/contact/", "/contact"}, {"/refund-policy/", "/refund-policy"},
                {"/status/", "/status"}, {"/for/lofi/", "/for/lofi"}, {"/help/youtube-stream-key/", "/help/youtube-stream-key"},
                {"/login/", "/login"}, {"/register/", "/register"}, {"/pricing//", "/pricing"}, {"/for/lofi/?utm_source=x", "/for/lofi?utm_source=x"},
        };
        for (String[] c : cases) {
            mvc.perform(get(c[0])).andExpect(status().isMovedPermanently()).andExpect(header().string("Location", c[1]));
        }
        // Not a page: no redirect to an address that doesn't exist.
        mvc.perform(get("/for/nope/")).andExpect(status().isNotFound());
        mvc.perform(get("/nope/")).andExpect(status().isNotFound());
        // The app and API are untouched.
        mvc.perform(get("/studio/")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/studio/").with(user(EMAIL).roles("USER"))).andExpect(status().isOk());
    }

    @Test
    void builtPageCopiesUnderSiteRedirectToTheirPublicAddress() throws Exception {
        String[][] cases = {
                {"/site/index.html", "/"}, {"/site/hi.html", "/hi"}, {"/site/pricing.html", "/pricing"}, {"/site/help.html", "/help"},
                {"/site/for-lofi.html", "/for/lofi"}, {"/site/help-youtube-preparing.html", "/help/youtube-preparing"},
                {"/site/privacy.html", "/privacy"}, {"/site/terms.html", "/terms"}, {"/site/contact.html", "/contact"}, {"/site/refund-policy.html", "/refund-policy"}, {"/site/status.html", "/status"},
        };
        for (String[] c : cases) {
            mvc.perform(get(c[0])).andExpect(status().isMovedPermanently()).andExpect(header().string("Location", c[1]));
        }
        for (String path : new String[]{"/site", "/site/", "/site/for-nope.html", "/site/other.html"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        // Stylesheets, scripts and images under /site/ are served as they are.
        mvc.perform(get("/site/assets/seo-fixture-Ab12Cd34.css")).andExpect(status().isOk());
        // Spring's own forwards to the built files still work.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/index.html"));
        mvc.perform(get("/for/lofi")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/for-lofi.html"));
    }

    @Test
    void oldAddressesMovedForGood() throws Exception {
        String[][] cases = {{"/home.html", "/"}, {"/pricing.html", "/pricing"}, {"/privacy.html", "/privacy"}, {"/terms.html", "/terms"},
                {"/features", "/#how"}, {"/features.html", "/#how"}};
        for (String[] c : cases) {
            mvc.perform(get(c[0])).andExpect(status().isMovedPermanently()).andExpect(redirectedUrl(c[1]));
        }
    }

    @Test
    void sitemapListsEveryPublicPageAndNothingElse() throws Exception {
        String xml = mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(not(containsString("/login"))))
                .andExpect(content().string(not(containsString("/register"))))
                .andExpect(content().string(not(containsString("/status"))))
                .andReturn().getResponse().getContentAsString();
        List<String> listed = new ArrayList<>();
        Matcher m = Pattern.compile("<loc>https://afklive\\.in(/[^<]*)</loc>").matcher(xml);
        while (m.find()) {
            listed.add(m.group(1));
        }
        assertThat(listed).containsExactlyElementsOf(PublicPages.INDEXABLE.keySet());
        // Every listed address answers 200 to a signed-out visitor, with its own page.
        for (String path : listed) {
            var page = mvc.perform(get(path)).andExpect(status().isOk());
            if (path.equals("/contact")) {
                // Served by ContactPage, which fills the business details in (a fixture page in tests).
                page.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                        .andExpect(header().string("Cache-Control", "no-cache, public"));
            } else {
                page.andExpect(forwardedUrl("/site/" + PublicPages.INDEXABLE.get(path)));
            }
        }
        // Every page the site build makes has its public address, and all but /status are in the sitemap.
        Path site = Path.of("..", "afklive-ui", "site");
        try (Stream<Path> files = Files.list(site)) {
            for (String file : files.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".html")).toList()) {
                assertThat(PublicPages.pathForFile(file)).as(file).isPresent();
                if (!file.equals("status.html")) {
                    assertThat(listed).as(file).contains(PublicPages.pathForFile(file).orElseThrow());
                }
            }
        }
        mvc.perform(get("/robots.txt")).andExpect(content().string(containsString("Sitemap: https://afklive.in/sitemap.xml")));
    }

    @Test
    void theHealthCheckIsLeftAlone() throws Exception {
        // /healthz (its own security chain) is untouched by the clean-URL filter, the 404 rule and the caching.
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{get("/healthz"), head("/healthz")}) {
            var r = mvc.perform(request)
                    .andExpect(header().string("X-Robots-Tag", "noindex"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(header().doesNotExist("Location"))
                    .andReturn().getResponse();
            assertThat(r.getStatus()).isIn(200, 503);
        }
        mvc.perform(get("/healthz/")).andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/sitemap.xml")).andExpect(content().string(not(containsString("healthz"))));
    }

    @Test
    void caddySendsWwwAndHttpToOneHttpsAddress() throws Exception {
        String caddy = Files.readString(Path.of("..", "afklive-scripts", "caddy", "Caddyfile"));
        assertThat(caddy).doesNotContain("afk.live")
                .contains("http://afklive.in, http://www.afklive.in {\n\tredir https://afklive.in{uri} permanent\n}")
                .contains("afklive.in, www.afklive.in {\n\t@www host www.afklive.in\n\tredir @www https://afklive.in{uri} permanent");
    }

    @Test
    void sitemapDatesComeFromTheBuild() {
        String xml = com.afklive.streamer.endpoint.SitemapController.render(
                java.util.Map.of("/", "index.html", "/hi", "hi.html"),
                java.util.Map.of("index.html", "2026-10-23T18:02:11+05:30", "hi.html", "not a date"));
        assertThat(xml).contains("<loc>https://afklive.in/</loc>\n    <lastmod>2026-10-23</lastmod>");
        assertThat(xml).contains("<loc>https://afklive.in/hi</loc>\n  </url>");
        assertThat(xml).doesNotContain("changefreq").doesNotContain("priority");
    }

    @Test
    void staticFilesAreCachedAndSignedInResponsesAreNot() throws Exception {
        String year = "max-age=31536000, public, immutable";
        mvc.perform(get("/site/assets/seo-fixture-Ab12Cd34.css")).andExpect(header().string("Cache-Control", year))
                .andExpect(header().doesNotExist("Pragma")).andExpect(header().doesNotExist("Expires"));
        mvc.perform(get("/app/assets/seo-fixture-Ab12Cd34.js")).andExpect(header().string("Cache-Control", year));
        mvc.perform(get("/site/img/seo-fixture.svg")).andExpect(header().string("Cache-Control", "max-age=86400, public"));
        mvc.perform(get("/favicon.ico")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=86400, public"));
        mvc.perform(get("/site/seo-fixture-page.css")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache, public"));
        // Signed-in pages and the API keep Spring Security's no-store.
        mvc.perform(get("/api/me").with(user(EMAIL).roles("USER")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/studio").with(user(EMAIL).roles("USER")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/login")).andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
