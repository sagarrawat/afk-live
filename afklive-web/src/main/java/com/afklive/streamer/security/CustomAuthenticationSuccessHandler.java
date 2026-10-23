package com.afklive.streamer.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        // ON-6: a new password sign-in starts without the second step done (AccountGateFilter asks for it).
        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(AccountGate.VERIFIED);
        }
        Set<String> roles = AuthorityUtils.authorityListToSet(authentication.getAuthorities());

        if (roles.contains("ROLE_ADMIN")) {
            getRedirectStrategy().sendRedirect(request, response, "/admin");
        } else {
            // For normal users, we can fall back to default behavior (handling saved requests)
            // But we need to set the default target url first just in case
            setDefaultTargetUrl("/studio");
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
