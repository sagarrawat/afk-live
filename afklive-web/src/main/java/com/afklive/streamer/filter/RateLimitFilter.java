package com.afklive.streamer.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.Set;

/**
 * Per-client rate limiting for the API. Each signed-in user (or, for anonymous requests, each IP)
 * gets its own token buckets, so one client can't exhaust capacity for everyone.
 * Expensive actions (starting streams, uploads, imports, AI calls) get a tighter limit.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> EXPENSIVE_PREFIXES = Set.of(
            "/api/start", "/api/stream/schedule", "/api/library/upload", "/api/upload",
            "/api/library/import", "/api/ai/", "/api/convert/optimize", "/api/convert/shorts",
            "/api/library/merge", "/api/payment/initiate", "/api/auth/");

    /** Expensive endpoints matched exactly (their prefixes also cover cheap polling endpoints). */
    private static final Set<String> EXPENSIVE_EXACT = Set.of("/api/convert");

    private final long generalPerMinute;
    private final long expensivePerMinute;
    private final Cache<String, Bucket> generalBuckets;
    private final Cache<String, Bucket> expensiveBuckets;

    public RateLimitFilter(@Value("${rate-limiter.general-per-minute:300}") long generalPerMinute,
                           @Value("${rate-limiter.expensive-per-minute:20}") long expensivePerMinute) {
        this.generalPerMinute = generalPerMinute;
        this.expensivePerMinute = expensivePerMinute;
        this.generalBuckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();
        this.expensiveBuckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Payment provider callbacks must never be throttled.
        return !uri.startsWith("/api/") || uri.startsWith("/api/payment/callback");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientKey(request);
        String uri = request.getRequestURI();

        Bucket general = generalBuckets.get(client, k -> perMinute(generalPerMinute));
        if (!tryConsume(general, response)) {
            return;
        }
        if (EXPENSIVE_EXACT.contains(uri) || EXPENSIVE_PREFIXES.stream().anyMatch(uri::startsWith)) {
            Bucket expensive = expensiveBuckets.get(client, k -> perMinute(expensivePerMinute));
            if (!tryConsume(expensive, response)) {
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static boolean tryConsume(Bucket bucket, HttpServletResponse response) throws IOException {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"Too many requests. Please wait a moment and try again.\"}");
        return false;
    }

    static Bucket perMinute(long tokens) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(tokens, Refill.greedy(tokens, Duration.ofMinutes(1))))
                .build();
    }

    private static String clientKey(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null) {
            return "user:" + principal.getName();
        }
        // forward-headers-strategy=native makes getRemoteAddr() the real client behind the proxy.
        return "ip:" + request.getRemoteAddr();
    }
}
