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
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.util.function.Consumer;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 1. PUBLIC: Landing page, assets, and user-check API
                        .requestMatchers("/", "/home.html", "/pricing.html", "/features.html", "/pricing", "/features", "/css/**", "/js/**", "/api/user-info", "/api/pricing", "/api/mock/**", "/error").permitAll()
                        .requestMatchers("/login", "/register", "/verify-email", "/forgot-password", "/reset-password", "/api/auth/**").permitAll()
                        // Allow stock music api for authenticated users (handled by anyRequest().authenticated() but explicit here for clarity/debug)
                        // .requestMatchers("/api/stock-music/**").authenticated()

                        // 2. PROTECTED: The Studio URL and internal index file
                        .requestMatchers("/studio", "/app.html").permitAll()

                        // 3. CATCH-ALL
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/studio", true)
                        .permitAll()
                )
                .oauth2Login(oauth -> oauth
                        .loginPage("/login")
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(authorizationRequestResolver(clientRegistrationRepository))
                        )
                        // CHANGE: Redirect to /studio after login, not index.html
                        .defaultSuccessUrl("/studio", true)
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

        DefaultOAuth2AuthorizationRequestResolver authorizationRequestResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository, "/oauth2/authorization");

        authorizationRequestResolver.setAuthorizationRequestCustomizer(
                authorizationRequestCustomizer());

        return authorizationRequestResolver;
    }

    private Consumer<OAuth2AuthorizationRequest.Builder> authorizationRequestCustomizer() {
        return customizer -> customizer
                .additionalParameters(params -> {
                    params.put("access_type", "offline");
                    params.put("prompt", "consent");
                });
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
