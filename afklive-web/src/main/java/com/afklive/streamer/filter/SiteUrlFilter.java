package com.afklive.streamer.filter;

import com.afklive.streamer.endpoint.PublicPages;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One address per public page, for people and search engines (SEO): runs first in the security chain,
 * before sign-in is checked, so these never bounce to the sign-in page.
 * <ul>
 *   <li>{@code /pricing/}, {@code /help/youtube-stream-key/}: 301 to the address without the slash.</li>
 *   <li>{@code /site/pricing.html}, {@code /site/for-lofi.html}: the built files the pages forward to;
 *       301 to the public address ({@code /pricing}, {@code /for/lofi}). Other {@code /site/*.html} and
 *       the {@code /site/} folder itself are a 404. Stylesheets, scripts and images under /site/ are untouched.</li>
 * </ul>
 * Only the browser's own request is checked: Spring's internal forwards to /site/*.html pass through.
 */
public class SiteUrlFilter extends OncePerRequestFilter {

    private static final Pattern BUILT_PAGE = Pattern.compile("^/site/([a-z0-9-]+\\.html)$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (request.getDispatcherType() != DispatcherType.REQUEST || !("GET".equals(method) || "HEAD".equals(method))) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/site") || path.equals("/site/")) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        Matcher built = BUILT_PAGE.matcher(path);
        if (built.matches()) {
            Optional<String> clean = PublicPages.pathForFile(built.group(1));
            if (clean.isPresent()) {
                movedTo(request, response, clean.get());
            } else {
                response.sendError(HttpStatus.NOT_FOUND.value());
            }
            return;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            String trimmed = path.replaceAll("/+$", "");
            if (PublicPages.hasCleanForm(trimmed.isEmpty() ? "/" : trimmed)) {
                movedTo(request, response, trimmed.isEmpty() ? "/" : trimmed);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void movedTo(HttpServletRequest request, HttpServletResponse response, String path) {
        String query = request.getQueryString();
        response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
        response.setHeader("Location", request.getContextPath() + path + (query == null ? "" : "?" + query));
    }
}
