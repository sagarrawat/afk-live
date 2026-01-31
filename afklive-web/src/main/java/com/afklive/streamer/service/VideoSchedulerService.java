package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.repository.SocialChannelRepository;
import com.afklive.streamer.util.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoSchedulerService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VideoSchedulerService.class);

    private final ScheduledVideoRepository repository;
    private final SocialChannelRepository channelRepository;
    private final FileStorageService storageService;
    private final YouTubeService youTubeService;
    private final InstagramService instagramService;
    private final SnapchatService snapchatService;
    private final EmailService emailService;

    @Scheduled(fixedRate = 3_60_000)
    public void processScheduledVideos() {
        log.info("Checking for scheduled videos...");
        List<ScheduledVideo> videosToUpload = repository.findByStatusAndScheduledTimeLessThanEqual(
                ScheduledVideo.VideoStatus.PENDING, LocalDateTime.now());

        for (ScheduledVideo video : videosToUpload) {
            uploadVideo(video);
        }
    }

    private void uploadVideo(ScheduledVideo video) {
        log.info("Starting upload for video ID: {}", video.getId());
        video.setStatus(ScheduledVideo.VideoStatus.PROCESSING);
        repository.save(video);

        try {
            InputStream fileStream = storageService.downloadFile(video.getS3Key());
            String videoId = null;
            String platform = AppConstants.PLATFORM_YOUTUBE;
            String credentialId = video.getUsername(); // Default to owner

            if (video.getSocialChannelId() != null) {
                SocialChannel channel = channelRepository.findById(video.getSocialChannelId()).orElse(null);
                if (channel != null) {
                    platform = channel.getPlatform();
                    if (channel.getCredentialId() != null) {
                        credentialId = channel.getCredentialId();
                    }
                }
            }

            if (AppConstants.PLATFORM_INSTAGRAM.equals(platform)) {
                videoId = instagramService.uploadVideo(
                        credentialId,
                        fileStream,
                        video.getTitle(),
                        video.getDescription()
                );
            } else if (AppConstants.PLATFORM_SNAPCHAT.equals(platform)) {
                videoId = snapchatService.uploadVideo(
                        credentialId,
                        fileStream,
                        video.getTitle(),
                        video.getDescription()
                );
            } else {
                // Default to YouTube
                videoId = youTubeService.uploadVideo(
                        credentialId,
                        fileStream,
                        video.getTitle(),
                        video.getDescription(),
                        video.getTags(),
                        video.getPrivacyStatus(),
                        video.getCategoryId()
                );

                if (video.getThumbnailS3Key() != null) {
                    try (InputStream thumbStream = storageService.downloadFile(video.getThumbnailS3Key())) {
                        youTubeService.uploadThumbnail(credentialId, videoId, thumbStream);
                    } catch (Exception e) {
                        log.error("Failed to upload thumbnail for video {}", video.getId(), e);
                    }
                }

                // Post First Comment if set (YouTube only for now)
                if (video.getFirstComment() != null && !video.getFirstComment().isEmpty()) {
                    try {
                        youTubeService.addComment(credentialId, videoId, video.getFirstComment());
                        log.info("Posted first comment for video ID: {}", video.getId());
                    } catch (Exception e) {
                        log.error("Failed to post first comment for video ID: {}", video.getId(), e);
                    }
                }
            }

            video.setYoutubeVideoId(videoId); // Reuse field for ID
            video.setStatus(ScheduledVideo.VideoStatus.UPLOADED);
            log.info("Successfully uploaded video ID: {} to {}", video.getId(), platform);
            emailService.sendUploadNotification(video.getUsername(), video.getTitle(), "UPLOADED to " + platform);
        } catch (Exception e) {
            log.error("Failed to upload video ID: {}", video.getId(), e);
            video.setStatus(ScheduledVideo.VideoStatus.FAILED);
            video.setErrorMessage(e.getMessage());
            emailService.sendUploadNotification(video.getUsername(), video.getTitle(), "FAILED: " + e.getMessage());
        } finally {
            repository.save(video);
        }
    }
}
