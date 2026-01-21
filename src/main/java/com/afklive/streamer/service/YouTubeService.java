package com.afklive.streamer.service;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import com.google.api.services.youtubeAnalytics.v2.YouTubeAnalytics;
import com.google.api.services.youtubeAnalytics.v2.model.QueryResponse;
import com.afklive.streamer.util.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@Service
public class YouTubeService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(YouTubeService.class);

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager;
    private final com.afklive.streamer.service.ChannelService channelService;
    private static final String APPLICATION_NAME = "AFK Live Streamer";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    public YouTubeService(@Qualifier("serviceAuthorizedClientManager") AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager,
                          @org.springframework.context.annotation.Lazy com.afklive.streamer.service.ChannelService channelService) {
        this.authorizedClientManager = authorizedClientManager;
        this.channelService = channelService;
    }

    // --- HELPER METHODS ---

    private Authentication createPrincipal(String username) {
        return new UsernamePasswordAuthenticationToken(username, "N/A", AuthorityUtils.NO_AUTHORITIES);
    }

    private Credential getCredential(String username) {
        try {
            // Resolve the correct Credential ID (Google Subject ID) if possible
            // If the user has linked a YouTube channel, the token is stored under the Google Subject ID.
            // If we just use 'username' (email), it might fail if the token is keyed by Subject ID.
            String credentialId = channelService.getCredentialId(username);

            Authentication principal = createPrincipal(credentialId);
            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(AppConstants.OAUTH_GOOGLE_YOUTUBE)
                    .principal(principal)
                    .build();

            OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);

            if (client == null || client.getAccessToken() == null) {
                // Try fallback to username if credentialId didn't work (backward compatibility or direct login)
                if (!credentialId.equals(username)) {
                     principal = createPrincipal(username);
                     authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(AppConstants.OAUTH_GOOGLE_YOUTUBE)
                            .principal(principal)
                            .build();
                     client = authorizedClientManager.authorize(authorizeRequest);
                }

                if (client == null || client.getAccessToken() == null) {
                    throw new IllegalStateException("User " + username + " is not connected to YouTube.");
                }
            }

            return new Credential(BearerToken.authorizationHeaderAccessMethod())
                    .setAccessToken(client.getAccessToken().getTokenValue());
        } catch (Exception e) {
            log.error("Failed to get credential for user {}", username, e);
            throw new RuntimeException("Authentication failed: " + e.getMessage());
        }
    }

    private YouTube getYouTubeClient(String username) throws Exception {
        return new YouTube.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, getCredential(username))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private YouTubeAnalytics getAnalyticsClient(String username) throws Exception {
        return new YouTubeAnalytics.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, getCredential(username))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    // --- CONNECTION CHECK ---

    public boolean isConnected(String username) {
        try {
            getCredential(username);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- VIDEO UPLOAD ---

    public String uploadVideo(String username, InputStream fileStream, String title, String description, String tags, String privacyStatus, String categoryId) throws Exception {
        YouTube youtube = getYouTubeClient(username);

        Video video = new Video();

        VideoSnippet snippet = new VideoSnippet();
        snippet.setTitle(title);
        snippet.setDescription(description);
        if (tags != null && !tags.isEmpty()) {
            snippet.setTags(List.of(tags.split("\\s*,\\s*")));
        }
        if (categoryId != null && !categoryId.isEmpty()) {
            snippet.setCategoryId(categoryId);
        }
        video.setSnippet(snippet);

        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus(privacyStatus);
        video.setStatus(status);

        InputStreamContent mediaContent = new InputStreamContent("video/*", fileStream);

        YouTube.Videos.Insert insertRequest = youtube.videos().insert(Collections.singletonList("snippet,status"), video, mediaContent);
        Video returnedVideo = insertRequest.execute();

        log.info("Uploaded video ID: {}", returnedVideo.getId());
        return returnedVideo.getId();
    }

    public void uploadThumbnail(String username, String videoId, InputStream thumbnailStream) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        InputStreamContent mediaContent = new InputStreamContent("application/octet-stream", thumbnailStream);
        youtube.thumbnails().set(videoId, mediaContent).execute();
        log.info("Uploaded thumbnail for video ID: {}", videoId);
    }

    // --- ANALYTICS ---

    public QueryResponse getChannelAnalytics(String username, String startDate, String endDate) throws Exception {
        YouTubeAnalytics analytics = getAnalyticsClient(username);

        return analytics.reports().query()
                .setIds("channel==MINE")
                .setStartDate(startDate)
                .setEndDate(endDate)
                .setMetrics("views,subscribersGained,estimatedMinutesWatched")
                .setDimensions("day")
                .setSort("day")
                .execute();
    }

    // --- COMMENTS ---

    public CommentThreadListResponse getCommentThreads(String username) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        return youtube.commentThreads().list(Collections.singletonList("snippet,replies"))
                .setAllThreadsRelatedToChannelId(getChannelId(username))
                .setMaxResults(20L)
                .execute();
    }

    public String getChannelName(String username) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        ChannelListResponse response = youtube.channels().list(Collections.singletonList("snippet"))
                .setMine(true)
                .execute();
        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("No channel found for user.");
        }
        return response.getItems().getFirst().getSnippet().getTitle();
    }

    private String getChannelId(String username) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        ChannelListResponse response = youtube.channels().list(Collections.singletonList("id"))
                .setMine(true)
                .execute();
        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("No channel found for user.");
        }
        return response.getItems().getFirst().getId();
    }

    public void addComment(String username, String videoId, String text) throws Exception {
        YouTube youtube = getYouTubeClient(username);

        CommentThread thread = new CommentThread();
        CommentThreadSnippet snippet = new CommentThreadSnippet();
        snippet.setVideoId(videoId);

        Comment topLevelComment = new Comment();
        CommentSnippet commentSnippet = new CommentSnippet();
        commentSnippet.setTextOriginal(text);
        topLevelComment.setSnippet(commentSnippet);

        snippet.setTopLevelComment(topLevelComment);
        thread.setSnippet(snippet);

        youtube.commentThreads().insert(Collections.singletonList("snippet"), thread).execute();
    }

    public List<CommentThread> getUnrepliedComments(String username) throws Exception {
        CommentThreadListResponse response = getCommentThreads(username);
        if (response.getItems() == null) return Collections.emptyList();

        // Filter: Keep threads where replies is empty OR none of the replies are from the channel owner
        // Note: 'replies' field in CommentThread might be null if reply count is 0
        // We'll simplify: if totalReplyCount == 0, it's unreplied.
        // If > 0, we need to check if we replied.

        String channelId = getChannelId(username);
        List<CommentThread> unreplied = new java.util.ArrayList<>();

        for (CommentThread thread : response.getItems()) {
            boolean repliedByOwner = false;
            if (thread.getSnippet().getTotalReplyCount() > 0 && thread.getReplies() != null) {
                for (Comment reply : thread.getReplies().getComments()) {
                   // Check author channel ID
                   if (reply.getSnippet().getAuthorChannelId().getValue().equals(channelId)) {
                       repliedByOwner = true;
                       break;
                   }
                }
            }

            if (!repliedByOwner) {
                unreplied.add(thread);
            }
        }
        return unreplied;
    }

    public String replyToComment(String username, String parentId, String text) throws Exception {
        YouTube youtube = getYouTubeClient(username);

        Comment comment = new Comment();
        CommentSnippet snippet = new CommentSnippet();
        snippet.setParentId(parentId);
        snippet.setTextOriginal(text);
        comment.setSnippet(snippet);

        Comment inserted = youtube.comments().insert(Collections.singletonList("snippet"), comment).execute();
        return inserted.getId();
    }

    public void deleteComment(String username, String commentId) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        youtube.comments().delete(commentId).execute();
    }


    // --- CATEGORIES ---

    public List<VideoCategory> getVideoCategories(String username, String regionCode) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        VideoCategoryListResponse response = youtube.videoCategories().list(Collections.singletonList("snippet"))
                .setRegionCode(regionCode != null ? regionCode : "US")
                .execute();
        return response.getItems();
    }

    // --- STREAM KEYS ---

    public String getStreamKey(String username) throws Exception {
        YouTube youtube = getYouTubeClient(username);
        // "cdn" part contains the ingestion info (stream name/key)
        LiveStreamListResponse response = youtube.liveStreams().list(Collections.singletonList("cdn"))
                .setMine(true)
                .execute();

        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("No live streams found for this channel. Please enable live streaming in YouTube Studio.");
        }

        // Usually the default stream is the first one or we pick the first available
        return response.getItems().get(0).getCdn().getIngestionInfo().getStreamName();
    }
}
