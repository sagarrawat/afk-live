package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/engagement")
@RequiredArgsConstructor
public class EngagementController {

    private final UserService userService;

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        User user = userService.getOrCreateUser(principal.getName());
        return ResponseEntity.ok(Map.of(
            "autoReplyEnabled", user.isAutoReplyEnabled(),
            "deleteNegativeComments", user.isDeleteNegativeComments()
        ));
    }

    @PostMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Boolean> payload, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        User user = userService.getOrCreateUser(principal.getName());

        if (payload.containsKey("autoReplyEnabled")) {
            user.setAutoReplyEnabled(payload.get("autoReplyEnabled"));
        }
        if (payload.containsKey("deleteNegativeComments")) {
            user.setDeleteNegativeComments(payload.get("deleteNegativeComments"));
        }
        userService.saveUser(user);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
