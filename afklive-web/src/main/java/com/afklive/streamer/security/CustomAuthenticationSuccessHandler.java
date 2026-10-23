package com.afklive.streamer.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        // ON-6: a new password sign-in starts without the second step done (AccountGateFilter asks for it).
        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(AccountGate.VERIFIED);
        }
        String home = SignInTargets.home(authentication);
        if (SignInTargets.ADMIN_HOME.equals(home)) {
            getRedirectStrategy().sendRedirect(request, response, home);
        } else {
            // Normal users go back to the page they asked for (saved request), else the studio.
            setDefaultTargetUrl(home);
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
