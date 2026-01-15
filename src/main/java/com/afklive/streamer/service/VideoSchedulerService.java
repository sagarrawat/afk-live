package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoSchedulerService {

    private final ScheduledVideoRepository repository;
    private final FileStorageService storageService;
    private final YouTubeService youTubeService;
    private final EmailService emailService;

    @Scheduled(fixedRate = 60000) // Run every minute
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
            String videoId = youTubeService.uploadVideo(
                    video.getUsername(),
                    fileStream,
                    video.getTitle(),
                    video.getDescription(),
                    video.getTags(),
                    video.getPrivacyStatus(),
                    video.getCategoryId()
            );

            video.setYoutubeVideoId(videoId);
            video.setStatus(ScheduledVideo.VideoStatus.UPLOADED);
            log.info("Successfully uploaded video ID: {}", video.getId());
            emailService.sendUploadNotification(video.getUsername(), video.getTitle(), "UPLOADED");
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
