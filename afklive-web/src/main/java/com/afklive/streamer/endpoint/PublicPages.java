package com.afklive.streamer.endpoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The public site's pages (afklive-ui/site, built into static/site/): each public address and the built
 * file Spring forwards it to. One list for the routes (HomeController), the sitemap (SitemapController)
 * and the clean-URL redirects (SiteUrlFilter). A new use-case page or help article needs its slug here
 * and in site/use-cases.py or site/help.py (site/src/help-pages.test.ts and SitemapTest check both).
 */
public final class PublicPages {

    private PublicPages() {
    }

    /** MK-3: the use-case pages (afklive-ui/site/use-cases.py writes site/for-&lt;slug&gt;.html). */
    public static final List<String> USE_CASES = List.of("lofi", "devotional", "mobile-gaming", "podcasts");

    /** MK-4: the help articles (afklive-ui/site/help.py writes site/help-&lt;slug&gt;.html). */
    public static final List<String> HELP_ARTICLES = List.of("youtube-stream-key", "youtube-preparing", "vertical-or-landscape",
            "live-here-not-on-youtube", "phone-streaming", "loops-and-youtube-rules", "titles-and-thumbnails", "music-on-your-live");

    /** Public address → built file under static/site/, for every page search engines should index, in sitemap order. */
    public static final Map<String, String> INDEXABLE;

    /** Public pages that exist but stay out of the sitemap: /status (noindex: thin, filled in by script). */
    public static final Map<String, String> NOT_INDEXED = Map.of("/status", "status.html");

    static {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("/", "index.html");
        pages.put("/hi", "hi.html");
        pages.put("/pricing", "pricing.html");
        for (String slug : USE_CASES) {
            pages.put("/for/" + slug, "for-" + slug + ".html");
        }
        pages.put("/help", "help.html");
        for (String slug : HELP_ARTICLES) {
            pages.put("/help/" + slug, "help-" + slug + ".html");
        }
        pages.put("/privacy", "privacy.html");
        pages.put("/terms", "terms.html");
        INDEXABLE = java.util.Collections.unmodifiableMap(pages);
    }

    /** The built file a public address serves, if it is one of the site's pages. */
    public static Optional<String> fileFor(String path) {
        String file = INDEXABLE.get(path);
        return Optional.ofNullable(file != null ? file : NOT_INDEXED.get(path));
    }

    /** The public address of a built file (for-lofi.html → /for/lofi), if it has one. */
    public static Optional<String> pathForFile(String file) {
        for (Map<String, String> pages : List.of(INDEXABLE, NOT_INDEXED)) {
            for (Map.Entry<String, String> e : pages.entrySet()) {
                if (e.getValue().equals(file)) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /** Addresses with a clean form that a trailing slash should redirect to: the site's pages plus sign-in. */
    public static boolean hasCleanForm(String path) {
        return fileFor(path).isPresent() || path.equals("/login") || path.equals("/register");
    }
}
