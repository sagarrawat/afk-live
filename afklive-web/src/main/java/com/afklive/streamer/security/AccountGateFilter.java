package com.afklive.streamer.security;

import com.afklive.streamer.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Runs in the security chain after sign-in filters and before authorization (see {@link AccountGate}):
 * <ul>
 *   <li>Account scheduled for deletion: the session ends and the browser is sent to sign-in, which
 *       offers to keep the account.</li>
 *   <li>Two-step sign-in not finished: only the code page (and sign-out) work; the API answers 401.</li>
 * </ul>
 */
public class AccountGateFilter extends OncePerRequestFilter {

    public static final String TWO_FACTOR_PAGE = "/login/2fa";
    private static final Set<String> PENDING_ALLOWED = Set.of(TWO_FACTOR_PAGE, "/logout", "/error", "/favicon.ico");

    private final AccountGate gate;

    public AccountGateFilter(AccountGate gate) {
        this.gate = gate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = path(request);
        return p.startsWith("/site/") || p.startsWith("/app/assets/") || p.startsWith("/css/") || p.startsWith("/js/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        String email = SecurityUtils.getEmail(auth);
        Optional<AccountGate.State> state = email == null ? Optional.empty() : gate.state(email);
        if (state.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String path = path(request);
        if (state.get().deletionScheduledFor() != null) {
            closeSession(request, response, email);
            deny(request, response, path, "/login",
                    "This account is scheduled for deletion. Sign in again to keep it.");
            return;
        }
        if (state.get().twoFactor()) {
            HttpSession session = request.getSession(false);
            Object verified = session == null ? null : session.getAttribute(AccountGate.VERIFIED);
            if (!email.equals(verified) && !PENDING_ALLOWED.contains(path)) {
                deny(request, response, path, TWO_FACTOR_PAGE,
                        "Enter the code from your authenticator app to finish signing in.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Signs the browser out but remembers which account it proved it owns, so sign-in can offer to restore it. */
    private static void closeSession(HttpServletRequest request, HttpServletResponse response, String email) {
        SecurityContextHolder.clearContext();
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true).setAttribute(AccountGate.RESTORE, email);
        Cookie rememberMe = new Cookie("remember-me", "");
        rememberMe.setMaxAge(0);
        rememberMe.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        response.addCookie(rememberMe);
    }

    private static void deny(HttpServletRequest request, HttpServletResponse response, String path, String page, String message)
            throws IOException {
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"" + message.replace("\"", "\\\"") + "\"}");
            return;
        }
        response.sendRedirect(request.getContextPath() + page);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
