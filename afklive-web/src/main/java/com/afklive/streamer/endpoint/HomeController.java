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
                return "redirect:/admin";
            }
            return "redirect:/studio";
        }
        return "forward:/home.html";
    }

    @GetMapping("/terms")
    public String terms() {
        return "forward:/terms.html";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "forward:/privacy.html";
    }
}
