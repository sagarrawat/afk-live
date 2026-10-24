package com.afklive.streamer.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OPS-2: a form opened before a deploy (or in another tab before signing in) carries a security token
 * that is no longer valid. That's not a server error: the sign-in forms open again with "This page
 * expired. Please try again.", the app's API answers a 403 marked {@code "code":"csrf"} (the app then
 * fetches a fresh token and retries once), and any other page says the page expired.
 */
@Slf4j
public class FriendlyAccessDeniedHandler implements AccessDeniedHandler {

    public static final String EXPIRED_MESSAGE = "This page expired. Please try again.";
    /** Set on the request when the 403 is an expired page, so the error page words it that way. */
    public static final String EXPIRED_ATTRIBUTE = FriendlyAccessDeniedHandler.class.getName() + ".expired";

    /** The server-rendered forms, and where each one opens again. */
    private static final Map<String, String> FORMS = Map.of(
            "/login", "/login",
            "/register", "/register",
            "/forgot-password", "/forgot-password",
            "/reset-password", "/reset-password",
            "/login/2fa", "/login/2fa",
            "/account/restore", "/login",
            "/logout", "/login");

    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException, ServletException {
        if (!(e instanceof CsrfException) || response.isCommitted()) {
            fallback.handle(request, response, e);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        log.info("Expired form or token on {} {}: {}", request.getMethod(), path, e.getClass().getSimpleName());
        String form = FORMS.get(path);
        if (form != null && !isApi(path)) {
            response.sendRedirect(request.getContextPath() + expiredUrl(form, request.getParameter("token")));
            return;
        }
        if (isApi(path) || !acceptsHtml(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"success\":false,\"code\":\"csrf\",\"message\":\"" + EXPIRED_MESSAGE + "\"}");
            return;
        }
        request.setAttribute(EXPIRED_ATTRIBUTE, Boolean.TRUE);
        response.sendError(HttpServletResponse.SC_FORBIDDEN, EXPIRED_MESSAGE);
    }

    /** The form again, flagged as expired; a password reset keeps its link's token. */
    static String expiredUrl(String form, String token) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(form);
        if ("/reset-password".equals(form) && token != null && !token.isBlank()) {
            b.queryParam("token", "{token}");
        }
        b.queryParam("expired");
        return "/reset-password".equals(form) && token != null && !token.isBlank()
                ? b.encode().buildAndExpand(token).toUriString()
                : b.build().toUriString();
    }

    private static boolean isApi(String path) {
        return path.startsWith("/api/") || path.startsWith("/internal/");
    }

    private static boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}
