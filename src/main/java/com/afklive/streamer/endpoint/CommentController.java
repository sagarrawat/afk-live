package com.afklive.streamer.endpoint;

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

    @GetMapping
    public ResponseEntity<?> getComments(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        try {
            return ResponseEntity.ok(youTubeService.getCommentThreads(SecurityUtils.getEmail(principal)));
        } catch (Exception e) {
            if (e.getMessage().contains("not connected") || e.getMessage().contains("Authentication failed")) {
                return ResponseEntity.status(403).body(Map.of("message", "YouTube not connected. Please connect in Settings."));
            }
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
            youTubeService.replyToComment(SecurityUtils.getEmail(principal), parentId, text);
            return ResponseEntity.ok(Map.of("success", true, "message", "Reply posted"));
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
            youTubeService.deleteComment(SecurityUtils.getEmail(principal), id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Comment deleted"));
        } catch (Exception e) {
            log.error("Failed to delete", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
