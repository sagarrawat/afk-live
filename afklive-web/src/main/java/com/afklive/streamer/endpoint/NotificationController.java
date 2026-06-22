package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.NotificationDto;
import com.afklive.streamer.model.User;
import com.afklive.streamer.service.NotificationService;
import com.afklive.streamer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    
    private final NotificationService notificationService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<NotificationDto>> getNotifications(Authentication authentication, @RequestParam(defaultValue = "false") boolean unreadOnly) {
        User user = userService.getOrCreateUser(authentication.getName());
        if (user == null) return ResponseEntity.status(401).build();

        if (unreadOnly) {
            return ResponseEntity.ok(notificationService.getUnreadNotifications(user.getId()));
        } else {
            return ResponseEntity.ok(notificationService.getAllNotifications(user.getId()));
        }
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID id, Authentication authentication) {
        User user = userService.getOrCreateUser(authentication.getName());
        if (user == null) return ResponseEntity.status(401).build();

        notificationService.markAsRead(id, user.getId());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(Authentication authentication) {
        User user = userService.getOrCreateUser(authentication.getName());
        if (user == null) return ResponseEntity.status(401).build();

        notificationService.markAllAsRead(user.getId());
        return ResponseEntity.ok().build();
    }
}
