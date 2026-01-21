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
@Slf4j
public class YouTubeService {

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager;
    private static final String APPLICATION_NAME = "AFK Live Streamer";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    public YouTubeService(@Qualifier("serviceAuthorizedClientManager") AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    // --- HELPER METHODS ---

    private Authentication createPrincipal(String credentialId) {
        // The authorizedClientManager uses the principal name to look up the client.
        // If the client was stored under the Google ID (sub), we must use that ID here.
        return new UsernamePasswordAuthenticationToken(credentialId, "N/A", AuthorityUtils.NO_AUTHORITIES);
    }

    private Credential getCredential(String credentialId) {
        try {
            Authentication principal = createPrincipal(credentialId);
            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(AppConstants.OAUTH_GOOGLE_YOUTUBE)
                    .principal(principal)
                    .build();

            OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);

            if (client == null || client.getAccessToken() == null) {
                // Try refreshing? The manager handles refresh if refresh token is present.
                // If it returns null, it means no client found or re-auth required.
                throw new IllegalStateException("Credential not found for ID: " + credentialId);
            }

            return new Credential(BearerToken.authorizationHeaderAccessMethod())
                    .setAccessToken(client.getAccessToken().getTokenValue());
        } catch (Exception e) {
            log.error("Failed to get credential for ID {}", credentialId, e);
            throw new RuntimeException("Authentication failed: " + e.getMessage());
        }
    }

    private YouTube getYouTubeClient(String credentialId) throws Exception {
        return new YouTube.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, getCredential(credentialId))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private YouTubeAnalytics getAnalyticsClient(String credentialId) throws Exception {
        return new YouTubeAnalytics.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, getCredential(credentialId))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    // --- CONNECTION CHECK ---

    public boolean isConnected(String credentialId) {
        try {
            getCredential(credentialId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- VIDEO UPLOAD ---

    public String uploadVideo(String credentialId, InputStream fileStream, String title, String description, String tags, String privacyStatus, String categoryId) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);

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

    public void uploadThumbnail(String credentialId, String videoId, InputStream thumbnailStream) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);
        InputStreamContent mediaContent = new InputStreamContent("application/octet-stream", thumbnailStream);
        youtube.thumbnails().set(videoId, mediaContent).execute();
        log.info("Uploaded thumbnail for video ID: {}", videoId);
    }

    // --- ANALYTICS ---

    public QueryResponse getChannelAnalytics(String credentialId, String startDate, String endDate) throws Exception {
        YouTubeAnalytics analytics = getAnalyticsClient(credentialId);

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

    public CommentThreadListResponse getCommentThreads(String credentialId) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);
        return youtube.commentThreads().list(Collections.singletonList("snippet,replies"))
                .setAllThreadsRelatedToChannelId(getChannelId(credentialId))
                .setMaxResults(20L)
                .execute();
    }

    public String getChannelName(String credentialId) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);
        ChannelListResponse response = youtube.channels().list(Collections.singletonList("snippet"))
                .setMine(true)
                .execute();
        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("No channel found for credential.");
        }
        return response.getItems().getFirst().getSnippet().getTitle();
    }

    private String getChannelId(String credentialId) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);
        ChannelListResponse response = youtube.channels().list(Collections.singletonList("id"))
                .setMine(true)
                .execute();
        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("No channel found for credential.");
        }
        return response.getItems().getFirst().getId();
    }

    public void addComment(String credentialId, String videoId, String text) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);

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

    public List<CommentThread> getUnrepliedComments(String credentialId) throws Exception {
        CommentThreadListResponse response = getCommentThreads(credentialId);
        if (response.getItems() == null) return Collections.emptyList();

        String channelId = getChannelId(credentialId);
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

    public String replyToComment(String credentialId, String parentId, String text) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);

        Comment comment = new Comment();
        CommentSnippet snippet = new CommentSnippet();
        snippet.setParentId(parentId);
        snippet.setTextOriginal(text);
        comment.setSnippet(snippet);

        Comment inserted = youtube.comments().insert(Collections.singletonList("snippet"), comment).execute();
        return inserted.getId();
    }

    public void deleteComment(String credentialId, String commentId) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);
        youtube.comments().delete(commentId).execute();
    }


    // --- CATEGORIES ---

    public List<VideoCategory> getVideoCategories(String credentialId, String regionCode) throws Exception {
        YouTube youtube = getYouTubeClient(credentialId);
        VideoCategoryListResponse response = youtube.videoCategories().list(Collections.singletonList("snippet"))
                .setRegionCode(regionCode != null ? regionCode : "US")
                .execute();
        return response.getItems();
    }

    // --- BROADCASTS (Added for Title/Description updates) ---

    public void updateBroadcast(String credentialId, String title, String description) {
        try {
            YouTube youtube = getYouTubeClient(credentialId);
            // Search for active, upcoming, or all broadcasts
            LiveBroadcastListResponse response = youtube.liveBroadcasts().list(Collections.singletonList("id,snippet,status"))
                    .setBroadcastStatus("all") // Fetch all to find the relevant one
                    .setMine(true)
                    .setMaxResults(5L) // Limit to recent ones
                    .execute();

            List<LiveBroadcast> broadcasts = response.getItems();
            if (broadcasts == null || broadcasts.isEmpty()) {
                log.info("No broadcast found for user credential: {}", credentialId);
                return;
            }

            // Heuristic: Update the most relevant broadcast.
            // Priority: active > upcoming > ready > completed (ignore completed)
            // Or just update the first non-completed one?
            LiveBroadcast target = null;

            for (LiveBroadcast b : broadcasts) {
                String status = b.getStatus().getLifeCycleStatus();
                if ("live".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status)) {
                    target = b;
                    break;
                }
            }

            if (target == null) {
                for (LiveBroadcast b : broadcasts) {
                    String status = b.getStatus().getLifeCycleStatus();
                    if ("upcoming".equalsIgnoreCase(status) || "ready".equalsIgnoreCase(status)) {
                        target = b;
                        break;
                    }
                }
            }

            if (target == null) {
                 log.info("No active/upcoming broadcast found to update.");
                 return;
            }

            LiveBroadcastSnippet snippet = target.getSnippet();
            boolean changed = false;

            if (title != null && !title.isEmpty()) {
                snippet.setTitle(title);
                changed = true;
            }
            if (description != null && !description.isEmpty()) {
                snippet.setDescription(description);
                changed = true;
            }

            if (changed) {
                LiveBroadcast update = new LiveBroadcast();
                update.setId(target.getId());
                update.setSnippet(snippet);
                update.setStatus(target.getStatus());

                youtube.liveBroadcasts().update(Collections.singletonList("snippet"), update).execute();
                log.info("Updated broadcast {} ({}) title/desc", target.getId(), target.getStatus().getLifeCycleStatus());
            }

        } catch (Exception e) {
            log.error("Failed to update broadcast metadata", e);
        }
    }
}
