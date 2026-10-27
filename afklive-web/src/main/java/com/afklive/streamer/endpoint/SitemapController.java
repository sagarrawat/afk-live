package com.afklive.streamer.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * /sitemap.xml, written from {@link PublicPages} so a new page can't be left out and pages that aren't
 * indexed (sign-in, register, status) can't creep in. lastmod is each page's last change: the site build
 * (vite.site.config.ts) writes static/site/lastmod.json from git, or from the file's modified time.
 */
@RestController
@Slf4j
public class SitemapController {

    static final String ORIGIN = "https://afklive.in";
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final String xml;

    public SitemapController() {
        this.xml = render(PublicPages.INDEXABLE, readLastModified());
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic()).body(xml);
    }

    /** The sitemap for these pages (address → built file) and dates (built file → ISO date or date-time). */
    public static String render(Map<String, String> pages, Map<String, String> lastModified) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        pages.forEach((path, file) -> {
            out.append("  <url>\n    <loc>").append(ORIGIN).append(path).append("</loc>\n");
            String date = lastModified.get(file);
            if (date != null && date.length() >= 10 && DATE.matcher(date.substring(0, 10)).matches()) {
                out.append("    <lastmod>").append(date, 0, 10).append("</lastmod>\n");
            }
            out.append("  </url>\n");
        });
        return out.append("</urlset>\n").toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readLastModified() {
        ClassPathResource file = new ClassPathResource("static/site/lastmod.json");
        if (!file.exists()) {
            return Map.of(); // a build without the site (-DskipUi): no dates rather than wrong ones
        }
        try (InputStream in = file.getInputStream()) {
            return new ObjectMapper().readValue(in, Map.class);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read static/site/lastmod.json; the sitemap has no lastmod dates.", e);
            return Map.of();
        }
    }
}
