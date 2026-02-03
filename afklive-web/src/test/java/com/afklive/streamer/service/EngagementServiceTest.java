package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.repository.ProcessedCommentRepository;
import com.afklive.streamer.repository.UserRepository;
import com.google.api.services.youtube.model.Comment;
import com.google.api.services.youtube.model.CommentSnippet;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import com.google.api.services.youtube.model.CommentThreadSnippet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

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
