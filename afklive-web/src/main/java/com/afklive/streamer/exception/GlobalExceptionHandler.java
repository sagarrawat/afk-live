package com.afklive.streamer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * Turns exceptions into JSON responses. Validation problems (IllegalArgumentException,
 * IllegalStateException) carry user-facing messages and become 400s; unexpected errors are
 * logged in full but return a generic message so internals never leak to clients.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** e.g. "com.afklive.streamer.model.PlanType" or "java.lang.String" in a framework message. */
    private static final java.util.regex.Pattern PACKAGE_NAME =
            java.util.regex.Pattern.compile("\\b(com|org|java|javax|jakarta)\\.[a-z_]+\\.[a-z_]");

    static final String GENERIC_ERROR = "Something went wrong on our side. Please try again.";

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage();
        if (message != null && (message.contains("not connected to YouTube") || message.contains("Authentication failed"))) {
            // 401 triggers the "Connect" flow in the frontend
            return body(HttpStatus.UNAUTHORIZED, message);
        }
        log.error("Unhandled error", e);
        return serverError();
    }

    /**
     * OPS-2: only a real server error says "our side", with the request's code (it's in the log line
     * above, so an admin can find it).
     */
    static ResponseEntity<Map<String, Object>> serverError() {
        String id = org.slf4j.MDC.get(com.afklive.streamer.filter.RequestIdFilter.MDC_KEY);
        if (id == null) {
            return body(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", GENERIC_ERROR + " Code: " + id, "requestId", id));
    }

    @ExceptionHandler(NotFoundException.class)
    public Object handleNotFound(NotFoundException e, jakarta.servlet.http.HttpServletRequest request) {
        if (isPage(request)) {
            // A person opened a page that doesn't exist: show the site's 404 page, not JSON.
            org.springframework.web.servlet.ModelAndView page = new org.springframework.web.servlet.ModelAndView("error/404");
            page.setStatus(HttpStatus.NOT_FOUND);
            return page;
        }
        return body(HttpStatus.NOT_FOUND, userFacing(e.getMessage()));
    }

    /** A browser page load (not an API call). */
    static boolean isPage(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return !uri.startsWith("/api/") && !uri.startsWith("/internal/") && accept != null && accept.contains("text/html");
    }

    /** Malformed or mistyped JSON: the client's mistake, not a server error. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "The request body isn't valid JSON for this action.");
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, userFacing(e.getMessage()));
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String, Object>> handleTooMany(TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, e.retryAfterSeconds())))
                .body(Map.of("success", false, "message", userFacing(e.getMessage())));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, userFacing(e.getMessage()));
    }

    /**
     * Our own validation errors carry messages meant for users; JDK/framework ones (enum parsing,
     * number parsing, null checks) leak class names, so those get a generic message instead.
     */
    static String userFacing(String message) {
        if (message == null || message.isBlank()) {
            return "Invalid request.";
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        if (PACKAGE_NAME.matcher(m).find()
                || m.startsWith("no enum constant") || m.startsWith("for input string")
                || m.startsWith("cannot invoke") || m.contains("exception")) {
            return "Invalid request.";
        }
        return message;
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        log.warn("Rejected unsafe request: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "Invalid file name.");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "message", message));
    }
}
