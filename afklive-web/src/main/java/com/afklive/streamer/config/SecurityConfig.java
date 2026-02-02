package com.afklive.streamer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import com.afklive.streamer.security.CustomOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.afklive.streamer.security.OAuth2LoginSuccessHandler;
import com.afklive.streamer.security.CustomAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import com.afklive.streamer.service.CustomUserDetailsService;

import java.util.function.Consumer;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository,
                                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                                           CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler,
                                           CustomUserDetailsService userDetailsService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 1. PUBLIC: Landing page, assets
                        .requestMatchers("/", "/home.html", "/pricing.html", "/features.html", "/privacy.html", "/privacy", "/terms.html", "/terms", "/pricing", "/features", "/css/**", "/js/**", "/api/user-info", "/api/pricing", "/api/mock/**", "/error").permitAll()
                        .requestMatchers("/login", "/register", "/verify-email", "/forgot-password", "/reset-password", "/api/auth/**").permitAll()
                        .requestMatchers("/api/payment/callback", "/api/payment/initiate").permitAll()

                        // 2. ADMIN
                        .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")

                        // 3. PROTECTED: The Studio URL and internal index file
                        .requestMatchers("/studio", "/app.html", "/app").hasRole("USER")

                        // 4. CATCH-ALL
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(customAuthenticationSuccessHandler)
                        .permitAll()
                )
                .rememberMe(remember -> remember
                        .key("afkLiveSecretKey123")
                        .tokenValiditySeconds(86400 * 30) // 30 days
                        .userDetailsService(userDetailsService)
                )
                .oauth2Login(oauth -> oauth
                        .loginPage("/login")
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(authorizationRequestResolver(clientRegistrationRepository))
                        )
                        .successHandler(oAuth2LoginSuccessHandler)
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/") // Back to Home after logout
                        .permitAll()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                );

        return http.build();
    }

    private OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new CustomOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            JdbcTemplate jdbcTemplate,
            ClientRegistrationRepository clientRegistrationRepository) {
        return new JdbcOAuth2AuthorizedClientService(jdbcTemplate, clientRegistrationRepository);
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository(OAuth2AuthorizedClientService authorizedClientService) {
        // We need to use the service-based repository to ensure persistence via JDBC
        return new org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {

        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken()
                        .build();

        DefaultOAuth2AuthorizedClientManager authorizedClientManager =
                new DefaultOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientRepository);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }

    @Bean
    public AuthorizedClientServiceOAuth2AuthorizedClientManager serviceAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .refreshToken()
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }
}
