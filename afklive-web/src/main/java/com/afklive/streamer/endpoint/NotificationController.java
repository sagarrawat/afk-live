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
    private final com.afklive.streamer.service.PushService push;

    /** NT-1 / MB-2: whether push alerts are available, the key browsers subscribe with, and this user's devices. */
    public record PushInfo(boolean available, String publicKey, long devices) {
    }

    public record PushKeys(String p256dh, String auth) {
    }

    public record PushSubscribe(String endpoint, PushKeys keys) {
    }

    @GetMapping("/push")
    public PushInfo pushInfo(Principal principal) {
        return new PushInfo(push.enabled(), push.publicKey(), push.devices(user(principal).getId()));
    }

    @PostMapping("/push")
    public PushInfo subscribe(@RequestBody PushSubscribe body, Principal principal,
                              @RequestHeader(value = "User-Agent", required = false) String ua) {
        User u = user(principal);
        if (body == null || body.keys() == null) {
            throw new IllegalArgumentException("That browser subscription isn't valid.");
        }
        push.subscribe(u.getId(), body.endpoint(), body.keys().p256dh(), body.keys().auth(), ua);
        return new PushInfo(true, push.publicKey(), push.devices(u.getId()));
    }

    @DeleteMapping("/push")
    public PushInfo unsubscribe(@RequestBody PushSubscribe body, Principal principal) {
        User u = user(principal);
        if (body != null && body.endpoint() != null) {
            push.unsubscribe(u.getId(), body.endpoint());
        }
        return new PushInfo(push.enabled(), push.publicKey(), push.devices(u.getId()));
    }

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

    /** NT-3: each kind of alert with its email and push setting. */
    public record PreferenceView(String id, String label, String description, boolean email, boolean push) {
    }

    @GetMapping("/preferences")
    public List<PreferenceView> preferences(Principal principal) {
        return views(user(principal));
    }

    @PutMapping("/preferences")
    public List<PreferenceView> setPreferences(@RequestBody Map<String, com.afklive.streamer.service.AlertPreferences.Channels> body,
                                               Principal principal) {
        User u = user(principal);
        Map<String, com.afklive.streamer.service.AlertPreferences.Channels> next =
                new java.util.LinkedHashMap<>(com.afklive.streamer.service.AlertPreferences.read(u.getAlertPreferences()));
        if (body != null) {
            body.forEach((k, ch) -> {
                if (next.containsKey(k) && ch != null) {
                    next.put(k, ch);
                }
            });
        }
        u.setAlertPreferences(com.afklive.streamer.service.AlertPreferences.write(next));
        users.save(u);
        return views(u);
    }

    private static List<PreferenceView> views(User u) {
        var prefs = com.afklive.streamer.service.AlertPreferences.read(u.getAlertPreferences());
        return com.afklive.streamer.service.AlertPreferences.KINDS.stream()
                .map(k -> new PreferenceView(k.id(), k.label(), k.description(), prefs.get(k.id()).email(), prefs.get(k.id()).push()))
                .toList();
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
