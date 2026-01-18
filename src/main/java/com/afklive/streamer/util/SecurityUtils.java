package com.afklive.streamer.util;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import java.security.Principal;

public class SecurityUtils {
    public static String getEmail(Principal principal) {
        if (principal instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) principal;
            String email = token.getPrincipal().getAttribute("email");
            if (email != null) return email;
        }
        return principal.getName();
    }
}
