package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.AppSettingRepository;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.repository.ProcessedCommentRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.google.api.services.youtube.model.Comment;
import com.google.api.services.youtube.model.CommentSnippet;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import com.google.api.services.youtube.model.CommentThreadSnippet;
import com.google.api.services.youtube.model.LiveChatMessage;
import com.google.api.services.youtube.model.LiveChatMessageListResponse;
import com.google.api.services.youtube.model.LiveChatMessageSnippet;
import com.google.api.services.youtube.model.LiveChatTextMessageDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngagementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProcessedCommentRepository processedRepository;

    @Mock
    private EngagementActivityRepository activityRepository;

    @Mock
    private YouTubeService youTubeService;

    @Mock
    private AiService aiService;

    @Mock
    private AppSettingRepository appSettingRepository;

    @Mock
    private StreamJobRepository streamJobRepo;

    @InjectMocks
    private EngagementService engagementService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setAutoReplyEnabled(true);
        testUser.setDeleteNegativeComments(false);
    }

    @Test
    void processUserComments_shouldCheckDatabaseOnce_Optimized() throws Exception {
        // Arrange
        when(youTubeService.isConnected("testuser")).thenReturn(true);

        CommentThread thread1 = createCommentThread("c1", "video1", "Nice video!");
        CommentThread thread2 = createCommentThread("c2", "video1", "Great content!");

        CommentThreadListResponse response = new CommentThreadListResponse();
        response.setItems(Arrays.asList(thread1, thread2));

        when(youTubeService.getCommentThreads("testuser")).thenReturn(response);

        // Mock the batch fetch
        when(processedRepository.findExistingCommentIds(anyCollection())).thenReturn(Collections.emptySet());

        // Mock AI service to prevent NPEs during processing
        when(aiService.analyzeSentiment(anyString())).thenReturn("NEUTRAL");

        // Act
        engagementService.processUserComments(testUser);

        // Assert - OPTIMIZED BEHAVIOR
        // We expect findExistingCommentIds to be called once
        verify(processedRepository, times(1)).findExistingCommentIds(anyCollection());
        // We expect existsByCommentId to NEVER be called
        verify(processedRepository, never()).existsByCommentId(anyString());
    }

    @Test
    void processUserComments_shouldDoNothing_WhenYouTubeReturnsNull() throws Exception {
        // Arrange
        when(youTubeService.isConnected("testuser")).thenReturn(true);
        // Simulate 304 Not Modified
        when(youTubeService.getCommentThreads("testuser")).thenReturn(null);

        // Act
        engagementService.processUserComments(testUser);

        // Assert
        verify(processedRepository, never()).findExistingCommentIds(anyCollection());
        verify(aiService, never()).analyzeSentiment(anyString());
    }

    @Test
    void processUserComments_shouldSkipExistingComments() throws Exception {
        // Arrange
        when(youTubeService.isConnected("testuser")).thenReturn(true);

        CommentThread thread1 = createCommentThread("c1", "video1", "Nice video!");
        CommentThread thread2 = createCommentThread("c2", "video1", "Great content!");

        CommentThreadListResponse response = new CommentThreadListResponse();
        response.setItems(Arrays.asList(thread1, thread2));

        when(youTubeService.getCommentThreads("testuser")).thenReturn(response);

        // Mock that "c1" already exists
        when(processedRepository.findExistingCommentIds(anyCollection())).thenReturn(Collections.singleton("c1"));

        // Mock AI service only for the new comment
        when(aiService.analyzeSentiment("Great content!")).thenReturn("NEUTRAL");

        // Act
        engagementService.processUserComments(testUser);

        // Assert
        verify(processedRepository, times(1)).findExistingCommentIds(anyCollection());
        verify(aiService, never()).analyzeSentiment("Nice video!"); // Should be skipped
        verify(aiService, times(1)).analyzeSentiment("Great content!"); // Should be processed
    }

    @Test
    void processLiveEngagement_shouldSkipReply_WhenAiReturnsNull() throws Exception {
        // Arrange
        StreamJob job = new StreamJob();
        job.setUsername("testuser");
        job.setLiveChatId("chat123");
        job.setAutoReplyEnabled(true);
        job.setLive(true);

        when(appSettingRepository.findById("ENGAGEMENT_CRON_ENABLED")).thenReturn(Optional.empty()); // Defaults to true
        when(streamJobRepo.findByIsLiveTrueAndAutoReplyEnabledTrue()).thenReturn(Collections.singletonList(job));
        when(youTubeService.isConnected("testuser")).thenReturn(true);

        LiveChatMessage msg = new LiveChatMessage();
        msg.setId("msg1");
        LiveChatMessageSnippet snippet = new LiveChatMessageSnippet();
        snippet.setType("textMessageEvent");
        snippet.setAuthorChannelId("otherChannel"); // Not me
        LiveChatTextMessageDetails details = new LiveChatTextMessageDetails();
        details.setMessageText("Hello Streamer!");
        snippet.setTextMessageDetails(details);
        msg.setSnippet(snippet);

        LiveChatMessageListResponse response = new LiveChatMessageListResponse();
        response.setItems(Collections.singletonList(msg));
        response.setNextPageToken("nextPage");

        when(youTubeService.getLiveChatMessages(eq("testuser"), eq("chat123"), any())).thenReturn(response);
        when(youTubeService.getChannelId("testuser")).thenReturn("myChannelId");

        // SIMULATE AI FAILURE
        when(aiService.generateTwitterStyleReply("Hello Streamer!")).thenReturn(null);

        // Act
        engagementService.processLiveEngagement();

        // Assert
        verify(youTubeService, never()).replyToLiveChat(anyString(), anyString(), anyString());
        verify(activityRepository, never()).save(any()); // Should not log activity if no reply sent
    }

    private CommentThread createCommentThread(String commentId, String videoId, String text) {
        CommentSnippet topLevelSnippet = new CommentSnippet()
                .setTextDisplay(text);

        Comment topLevelComment = new Comment()
                .setId(commentId)
                .setSnippet(topLevelSnippet);

        CommentThreadSnippet threadSnippet = new CommentThreadSnippet()
                .setTopLevelComment(topLevelComment)
                .setVideoId(videoId);

        return new CommentThread()
                .setSnippet(threadSnippet);
    }
}
