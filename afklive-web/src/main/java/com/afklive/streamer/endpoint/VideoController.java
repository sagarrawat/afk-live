package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.AudioService;
import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.service.YouTubeService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VideoController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VideoController.class);

    private final FileStorageService storageService;
    private final ScheduledVideoRepository repository;
    private final YouTubeService youTubeService;
    private final UserService userService;
    private final AudioService audioService;

    @GetMapping("/youtube/categories")
    public ResponseEntity<?> getVideoCategories(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        try {
            return ResponseEntity.ok(youTubeService.getVideoCategories(SecurityUtils.getEmail(principal), "US"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/videos/schedule")
    public ResponseEntity<?> scheduleVideo(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "libraryVideoId", required = false) Long libraryVideoId,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestParam("title") String title,
            @RequestParam("description") String description,
            @RequestParam("tags") String tags,
            @RequestParam("privacyStatus") String privacyStatus,
            @RequestParam(value = "categoryId", required = false) String categoryId,
            @RequestParam("scheduledTime") String scheduledTimeStr,
            @RequestParam(value = "firstComment", required = false) String firstComment,
            @RequestParam(value = "audioFile", required = false) MultipartFile audioFile,
            @RequestParam(value = "audioTrackId", required = false) String audioTrackId,
            @RequestParam(value = "audioVolume", defaultValue = "0.5") String audioVolume,
            Principal principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String username = SecurityUtils.getEmail(principal);

        try {
            log.info("Scheduling video for user: {}", username);

            String s3Key;
            long finalSize = 0;

            if (libraryVideoId != null) {
                // Schedule from Library
                Optional<ScheduledVideo> libVideoOpt = repository.findById(libraryVideoId);
                if (libVideoOpt.isEmpty()) return ResponseEntity.badRequest().body("Library video not found");
                ScheduledVideo libVideo = libVideoOpt.get();
                if (!libVideo.getUsername().equals(username)) return ResponseEntity.status(403).body("Forbidden");

                // Check quota for the COPY
                // If we copy, we use more storage.
                long size = libVideo.getFileSize() != null ? libVideo.getFileSize() : 0;
                userService.checkStorageQuota(username, size);

                // Copy file in S3 (by download/upload for simplicity as we don't have S3 copy exposed)
                try (InputStream is = storageService.downloadFile(libVideo.getS3Key())) {
                    s3Key = storageService.uploadFile(is, libVideo.getTitle(), size);
                }
                finalSize = size;

            } else if (file != null && !file.isEmpty()) {
                // Normal Upload
                userService.checkStorageQuota(username, file.getSize());
                finalSize = file.getSize();

                if ((audioFile != null && !audioFile.isEmpty()) || (audioTrackId != null && !audioTrackId.isEmpty())) {
                    // Mix Audio
                    Path tempVideo = Files.createTempFile("vid", ".mp4");
                    Path tempAudio = Files.createTempFile("aud", ".mp3");
                    Path tempOut = Files.createTempFile("mix", ".mp4");

                    try {
                        file.transferTo(tempVideo);

                        if (audioFile != null && !audioFile.isEmpty()) {
                            audioFile.transferTo(tempAudio);
                        } else if (audioTrackId != null) {
                            Path fetchedAudio = audioService.getAudioPath(audioTrackId);
                            Files.copy(fetchedAudio, tempAudio, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            Files.deleteIfExists(fetchedAudio);
                        }

                        List<String> cmd = FFmpegCommandBuilder.buildMixCommand(tempVideo, tempAudio, audioVolume, tempOut);
                        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                        p.getInputStream().transferTo(System.out); // Pipe to stdout log

                        int exit = p.waitFor();
                        if (exit != 0) throw new RuntimeException("FFmpeg audio mix failed with exit code " + exit);

                        finalSize = Files.size(tempOut);
                        try (InputStream is = Files.newInputStream(tempOut)) {
                            s3Key = storageService.uploadFile(is, file.getOriginalFilename(), finalSize);
                        }
                    } finally {
                        Files.deleteIfExists(tempVideo);
                        Files.deleteIfExists(tempAudio);
                        Files.deleteIfExists(tempOut);
                    }
                } else {
                    s3Key = storageService.uploadFile(file.getInputStream(), file.getOriginalFilename(), file.getSize());
                }
            } else {
                return ResponseEntity.badRequest().body("File or Library Video required");
            }

            userService.updateStorageUsage(username, finalSize);

            String thumbnailKey = null;
            if (thumbnail != null && !thumbnail.isEmpty()) {
                userService.checkStorageQuota(username, thumbnail.getSize());
                thumbnailKey = storageService.uploadFile(thumbnail.getInputStream(), thumbnail.getOriginalFilename(), thumbnail.getSize());
                userService.updateStorageUsage(username, thumbnail.getSize());
            }

            // Parse time
            ZonedDateTime scheduledTime = ZonedDateTime.parse(scheduledTimeStr);

            // Save Metadata
            ScheduledVideo video = new ScheduledVideo();
            video.setUsername(username);
            video.setTitle(title);
            video.setDescription(description);
            video.setTags(tags);
            video.setCategoryId(categoryId);
            video.setPrivacyStatus(privacyStatus);
            video.setCategoryId(categoryId);
            video.setScheduledTime(scheduledTime);
            video.setS3Key(s3Key);
            video.setThumbnailS3Key(thumbnailKey);
            if (firstComment != null && !firstComment.trim().isEmpty()) {
                video.setFirstComment(firstComment);
            }
            video.setStatus(ScheduledVideo.VideoStatus.PENDING);

            repository.save(video);

            return ResponseEntity.ok(Map.of("success", true, "message", "Video scheduled successfully", "id", video.getId()));
        } catch (Exception e) {
            log.error("Error scheduling video", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/videos")
    public ResponseEntity<?> getScheduledVideos(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String username = SecurityUtils.getEmail(principal);

        List<ScheduledVideo> videos = repository.findByUsername(username);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/youtube/status")
    public ResponseEntity<?> getYouTubeStatus(Principal principal) {
        if (principal == null) return ResponseEntity.ok(Map.of("connected", false));
        String username = SecurityUtils.getEmail(principal);
        boolean connected = youTubeService.isConnected(username);
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    @GetMapping("/videos/{id}/thumbnail")
    public ResponseEntity<?> getVideoThumbnail(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String username = SecurityUtils.getEmail(principal);

        Optional<ScheduledVideo> videoOpt = repository.findById(id);
        if (videoOpt.isEmpty()) return ResponseEntity.notFound().build();

        ScheduledVideo video = videoOpt.get();
        if (!video.getUsername().equals(username)) return ResponseEntity.status(403).build();

        if (video.getThumbnailS3Key() == null) return ResponseEntity.notFound().build();

        // Try Presigned URL
        Optional<String> presignedUrl = storageService.generatePresignedUrl(video.getThumbnailS3Key());
        if (presignedUrl.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, presignedUrl.get())
                    .build();
        }

        try {
            InputStream is = storageService.downloadFile(video.getThumbnailS3Key());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG) // Assuming JPEG
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            log.error("Failed to fetch thumbnail", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
