package com.afklive.streamer.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Limits account form posts (sign-in, sign-up, password reset) per IP, against password guessing
 * and email flooding. Runs before Spring Security (order -100), which answers POST /login itself
 * and never passes it further down the chain.
 */
@Component
@Order(-110)
public class AccountRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> ACCOUNT_PATHS = Set.of(
            "/login", "/register", "/forgot-password", "/reset-password", "/verify-email", "/login/2fa", "/account/restore");

    private final long perMinute;
    private final Cache<String, Bucket> buckets =
            Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();

    public AccountRateLimitFilter(@Value("${rate-limiter.account-per-minute:10}") long perMinute) {
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && ACCOUNT_PATHS.contains(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.get(request.getRemoteAddr(), k -> RateLimitFilter.perMinute(perMinute));
        if (!RateLimitFilter.tryConsume(bucket, response)) {
            return;
        }
        chain.doFilter(request, response);
    }
}
