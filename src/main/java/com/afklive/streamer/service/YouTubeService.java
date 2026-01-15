package com.afklive.streamer.service;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import lombok.RequiredArgsConstructor;
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

    public boolean isConnected(String username) {
        try {
            Authentication principal = createPrincipal(username);
            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId("google")
                    .principal(principal)
                    .build();

            OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);
            return client != null && client.getAccessToken() != null;
        } catch (Exception e) {
            log.warn("Failed to check connection for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    public String uploadVideo(String username, InputStream fileStream, String title, String description, String tags, String privacyStatus, String categoryId) throws Exception {

        Authentication principal = createPrincipal(username);
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId("google")
                .principal(principal)
                .build();

        OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);

        if (client == null) {
            throw new IllegalStateException("User " + username + " is not connected to YouTube.");
        }

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(client.getAccessToken().getTokenValue());

        YouTube youtube = new YouTube.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

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
        Authentication principal = createPrincipal(username);
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId("google")
                .principal(principal)
                .build();

        OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);

        if (client == null) {
            throw new IllegalStateException("User " + username + " is not connected to YouTube.");
        }

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(client.getAccessToken().getTokenValue());

        YouTube youtube = new YouTube.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        InputStreamContent mediaContent = new InputStreamContent("application/octet-stream", thumbnailStream);
        youtube.thumbnails().set(videoId, mediaContent).execute();
        log.info("Uploaded thumbnail for video ID: {}", videoId);
    }

    private Authentication createPrincipal(String username) {
        return new UsernamePasswordAuthenticationToken(username, "N/A", AuthorityUtils.NO_AUTHORITIES);
    }
}
