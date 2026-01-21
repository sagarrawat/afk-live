package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.model.EngagementActivity;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.service.YouTubeService;
import com.afklive.streamer.util.SecurityUtils;
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
    private final ChannelService channelService;

    @GetMapping("/unreplied")
    public ResponseEntity<?> getUnrepliedComments(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            String username = SecurityUtils.getEmail(principal);
            User user = userService.getOrCreateUser(username);

            List<Map<String, Object>> result = new ArrayList<>();

            // Iterate over all YouTube channels with credentials
            for (com.afklive.streamer.model.SocialChannel channel : user.getChannels()) {
                if ("YOUTUBE".equals(channel.getPlatform()) && channel.getCredentialId() != null) {
                    try {
                        // FIX: Pass credentialId, not username
                        List<CommentThread> threads = youTubeService.getUnrepliedComments(channel.getCredentialId());
                        for (CommentThread thread : threads) {
                            String commentText = thread.getSnippet().getTopLevelComment().getSnippet().getTextDisplay();
                            result.add(Map.of(
                                "id", thread.getId(),
                                "author", thread.getSnippet().getTopLevelComment().getSnippet().getAuthorDisplayName(),
                                "text", commentText,
                                "publishedAt", thread.getSnippet().getTopLevelComment().getSnippet().getPublishedAt().toString(),
                                "videoId", thread.getSnippet().getVideoId(),
                                "channelName", channel.getName()
                            ));
                        }
                    } catch (Exception e) {
                        // Log error but continue to next channel
                        System.err.println("Failed to fetch comments for channel " + channel.getName() + ": " + e.getMessage());
                    }
                }
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
        User user = userService.getOrCreateUser(SecurityUtils.getEmail(principal));
        return ResponseEntity.ok(Map.of(
            "autoReplyEnabled", user.isAutoReplyEnabled(),
            "deleteNegativeComments", user.isDeleteNegativeComments()
        ));
    }

    @PostMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Boolean> payload, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        User user = userService.getOrCreateUser(SecurityUtils.getEmail(principal));

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
        return ResponseEntity.ok(activityRepository.findByUsernameOrderByTimestampDesc(SecurityUtils.getEmail(principal)));
    }

    @PostMapping("/revert/{activityId}")
    public ResponseEntity<?> revertAction(@PathVariable Long activityId, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);

        EngagementActivity activity = activityRepository.findById(activityId).orElse(null);
        if (activity == null || !activity.getUsername().equals(email)) {
            return ResponseEntity.notFound().build();
        }

        if ("REPLY".equals(activity.getActionType())) {
            try {
                if (activity.getCreatedCommentId() != null) {
                    // FIX: Pass credentialId, not username
                    String credentialId = channelService.getCredentialId(email);
                    youTubeService.deleteComment(credentialId, activity.getCreatedCommentId());
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
