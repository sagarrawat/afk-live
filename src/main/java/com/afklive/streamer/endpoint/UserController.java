package com.afklive.streamer.endpoint;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
public class UserController {

    @GetMapping("/api/user-info")
    public Map<String, Object> getUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            // Return empty object for Guest
            return Collections.emptyMap();
        }
        // Return User Details (Name, Picture, Email)
        return Map.of(
                "name",
                principal.getAttribute("name"),
                "email",
                principal.getAttribute("email"),
                "picture",
                principal.getAttribute("picture")
        );
    }
}