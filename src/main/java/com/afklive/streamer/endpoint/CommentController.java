package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.YouTubeService;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
@Slf4j
public class CommentController {

    private final YouTubeService youTubeService;

    @GetMapping
    public CommentThreadListResponse getComments(Principal principal) {
        if (principal == null) throw new IllegalStateException("Not authenticated");
        try {
            return youTubeService.getCommentThreads(principal.getName());
        } catch (Exception e) {
            log.error("Failed to fetch comments", e);
            throw new RuntimeException("Error fetching comments: " + e.getMessage());
        }
    }

    @PostMapping("/{parentId}/reply")
    public Map<String, Object> replyToComment(
            Principal principal,
            @PathVariable String parentId,
            @RequestBody Map<String, String> payload) {

        if (principal == null) throw new IllegalStateException("Not authenticated");

        String text = payload.get("text");
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Reply text cannot be empty");
        }

        try {
            youTubeService.replyToComment(principal.getName(), parentId, text);
            return Map.of("success", true, "message", "Reply posted");
        } catch (Exception e) {
            log.error("Failed to reply", e);
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteComment(
            Principal principal,
            @PathVariable String id) {

        if (principal == null) throw new IllegalStateException("Not authenticated");

        try {
            youTubeService.deleteComment(principal.getName(), id);
            return Map.of("success", true, "message", "Comment deleted");
        } catch (Exception e) {
            log.error("Failed to delete", e);
            return Map.of("success", false, "message", e.getMessage());
        }
    }
}
