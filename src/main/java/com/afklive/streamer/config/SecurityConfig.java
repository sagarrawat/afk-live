package com.afklive.streamer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 1. PUBLIC: Landing page, assets, and user-check API
                        .requestMatchers("/", "/home.html", "/css/**", "/js/**", "/api/user-info", "/error").permitAll()

                        // 2. PROTECTED: The Studio URL and internal index file
                        .requestMatchers("/studio", "/index.html", "/api/**").permitAll()

                        // 3. CATCH-ALL
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth -> oauth
                        // CHANGE: Redirect to /studio after login, not index.html
                        .defaultSuccessUrl("/studio", true)
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/") // Back to Home after logout
                        .permitAll()
                )
                .exceptionHandling(e -> e
                        // CHANGE: If not logged in, redirect to "/" (Home)
                        // This replaces the 401 Error or default Login Page
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/"))
                );

        return http.build();
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            JdbcTemplate jdbcTemplate,
            ClientRegistrationRepository clientRegistrationRepository) {

        return new JdbcOAuth2AuthorizedClientService(jdbcTemplate, clientRegistrationRepository);
    }
}