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

/**
 * OPS-4: the actuator is for this machine only. Health (liveness/readiness) answers requests made
 * directly on localhost (the deploy script and Caddy's active health checks); everything else under
 * {@code /actuator} is refused. Requests that came through Caddy carry the visitor's address
 * ({@code server.forward-headers-strategy: native} applies X-Forwarded-For), so they are not
 * "local" even though Caddy connects from 127.0.0.1. Stateless: health checks every second must
 * not create sessions in the database.
 */
@Configuration
public class ActuatorSecurityConfig {

    static final RequestMatcher LOCAL_HEALTH = request -> isLoopback(request)
            && (PathPatternRequestMatcher.pathPattern("/actuator/health").matches(request)
            || PathPatternRequestMatcher.pathPattern("/actuator/health/**").matches(request));

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator", "/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(LOCAL_HEALTH).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.requireExplicitSave(true))
                .requestCache(c -> c.disable())
                .csrf(c -> c.disable())
                .anonymous(a -> { });
        return http.build();
    }

    static boolean isLoopback(HttpServletRequest request) {
        // Tomcat gives the peer's address, or the forwarded one when a trusted proxy (Caddy on
        // localhost) sent the request. Compared as text: no name lookups.
        String addr = request.getRemoteAddr();
        if (addr == null) {
            return false;
        }
        addr = addr.trim();
        if (addr.startsWith("[") && addr.endsWith("]")) {
            addr = addr.substring(1, addr.length() - 1);
        }
        return addr.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || addr.equals("::1") || addr.equals("0:0:0:0:0:0:0:1");
    }
}
