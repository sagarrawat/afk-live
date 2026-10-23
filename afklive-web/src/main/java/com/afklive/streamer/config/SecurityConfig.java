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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import com.afklive.streamer.service.CustomUserDetailsService;
import com.afklive.streamer.filter.CsrfCookieFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@Configuration
@Slf4j
public class SecurityConfig {

    private final String rememberMeKey;
    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${app.security.remember-me-key:}") String rememberMeKey,
                          @Value("${app.base-url:http://localhost:8080}") String baseUrl,
                          @Value("${app.cors.allowed-origins:}") String extraOrigins) {
        if (rememberMeKey == null || rememberMeKey.isBlank()) {
            log.warn("REMEMBER_ME_KEY is not set; using a random key, so remember-me logins end on restart.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.rememberMeKey = HexFormat.of().formatHex(random);
        } else {
            this.rememberMeKey = rememberMeKey;
        }
        List<String> origins = new ArrayList<>();
        origins.add(stripTrailingSlash(baseUrl));
        Arrays.stream(extraOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .map(SecurityConfig::stripTrailingSlash)
                .forEach(origins::add);
        this.allowedOrigins = List.copyOf(origins);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository,
                                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                                           CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler,
                                           CustomUserDetailsService userDetailsService,
                                           com.afklive.streamer.security.AccountGate accountGate) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/payment/callback", "/internal/ingest/auth")
                )
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // ON-6 / ON-7: a second sign-in step for two-step accounts; accounts scheduled for deletion are closed.
                .addFilterBefore(new com.afklive.streamer.security.AccountGateFilter(accountGate),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.FORWARD, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // 1. PUBLIC: Landing page, assets
                        .requestMatchers("/", "/home.html", "/pricing.html", "/features.html", "/privacy.html", "/privacy", "/terms.html", "/terms", "/classic", "/classic.html", "/pricing", "/features", "/for/**", "/status", "/api/public/status", "/sitemap.xml", "/robots.txt", "/css/**", "/js/**", "/app/assets/**", "/app/manifest.webmanifest", "/app/*.png", "/favicon.ico", "/sw.js", "/site/**", "/api/user-info", "/api/pricing", "/api/billing/plans", "/error").permitAll()
                        .requestMatchers("/login", "/register", "/verify-email", "/forgot-password", "/reset-password", "/api/auth/**", "/account/restore").permitAll()
                        // Server-to-server payment notification; verified by signature in the controller.
                        .requestMatchers("/api/payment/callback").permitAll()
                        // Ingest server (MediaMTX) auth hook; verified by a shared secret in the controller.
                        .requestMatchers("/internal/ingest/auth").permitAll()

                        // 2. ADMIN (raw FFmpeg logs include every user's streams)
                        .requestMatchers("/admin/**", "/api/admin/**", "/api/logs").hasRole("ADMIN")

                        // 3. PROTECTED: The Studio URL and internal index file
                        .requestMatchers("/studio", "/studio/**", "/app", "/app/index.html").hasAnyRole("USER", "ADMIN")

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
                        .key(rememberMeKey)
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
                        .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern("/logout"))
                        .logoutSuccessUrl("/") // Back to Home after logout
                        .permitAll()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
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
