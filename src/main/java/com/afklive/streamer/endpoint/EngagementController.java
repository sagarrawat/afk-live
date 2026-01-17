package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.model.EngagementActivity;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.service.YouTubeService;
import com.google.api.services.youtube.model.CommentThread;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/engagement")
@RequiredArgsConstructor
public class EngagementController {

    private final UserService userService;
    private final YouTubeService youTubeService;
    private final AiService aiService;
    private final EngagementActivityRepository activityRepository;

    @GetMapping("/unreplied")
    public ResponseEntity<?> getUnrepliedComments(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            List<CommentThread> threads = youTubeService.getUnrepliedComments(principal.getName());
            List<Map<String, Object>> result = new ArrayList<>();

            for (CommentThread thread : threads) {
                String commentText = thread.getSnippet().getTopLevelComment().getSnippet().getTextDisplay();
                // Don't generate suggestions automatically for all to avoid rate limits

                result.add(Map.of(
                    "id", thread.getId(),
                    "author", thread.getSnippet().getTopLevelComment().getSnippet().getAuthorDisplayName(),
                    "text", commentText,
                    "publishedAt", thread.getSnippet().getTopLevelComment().getSnippet().getPublishedAt().toString(),
                    "videoId", thread.getSnippet().getVideoId()
                ));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
             return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/suggest")
    public ResponseEntity<?> getReplySuggestions(@RequestParam String text, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("suggestions", aiService.generateReplySuggestions(text)));
    }

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

    @GetMapping("/activity")
    public ResponseEntity<?> getActivityLog(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(activityRepository.findByUsernameOrderByTimestampDesc(principal.getName()));
    }

    @PostMapping("/revert/{activityId}")
    public ResponseEntity<?> revertAction(@PathVariable Long activityId, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        EngagementActivity activity = activityRepository.findById(activityId).orElse(null);
        if (activity == null || !activity.getUsername().equals(principal.getName())) {
            return ResponseEntity.notFound().build();
        }

        if ("REPLY".equals(activity.getActionType())) {
            try {
                if (activity.getCreatedCommentId() != null) {
                    youTubeService.deleteComment(principal.getName(), activity.getCreatedCommentId());
                    activity.setActionType("REVERTED_REPLY");
                    activityRepository.save(activity);
                    return ResponseEntity.ok(Map.of("success", true, "message", "Reply deleted"));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("message", "Cannot revert: ID missing"));
                }
            } catch (Exception e) {
                return ResponseEntity.status(500).body(Map.of("message", "Failed to revert: " + e.getMessage()));
            }
        }

        return ResponseEntity.badRequest().body(Map.of("message", "Cannot revert this action type"));
    }
}
