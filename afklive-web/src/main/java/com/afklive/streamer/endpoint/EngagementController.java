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

/**
 * Comments on the creator's YouTube channel, read-only, plus the history of replies AFK Live posted in
 * the past. AFK Live never posts or deletes anything on YouTube by itself (AI-3; YouTube's spam policy
 * and API Developer Policies III.I.2 / III.E.3.d): the automatic reply and auto-delete settings are gone.
 * {@code /suggest} only drafts text; posting stays a separate action the creator takes per comment
 * ({@code POST /api/comments/{id}/reply}). {@code /revert} lets a creator delete a reply AFK Live posted earlier.
 */
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

    @GetMapping("/activity")
    public ResponseEntity<?> getActivityLog(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(activityRepository.findByUserEmailOrderByTimestampDesc(SecurityUtils.getEmail(principal)));
    }

    @PostMapping("/revert/{activityId}")
    public ResponseEntity<?> revertAction(@PathVariable Long activityId, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);

        EngagementActivity activity = activityRepository.findById(activityId).orElse(null);
        if (activity == null || !activity.getUser().getEmail().equals(email)) {
            return ResponseEntity.notFound().build();
        }

        if ("REPLY".equals(activity.getActionType())) {
            try {
                if (activity.getCreatedCommentId() != null) {
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
