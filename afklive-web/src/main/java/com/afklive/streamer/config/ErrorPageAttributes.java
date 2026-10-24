package com.afklive.streamer.config;

import com.afklive.streamer.filter.RequestIdFilter;
import com.afklive.streamer.security.FriendlyAccessDeniedHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * OPS-2: what the error page (templates/error.html) and JSON errors know besides Spring's defaults:
 * the request id, a plain-language heading and explanation for the status, and whether the page had
 * merely expired. Server errors (5xx) are logged here with the request id, so the id a creator quotes
 * finds the log line.
 */
@Component
@Slf4j
public class ErrorPageAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attrs = super.getErrorAttributes(webRequest, options);
        int status = attrs.get("status") instanceof Integer s ? s : 500;
        boolean expired = webRequest.getAttribute(FriendlyAccessDeniedHandler.EXPIRED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) != null;
        String requestId = null;
        if (webRequest instanceof ServletWebRequest swr) {
            requestId = RequestIdFilter.of(swr.getRequest());
        }
        if (requestId == null) {
            requestId = RequestIdFilter.newId();
        }
        attrs.put("requestId", requestId);
        Wording w = wording(status, expired);
        attrs.put("heading", w.heading());
        attrs.put("explanation", w.explanation());
        attrs.put("serverError", status >= 500);
        if (status >= 500) {
            logServerError(webRequest, status, requestId);
        }
        return attrs;
    }

    record Wording(String heading, String explanation) {
    }

    /** Only a 5xx is "our side"; a 4xx says what actually happened. */
    static Wording wording(int status, boolean expired) {
        if (expired) {
            return new Wording("This page expired.", "Go back, reload the page and try again.");
        }
        if (status >= 500) {
            return new Wording("Something went wrong on our side.",
                    "Try again in a moment. If it keeps happening, write to support@afklive.in and mention the code below.");
        }
        return switch (status) {
            case 404, 410 -> new Wording("We can't find that page.", "The link may be old or mistyped.");
            case 401 -> new Wording("Please sign in.", "Sign in to open this page.");
            case 403 -> new Wording("You can't open this page.", "It belongs to another account, or needs a different sign-in.");
            case 405, 400 -> new Wording("That didn't work.", "Go back, reload the page and try again.");
            case 413 -> new Wording("That's too large.", "Try a smaller file.");
            case 429 -> new Wording("Too many tries.", "Wait a minute, then try again.");
            default -> new Wording("That didn't work.", "Go back and try again.");
        };
    }

    private void logServerError(WebRequest webRequest, int status, String requestId) {
        String previous = MDC.get(RequestIdFilter.MDC_KEY);
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        try {
            Object path = webRequest.getAttribute("jakarta.servlet.error.request_uri", RequestAttributes.SCOPE_REQUEST);
            String method = webRequest instanceof ServletWebRequest swr ? methodOf(swr.getRequest()) : "?";
            Throwable error = getError(webRequest);
            if (error != null) {
                log.error("Request {} failed with {} on {} {}", requestId, status, method, path, error);
            } else {
                log.error("Request {} failed with {} on {} {}", requestId, status, method, path);
            }
        } finally {
            if (previous == null) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            } else {
                MDC.put(RequestIdFilter.MDC_KEY, previous);
            }
        }
    }

    private static String methodOf(HttpServletRequest request) {
        return request.getMethod();
    }
}
