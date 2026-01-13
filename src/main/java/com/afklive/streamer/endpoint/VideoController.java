package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.StorageService;
import com.afklive.streamer.service.YouTubeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final StorageService storageService;
    private final ScheduledVideoRepository repository;
    private final YouTubeService youTubeService;

    @PostMapping("/videos/schedule")
    public ResponseEntity<?> scheduleVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("description") String description,
            @RequestParam("tags") String tags,
            @RequestParam("privacyStatus") String privacyStatus,
            @RequestParam("scheduledTime") String scheduledTimeStr,
            @AuthenticationPrincipal OAuth2User principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String username = principal.getName(); // Use principal name (sub) for consistency with OAuth2 storage

        try {
            log.info("Scheduling video for user: {}", username);

            // Upload to S3
            String s3Key = storageService.uploadFile(file.getInputStream(), file.getOriginalFilename(), file.getSize());

            // Parse time
            LocalDateTime scheduledTime = LocalDateTime.parse(scheduledTimeStr);

            // Save Metadata
            ScheduledVideo video = new ScheduledVideo();
            video.setUsername(username);
            video.setTitle(title);
            video.setDescription(description);
            video.setTags(tags);
            video.setPrivacyStatus(privacyStatus);
            video.setScheduledTime(scheduledTime);
            video.setS3Key(s3Key);
            video.setStatus(ScheduledVideo.VideoStatus.PENDING);

            repository.save(video);

            return ResponseEntity.ok(Map.of("success", true, "message", "Video scheduled successfully", "id", video.getId()));
        } catch (Exception e) {
            log.error("Error scheduling video", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/videos")
    public ResponseEntity<?> getScheduledVideos(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String username = principal.getName();

        List<ScheduledVideo> videos = repository.findByUsername(username);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/youtube/status")
    public ResponseEntity<?> getYouTubeStatus(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return ResponseEntity.ok(Map.of("connected", false));
        String username = principal.getName();
        boolean connected = youTubeService.isConnected(username);
        return ResponseEntity.ok(Map.of("connected", connected));
    }
}
