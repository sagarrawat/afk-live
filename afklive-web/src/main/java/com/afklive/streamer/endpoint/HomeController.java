package com.afklive.streamer.endpoint;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Set;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            Set<String> roles = AuthorityUtils.authorityListToSet(authentication.getAuthorities());
            if (roles.contains("ROLE_ADMIN")) {
                return "redirect:/studio/admin";
            }
            return "redirect:/studio";
        }
        return "forward:/site/index.html";
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

    @GetMapping("/terms")
    public String terms() {
        return "forward:/site/terms.html";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "forward:/site/privacy.html";
    }
}
