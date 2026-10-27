package com.afklive.streamer.endpoint;

import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.security.SignInTargets;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/** The public site's pages; the list of them is {@link PublicPages}. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:" + SignInTargets.home(authentication);
        }
        return "forward:/site/index.html";
    }

    /**
     * MK-1: the homepage in Hindi (afklive-ui/site/hi.html). Served to everyone, signed in or not, like
     * the other public pages; its buttons lead into the (English) app.
     */
    @GetMapping("/hi")
    public String hindiHome() {
        return "forward:/site/hi.html";
    }

    /** The old server-rendered admin moved into the app; old bookmarks land in the admin console. */
    @GetMapping({"/admin", "/admin/**"})
    public String oldAdmin() {
        return "redirect:" + SignInTargets.ADMIN_HOME;
    }

    /** MK-3: the use-case pages (afklive-ui/site/use-cases.py). */
    static final List<String> USE_CASES = PublicPages.USE_CASES;

    @GetMapping("/for/{slug}")
    public String useCase(@PathVariable String slug) {
        if (!USE_CASES.contains(slug)) {
            throw new NotFoundException("Page not found.");
        }
        return "forward:/site/for-" + slug + ".html";
    }

    /** MK-4: the help articles (afklive-ui/site/help.py writes site/help-&lt;slug&gt;.html). */
    public static final List<String> HELP_ARTICLES = PublicPages.HELP_ARTICLES;

    @GetMapping("/help")
    public String help() {
        return "forward:/site/help.html";
    }

    @GetMapping("/help/{slug}")
    public String helpArticle(@PathVariable String slug) {
        if (!HELP_ARTICLES.contains(slug)) {
            throw new NotFoundException("Page not found.");
        }
        return "forward:/site/help-" + slug + ".html";
    }

    /** The help articles in Hindi (site/help.py HI_ARTICLES); served to everyone, like /hi. */
    @GetMapping("/hi/help")
    public String hindiHelp() {
        return "forward:/site/hi-help.html";
    }

    @GetMapping("/hi/help/{slug}")
    public String hindiHelpArticle(@PathVariable String slug) {
        if (!PublicPages.HINDI_HELP_ARTICLES.contains(slug)) {
            throw new NotFoundException("Page not found.");
        }
        return "forward:/site/hi-help-" + slug + ".html";
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
