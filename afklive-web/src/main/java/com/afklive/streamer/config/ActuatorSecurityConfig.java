package com.afklive.streamer.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.regex.Pattern;

/**
 * OPS-4: the actuator is for this machine only. The health probes answer requests made directly on
 * localhost (the deploy script and Caddy's active health checks); everything else under
 * {@code /actuator} is refused. Requests that came through Caddy carry the visitor's address
 * ({@code server.forward-headers-strategy: native} applies X-Forwarded-For, and Caddy replaces any
 * X-Forwarded-For a visitor sends), so they are not local even though Caddy connects from 127.0.0.1.
 * Stateless: a health check every second must not create sessions in the database.
 */
@Configuration
public class ActuatorSecurityConfig {

    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final RequestMatcher HEALTH = PathPatternRequestMatcher.pathPattern("/actuator/health");
    private static final RequestMatcher HEALTH_PROBES = PathPatternRequestMatcher.pathPattern("/actuator/health/**");

    static final RequestMatcher LOCAL_HEALTH = request -> isLoopback(request)
            && (HEALTH.matches(request) || HEALTH_PROBES.matches(request));

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator", "/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(LOCAL_HEALTH).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.disable());
        return http.build();
    }

    /** Compared as text, so no name lookups. Tomcat gives the forwarded address for proxied requests. */
    static boolean isLoopback(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        if (addr == null) {
            return false;
        }
        addr = addr.trim();
        if (addr.startsWith("[") && addr.endsWith("]")) {
            addr = addr.substring(1, addr.length() - 1);
        }
        return IPV4_LOOPBACK.matcher(addr).matches() || addr.equals("::1") || addr.equals("0:0:0:0:0:0:0:1");
    }
}
