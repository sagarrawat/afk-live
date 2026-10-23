package com.afklive.streamer.endpoint;

import com.afklive.streamer.security.SignInTargets;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Set;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:" + SignInTargets.home(authentication);
        }
        return "forward:/site/index.html";
    }

    /** The old server-rendered admin moved into the app; old bookmarks land in the admin console. */
    @GetMapping({"/admin", "/admin/**"})
    public String oldAdmin() {
        return "redirect:" + SignInTargets.ADMIN_HOME;
    }

    /** MK-3: the use-case pages (afklive-ui/site/use-cases.py). */
    static final Set<String> USE_CASES = Set.of("lofi", "devotional", "mobile-gaming", "podcasts");

    @GetMapping("/for/{slug}")
    public String useCase(@org.springframework.web.bind.annotation.PathVariable String slug) {
        if (!USE_CASES.contains(slug)) {
            throw new com.afklive.streamer.exception.NotFoundException("Page not found.");
        }
        return "forward:/site/for-" + slug + ".html";
    }

    /** MK-4: the help articles (afklive-ui/site/help.py writes site/help-&lt;slug&gt;.html). */
    public static final Set<String> HELP_ARTICLES = Set.of("youtube-stream-key", "youtube-preparing", "vertical-or-landscape",
            "live-here-not-on-youtube", "phone-streaming");

    @GetMapping("/help")
    public String help() {
        return "forward:/site/help.html";
    }

    @GetMapping("/help/{slug}")
    public String helpArticle(@org.springframework.web.bind.annotation.PathVariable String slug) {
        if (!HELP_ARTICLES.contains(slug)) {
            throw new com.afklive.streamer.exception.NotFoundException("Page not found.");
        }
        return "forward:/site/help-" + slug + ".html";
    }

    @GetMapping("/terms")
    public String terms() {
        return "forward:/site/terms.html";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "forward:/site/privacy.html";
    }
}
