package com.afklive.streamer.service;

import com.afklive.streamer.model.AppSetting;
import com.afklive.streamer.model.EngagementActivity;
import com.afklive.streamer.model.ProcessedComment;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AppSettingRepository;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.repository.ProcessedCommentRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import com.google.api.services.youtube.model.LiveChatMessage;
import com.google.api.services.youtube.model.LiveChatMessageListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class EngagementService {

    private final UserRepository userRepository;
    private final ProcessedCommentRepository processedRepository;
    private final EngagementActivityRepository activityRepository;
    private final YouTubeService youTubeService;
    private final AiService aiService;
    private final StreamJobRepository streamJobRepo;
    private final AppSettingRepository appSettingRepository;
    private final UserService userService;

    // Run via JobRunr (30s)
    public void processLiveEngagement() {
        if (!isEngagementEnabled()) return;

        List<StreamJob> jobs = streamJobRepo.findByIsLiveTrueAndAutoReplyEnabledTrue();
        Set<String> processedUsers = new java.util.HashSet<>();

        for (StreamJob job : jobs) {
            String email = job.getUser().getEmail();
            if (processedUsers.contains(email)) continue;
            processedUsers.add(email);
            processLiveChatForUser(job);
        }
    }

    private void processLiveChatForUser(StreamJob job) {
        try {
            User user = job.getUser();
            String username = user.getEmail();

            if (!youTubeService.isConnected(username)) return;

            String liveChatId = job.getLiveChatId();
            if (liveChatId == null || liveChatId.isEmpty()) {
                liveChatId = youTubeService.getActiveLiveChatId(username);
                if (liveChatId != null) {
                    job.setLiveChatId(liveChatId);
                    streamJobRepo.save(job);
                }
            }
            if (liveChatId == null) return;

            LiveChatMessageListResponse response = youTubeService.getLiveChatMessages(username, liveChatId, job.getLastPageToken());

            if (response.getItems() != null && !response.getItems().isEmpty()) {
                job.setLastPageToken(response.getNextPageToken());
                streamJobRepo.save(job);

                String channelId = youTubeService.getChannelId(username);

                for (LiveChatMessage msg : response.getItems()) {
                    if (msg.getSnippet().getAuthorChannelId().equals(channelId)) continue;
                    if (!"textMessageEvent".equals(msg.getSnippet().getType())) continue;

                    String text = msg.getSnippet().getTextMessageDetails().getMessageText();

                    // Generate Reply
                    String reply = aiService.generateTwitterStyleReply(text, job.getTitle());

                    if (reply != null) {
                        // Send Reply
                        youTubeService.replyToLiveChat(username, liveChatId, reply);

                        // Log
                        logActivity(user, "LIVE_REPLY", msg.getId(), liveChatId, reply, null);
                    } else {
                        log.warn("AI failed to generate reply for msg: {}", msg.getId());
                    }
                }
            } else {
                job.setLastPageToken(response.getNextPageToken());
                streamJobRepo.save(job);
            }
        } catch (Exception e) {
            log.error("Live engagement error for user {}", job.getUser().getEmail(), e);
        }
    }

    // Run via JobRunr (1 min)
    public void processEngagement() {
        if (!isEngagementEnabled()) return;

        int page = 0;
        int size = 100;
        Slice<User> userSlice;

        do {
            userSlice = userRepository.findByAutoReplyEnabledTrue(PageRequest.of(page, size, Sort.by("email")));
            for (User user : userSlice) {
                processUserComments(user);
            }
            page++;
        } while (userSlice.hasNext());
    }

    private boolean isEngagementEnabled() {
        return appSettingRepository.findById("ENGAGEMENT_CRON_ENABLED")
                .map(setting -> Boolean.parseBoolean(setting.getSettingValue()))
                .orElse(true); // Default to true if not set
    }

    public void processUserComments(User user) {
        try {
            String username = user.getEmail();
            if (!youTubeService.isConnected(username)) return;

            CommentThreadListResponse response = youTubeService.getCommentThreads(username);

            // Handle 304 Not Modified or empty response
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) return;

            // Optimization: Fetch all existing comment IDs in one query to avoid N+1 problem
            List<String> commentIds = response.getItems().stream()
                    .map(thread -> thread.getSnippet().getTopLevelComment().getId())
                    .toList();
            Set<String> existingIds = processedRepository.findExistingCommentIds(commentIds);

            for (CommentThread thread : response.getItems()) {
                String commentId = thread.getSnippet().getTopLevelComment().getId();
                String text = thread.getSnippet().getTopLevelComment().getSnippet().getTextDisplay();
                String videoId = thread.getSnippet().getVideoId();

                if (existingIds.contains(commentId)) continue;

                // Process
                String sentiment = aiService.analyzeSentiment(text);
                String action = "IGNORED";

                String replyText = null;
                String newId = null;

                if (sentiment.contains("NEGATIVE")) {
                    if (user.isDeleteNegativeComments()) {
                        youTubeService.deleteComment(username, commentId);
                        action = "DELETED";
                        logActivity(user, "DELETE", commentId, videoId, text, null);
                    }
                } else if (sentiment.contains("POSITIVE") || user.isAutoReplyEnabled()) {
                    // Reply logic...
                    if (sentiment.contains("POSITIVE")) {
                        replyText = aiService.generateSingleReply(text);
                        if (replyText != null) {
                             newId = youTubeService.replyToComment(username, commentId, replyText);
                             action = "REPLIED";
                             logActivity(user, "REPLY", commentId, videoId, replyText, newId);
                        }
                    }
                }

                // Save state
                ProcessedComment pc = new ProcessedComment();
                pc.setCommentId(commentId);
                pc.setVideoId(videoId);
                pc.setUser(user);
                pc.setSentiment(sentiment);
                pc.setActionTaken(action);
                pc.setProcessedAt(java.time.LocalDateTime.now());
                processedRepository.save(pc);
            }
        } catch (Exception e) {
            log.error("Engagement error for user {}", user.getEmail(), e);
        }
    }

    private void logActivity(User user, String type, String commentId, String videoId, String content, String createdId) {
        EngagementActivity activity = new EngagementActivity();
        activity.setUser(user);
        activity.setActionType(type);
        activity.setCommentId(commentId);
        activity.setVideoId(videoId);
        activity.setContent(content);
        activity.setCreatedCommentId(createdId);
        activityRepository.save(activity);
    }
}
