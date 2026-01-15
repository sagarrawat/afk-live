package com.afklive.streamer.security;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OAuth2User oauthUser = token.getPrincipal();
        String email = oauthUser.getAttribute("email");
        String name = oauthUser.getAttribute("name");
        String picture = oauthUser.getAttribute("picture");

        Optional<User> userOpt = userRepository.findById(email);
        User user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            // Ensure Google users are enabled (verified)
            if (!user.isEnabled()) {
                user.setEnabled(true);
            }
            // Update profile info
            user.setFullName(name);
            user.setPictureUrl(picture);
        } else {
            // New User via Google
            user = new User(email);
            user.setFullName(name);
            user.setPictureUrl(picture);
            user.setEnabled(true); // Auto-verify Google users
            user.setPlanType(PlanType.FREE);
        }
        userRepository.save(user);

        // Redirect to Studio
        getRedirectStrategy().sendRedirect(request, response, "/studio");
    }
}
