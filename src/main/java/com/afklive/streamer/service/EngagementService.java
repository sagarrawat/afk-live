package com.afklive.streamer.service;

import com.afklive.streamer.model.ProcessedComment;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.ProcessedCommentRepository;
import com.afklive.streamer.repository.UserRepository;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EngagementService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngagementService.class);

    private final UserRepository userRepository;
    private final ProcessedCommentRepository processedRepository;
    private final YouTubeService youTubeService;
    private final AiService aiService;

    @Scheduled(fixedRate = 600000) // 10 mins
    public void processEngagement() {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            if (user.isAutoReplyEnabled()) {
                processUserComments(user);
            }
        }
    }

    public void processUserComments(User user) {
        try {
            if (!youTubeService.isConnected(user.getUsername())) return;

            CommentThreadListResponse response = youTubeService.getCommentThreads(user.getUsername());
            if (response.getItems() == null) return;

            for (CommentThread thread : response.getItems()) {
                String commentId = thread.getSnippet().getTopLevelComment().getId();
                String text = thread.getSnippet().getTopLevelComment().getSnippet().getTextDisplay();
                String videoId = thread.getSnippet().getVideoId();

                if (processedRepository.existsByCommentId(commentId)) continue;

                // Process
                String sentiment = aiService.analyzeSentiment(text);
                String action = "IGNORED";

                if (sentiment.contains("NEGATIVE")) {
                    if (user.isDeleteNegativeComments()) {
                        youTubeService.deleteComment(user.getUsername(), commentId);
                        action = "DELETED";
                    }
                } else if (sentiment.contains("POSITIVE")) {
                    // Note: Liking comments via API is not supported by YouTube Data API v3
                    youTubeService.replyToComment(user.getUsername(), commentId, "😊 Thanks for watching!");
                    action = "REPLIED";
                }

                // Save state
                ProcessedComment pc = new ProcessedComment();
                pc.setCommentId(commentId);
                pc.setVideoId(videoId);
                pc.setUsername(user.getUsername());
                pc.setSentiment(sentiment);
                pc.setActionTaken(action);
                pc.setProcessedAt(java.time.LocalDateTime.now());
                processedRepository.save(pc);
            }
        } catch (Exception e) {
            log.error("Engagement error for user {}", user.getUsername(), e);
        }
    }
}
