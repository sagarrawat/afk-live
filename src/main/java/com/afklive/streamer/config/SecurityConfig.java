package com.afklive.streamer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable()) // Disable CSRF for MVP
                .authorizeHttpRequests(auth -> auth
                        // 1. PUBLIC ACCESS: Home, CSS, JS, Images
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/api/user-info").permitAll()
                        // 2. SECURE ACCESS: Everything else (Upload, Start, Stop)
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth.defaultSuccessUrl("/", true) // Go back to Dashboard after login
                )
                .logout(logout -> logout.logoutSuccessUrl("/") // Stay on dashboard after logout
                        .permitAll())
                // 3. AJAX HANDLING: If API is called without login, return 401 (don't redirect to HTML login page)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }
}