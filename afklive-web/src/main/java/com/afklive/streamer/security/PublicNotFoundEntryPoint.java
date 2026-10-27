package com.afklive.streamer.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.List;

/**
 * What a signed-out visitor gets for an address that needs sign-in (SEO). Anything that exists (the app,
 * the API, a controller, a static file such as remote.html) still goes to the sign-in page, as before. An
 * address that matches nothing at all (a typo, /blog, /Pricing) is a real 404 with the site's error page,
 * not a redirect to a noindex sign-in page: search engines then drop it instead of learning that the
 * whole host is a login wall.
 */
public class PublicNotFoundEntryPoint implements AuthenticationEntryPoint {

    /** Always sign-in first, whatever the path: the app, the API and sign-in flows. */
    static final List<String> APP_PREFIXES = List.of("/studio", "/app", "/api", "/admin", "/internal", "/oauth2", "/login",
            "/logout", "/account", "/actuator", "/classic");

    /** Handler mappings that decide whether an address exists (controllers and view controllers, not the static fallback). */
    private static final List<String> MAPPINGS = List.of("requestMappingHandlerMapping", "viewControllerHandlerMapping");

    private final AuthenticationEntryPoint signIn = new LoginUrlAuthenticationEntryPoint("/login");
    private final ApplicationContext context;

    public PublicNotFoundEntryPoint(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, ServletException {
        String method = request.getMethod();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (("GET".equals(method) || "HEAD".equals(method)) && !isAppPath(path) && !exists(request, path)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        signIn.commence(request, response, e);
    }

    static boolean isAppPath(String path) {
        for (String prefix : APP_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** True when a controller, a view controller or a file in static/ answers this address. When unsure, true. */
    private boolean exists(HttpServletRequest request, String path) {
        for (String name : MAPPINGS) {
            if (!context.containsBean(name)) {
                continue;
            }
            RequestPath previous = ServletRequestPathUtils.hasParsedRequestPath(request) ? ServletRequestPathUtils.getParsedRequestPath(request) : null;
            try {
                ServletRequestPathUtils.parseAndCache(request);
                if (context.getBean(name, HandlerMapping.class).getHandler(request) != null) {
                    return true;
                }
            } catch (Exception ex) {
                return true; // e.g. the address exists for another method: keep the old behaviour
            } finally {
                ServletRequestPathUtils.setParsedRequestPath(previous, request);
            }
        }
        if (path.contains("..") || path.contains("\\")) {
            return true;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.contains(".") && new ClassPathResource("static" + path).isReadable();
    }
}
