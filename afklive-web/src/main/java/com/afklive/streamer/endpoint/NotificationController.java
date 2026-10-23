package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.NotificationService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** The in-app alert list and the email setting (NT-1, NT-2). */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;
    private final UserRepository users;

    public record Feed(long unread, boolean emailAlerts, List<Notification> items) {
    }

    private User user(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Sign in first.");
        }
        return users.findByEmail(SecurityUtils.getEmail(principal))
                .orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Account not found."));
    }

    @GetMapping
    public Feed list(Principal principal) {
        User u = user(principal);
        return new Feed(notifications.unread(u.getId()), u.isEmailAlerts(), notifications.latest(u.getId()));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(Principal principal) {
        notifications.markAllRead(user(principal).getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable Long id, Principal principal) {
        return notifications.markRead(user(principal).getId(), id) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/settings")
    public Map<String, Boolean> settings(@RequestBody Map<String, Boolean> body, Principal principal) {
        User u = user(principal);
        if (body.get("emailAlerts") != null) {
            u.setEmailAlerts(body.get("emailAlerts"));
            users.save(u);
        }
        return Map.of("emailAlerts", u.isEmailAlerts());
    }
}
