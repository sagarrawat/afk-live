package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/api/user-info")
    public Map<String, Object> getUser(@AuthenticationPrincipal Object principal) {
        if (principal == null) {
            return Collections.emptyMap();
        }

        String username;
        String name;
        String picture = "https://via.placeholder.com/32";

        if (principal instanceof OAuth2User) {
            OAuth2User oauth = (OAuth2User) principal;
            username = oauth.getAttribute("email");
            name = oauth.getAttribute("name");
            Object pic = oauth.getAttribute("picture");
            if (pic != null) picture = pic.toString();
        } else if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            org.springframework.security.core.userdetails.UserDetails userDetails = (org.springframework.security.core.userdetails.UserDetails) principal;
            username = userDetails.getUsername();
            name = username;
        } else {
            return Collections.emptyMap();
        }

        User user = userService.getOrCreateUser(username);
        if (user.getFullName() != null) name = user.getFullName();

        return Map.of(
                "name", name,
                "email", username,
                "picture", picture,
                "enabled", user.isEnabled(),
                "plan", Map.of(
                    "name", user.getPlanType().getDisplayName(),
                    "storageLimit", user.getPlanType().getMaxStorageBytes(),
                    "storageUsed", user.getUsedStorageBytes(),
                    "streamLimit", user.getPlanType().getMaxActiveStreams()
                )
        );
    }
}