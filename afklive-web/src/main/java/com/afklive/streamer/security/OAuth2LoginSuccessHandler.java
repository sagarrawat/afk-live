package com.afklive.streamer.security;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.CustomUserDetailsService;
import com.afklive.streamer.util.AppConstants;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final ChannelService channelService;
    private final CustomUserDetailsService customUserDetailsService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OAuth2User oauthUser = token.getPrincipal();
        String email = oauthUser.getAttribute("email");
        String name = oauthUser.getAttribute("name");
        String picture = oauthUser.getAttribute("picture");

        // Check for linking
        String linkingUser = (String) request.getSession().getAttribute("LINKING_USER");
        log.info("OAuth success. Email: {}. LinkingUser: {}", email, linkingUser);

        if (linkingUser != null) {
            request.getSession().removeAttribute("LINKING_USER");
            // Perform Linking
            try {
                // Here 'token.getName()' is the credential ID (likely Google ID)
                // 'linkingUser' is the ORIGINAL user (Email)
                log.info("Linking channel {} to user {}", token.getName(), linkingUser);
                channelService.syncChannelFromGoogle(token.getName(), linkingUser);

                // Restore Original Session
                try {
                    UserDetails originalUserDetails = customUserDetailsService.loadUserByUsername(linkingUser);

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            originalUserDetails, null, originalUserDetails.getAuthorities());

                    SecurityContextHolder.getContext().setAuthentication(auth);
                    securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);

                    // Explicitly set in session as fallback
                    request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());

                    log.info("Restored session for {}", linkingUser);
                } catch (Exception e) {
                    log.error("Failed to restore session for {}: {}", linkingUser, e.getMessage());
                    // Fallback to manual token if service fails, though unlikely
                     Optional<User> originalUserOpt = userRepository.findById(linkingUser);
                     if (originalUserOpt.isPresent()) {
                         User original = originalUserOpt.get();
                         UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                 original.getUsername(), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
                         SecurityContextHolder.getContext().setAuthentication(auth);
                         securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);
                     }
                }

                getRedirectStrategy().sendRedirect(request, response, "/studio?connected=true");
                return;
            } catch (Exception e) {
                log.error("Failed to link channel", e);
                // Restore session anyway to prevent stuck on wrong user
                 try {
                    UserDetails originalUserDetails = customUserDetailsService.loadUserByUsername(linkingUser);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            originalUserDetails, null, originalUserDetails.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);
                } catch (Exception ex) {
                    log.error("Failed to restore session after error", ex);
                }
                getRedirectStrategy().sendRedirect(request, response, "/studio?error=channel_sync_failed");
                return;
            }
        }

        // Standard Login
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

        // Update Security Context with app roles (ROLE_USER/ROLE_ADMIN) from DB
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList(user.getRole());
        OAuth2AuthenticationToken newAuth = new OAuth2AuthenticationToken(
                oauthUser,
                authorities,
                token.getAuthorizedClientRegistrationId()
        );
        SecurityContextHolder.getContext().setAuthentication(newAuth);
        securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);

        // Specific Handling for Channel Connection (Legacy or Direct Login with Scope)
        boolean hasYoutube = token.getAuthorizedClientRegistrationId().equals(AppConstants.OAUTH_GOOGLE_YOUTUBE)
                             || token.getAuthorities().stream().anyMatch(a -> a.getAuthority().contains("youtube"));

        if (hasYoutube) {
            try {
                channelService.syncChannelFromGoogle(token.getName(), email);
            } catch (Exception e) {
                // ignore
            }
        }

        // Redirect to Studio
        if ("ROLE_ADMIN".equals(user.getRole())) {
            getRedirectStrategy().sendRedirect(request, response, "/admin");
        } else {
            getRedirectStrategy().sendRedirect(request, response, "/studio");
        }
    }
}
