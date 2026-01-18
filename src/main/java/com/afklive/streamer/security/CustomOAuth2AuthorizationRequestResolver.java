package com.afklive.streamer.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CustomOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver defaultResolver;

    public CustomOAuth2AuthorizationRequestResolver(ClientRegistrationRepository repo, String authorizationRequestBaseUri) {
        this.defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(repo, authorizationRequestBaseUri);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest req = defaultResolver.resolve(request);
        return customizeAuthorizationRequest(req, request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest req = defaultResolver.resolve(request, clientRegistrationId);
        return customizeAuthorizationRequest(req, request);
    }

    private OAuth2AuthorizationRequest customizeAuthorizationRequest(OAuth2AuthorizationRequest req, HttpServletRequest request) {
        if (req == null) return null;

        String action = request.getParameter("action");
        if ("connect_youtube".equals(action)) {
            Set<String> scopes = new HashSet<>(req.getScopes());
            scopes.add("https://www.googleapis.com/auth/youtube.upload");
            scopes.add("https://www.googleapis.com/auth/youtube.readonly");
            scopes.add("https://www.googleapis.com/auth/youtube.force-ssl");

            Map<String, Object> additionalParameters = new HashMap<>(req.getAdditionalParameters());
            additionalParameters.put("access_type", "offline");
            additionalParameters.put("prompt", "consent");

            // Store current user for linking
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                request.getSession().setAttribute("LINKING_USER", auth.getName());
            }

            return OAuth2AuthorizationRequest.from(req)
                    .scopes(scopes)
                    .additionalParameters(additionalParameters)
                    .build();
        }

        // For normal login, ensure offline access just in case?
        // Default resolver usually doesn't add it unless configured.
        // We can leave it for now or enforce it.
        // Let's enforce offline for login too if needed, but 'prompt=consent' is annoying for login.

        return req;
    }
}
