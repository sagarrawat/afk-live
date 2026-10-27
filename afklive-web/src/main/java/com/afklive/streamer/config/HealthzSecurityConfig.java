package com.afklive.streamer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OPS: {@code /healthz} (HealthController) is open to everyone so an outside uptime monitor can call it.
 * Its own chain, ahead of the app's: no sign-in, no session, no CSRF cookie, no saved request, so a
 * check every minute (or a flood of them) creates nothing in the database. The API rate limiter only
 * covers {@code /api/**}, so monitors are never throttled.
 */
@Configuration
public class HealthzSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain healthzFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/healthz")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .csrf(c -> c.disable());
        return http.build();
    }
}
