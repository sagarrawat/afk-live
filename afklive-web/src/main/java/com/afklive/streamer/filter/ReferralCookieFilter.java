package com.afklive.streamer.filter;

import com.afklive.streamer.service.ReferralService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * GR-18: a visitor who arrives through an invite link (any page with {@code ?ref=CODE}) keeps the code
 * in a cookie for 30 days, so it still counts if they sign up later or with Google. The first invite
 * wins; the cookie holds only the code, and nothing else about the visitor.
 */
@Component
public class ReferralCookieFilter extends OncePerRequestFilter {

    static final int MAX_AGE = 30 * 24 * 3600;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || request.getParameter("ref") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String code = request.getParameter("ref");
        if (ReferralService.validCode(code) && ReferralService.codeFrom(request).isEmpty()) {
            Cookie c = new Cookie(ReferralService.COOKIE, code);
            c.setPath("/");
            c.setMaxAge(MAX_AGE);
            c.setHttpOnly(true);
            c.setSecure(request.isSecure());
            c.setAttribute("SameSite", "Lax");
            response.addCookie(c);
        }
        chain.doFilter(request, response);
    }
}
