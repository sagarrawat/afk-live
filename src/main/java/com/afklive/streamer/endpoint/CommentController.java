package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.YouTubeService;
import com.afklive.streamer.util.SecurityUtils;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommentController.class);

    private final YouTubeService youTubeService;
    private final ChannelService channelService;

    @GetMapping
    public ResponseEntity<?> getComments(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        try {
            String username = SecurityUtils.getEmail(principal);
            java.util.List<com.afklive.streamer.model.SocialChannel> channels = channelService.getChannels(username);

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            java.util.List<Object> allItems = new java.util.ArrayList<>();

            boolean atLeastOneConnected = false;

            for (com.afklive.streamer.model.SocialChannel ch : channels) {
                if ("YOUTUBE".equals(ch.getPlatform()) && ch.getCredentialId() != null) {
                    try {
                        atLeastOneConnected = true;
                        com.google.api.services.youtube.model.CommentThreadListResponse response =
                            youTubeService.getCommentThreads(ch.getCredentialId());
                        if (response.getItems() != null) {
                            allItems.addAll(response.getItems());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch comments for channel {}", ch.getName(), e);
                    }
                }
            }

            if (!atLeastOneConnected) {
                return ResponseEntity.status(403).body(Map.of("message", "YouTube not connected. Please connect in Settings."));
            }

            result.put("items", allItems);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to fetch comments", e);
            return ResponseEntity.status(500).body(Map.of("message", "Error fetching comments"));
        }
    }

    @PostMapping("/{parentId}/reply")
    public ResponseEntity<?> replyToComment(
            Principal principal,
            @PathVariable String parentId,
            @RequestBody Map<String, String> payload) {

        if (principal == null) return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        String text = payload.get("text");
        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Reply text cannot be empty"));
        }

        try {
            String username = SecurityUtils.getEmail(principal);
            java.util.List<com.afklive.streamer.model.SocialChannel> channels = channelService.getChannels(username);

            boolean success = false;
            String lastError = "No connected channels";

            // Try to find which channel owns this comment or just try all until success
            // Since we don't know the channel ID from parentId easily without lookup,
            // and we iterate channels, we try all. The API will return 404 or 403 if the comment doesn't belong to the channel credential.

            for (com.afklive.streamer.model.SocialChannel ch : channels) {
                if ("YOUTUBE".equals(ch.getPlatform()) && ch.getCredentialId() != null) {
                    try {
                        youTubeService.replyToComment(ch.getCredentialId(), parentId, text);
                        success = true;
                        break; // Stop on first success
                    } catch (Exception e) {
                        lastError = e.getMessage();
                    }
                }
            }

            if (success) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Reply posted"));
            } else {
                return ResponseEntity.status(500).body(Map.of("success", false, "message", "Failed to reply: " + lastError));
            }
        } catch (Exception e) {
            log.error("Failed to reply", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteComment(
            Principal principal,
            @PathVariable String id) {

        if (principal == null) return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        try {
            String username = SecurityUtils.getEmail(principal);
            java.util.List<com.afklive.streamer.model.SocialChannel> channels = channelService.getChannels(username);

            boolean success = false;
            String lastError = "No connected channels";

            for (com.afklive.streamer.model.SocialChannel ch : channels) {
                if ("YOUTUBE".equals(ch.getPlatform()) && ch.getCredentialId() != null) {
                    try {
                        youTubeService.deleteComment(ch.getCredentialId(), id);
                        success = true;
                        break;
                    } catch (Exception e) {
                        lastError = e.getMessage();
                    }
                }
            }

            if (success) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Comment deleted"));
            } else {
                return ResponseEntity.status(500).body(Map.of("success", false, "message", "Failed to delete: " + lastError));
            }
        } catch (Exception e) {
            log.error("Failed to delete", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
