package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
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
}
