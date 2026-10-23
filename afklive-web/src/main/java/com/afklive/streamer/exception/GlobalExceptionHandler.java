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

    private static final String GENERIC_ERROR = "Something went wrong. Please try again.";

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage();
        if (message != null && (message.contains("not connected to YouTube") || message.contains("Authentication failed"))) {
            // 401 triggers the "Connect" flow in the frontend
            return body(HttpStatus.UNAUTHORIZED, message);
        }
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR);
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
