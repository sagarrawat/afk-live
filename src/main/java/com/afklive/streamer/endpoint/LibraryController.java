package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.AppConstants;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
public class LibraryController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LibraryController.class);

    private final FileStorageService storageService;
    private final ScheduledVideoRepository repository;
    private final UserService userService;
    private final AiService aiService;

    @PostMapping("/upload")
    public ResponseEntity<?> bulkUpload(
            @RequestParam(AppConstants.PARAM_FILES) List<MultipartFile> files,
            java.security.Principal principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        try {
            int successCount = 0;
            for (MultipartFile file : files) {
                if (file.isEmpty()) continue;

                if (file.getOriginalFilename().toLowerCase().endsWith(".zip")) {
                    // Handle Zip
                    try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.isDirectory() || !isVideo(entry.getName())) continue;

                            // Approx size check (entry.getSize() is -1 if unknown)
                            long size = entry.getSize() > 0 ? entry.getSize() : file.getSize(); // Fallback to zip size (imperfect)
                            userService.checkStorageQuota(username, size);

                            String s3Key = storageService.uploadFile(zis, entry.getName(), size);
                            userService.updateStorageUsage(username, size);
                            createLibraryEntry(username, entry.getName(), s3Key, size);
                            successCount++;
                        }
                    }
                } else {
                    // Regular file
                    userService.checkStorageQuota(username, file.getSize());
                    String s3Key = storageService.uploadFile(file.getInputStream(), file.getOriginalFilename(), file.getSize());
                    userService.updateStorageUsage(username, file.getSize());
                    createLibraryEntry(username, file.getOriginalFilename(), s3Key, file.getSize());
                    successCount++;
                }
            }
            return ResponseEntity.ok(ApiResponse.success("Uploaded " + successCount + " videos to library", null));
        } catch (Exception e) {
            log.error("Bulk upload failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Bulk upload failed: " + e.getMessage()));
        }
    }

    private boolean isVideo(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".avi");
    }

    private void createLibraryEntry(String username, String filename, String key, long size) {
        ScheduledVideo video = new ScheduledVideo();
        video.setUsername(username);
        video.setTitle(filename);
        video.setS3Key(key);
        video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
        video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
        // We need a size field in ScheduledVideo.
        // Since I cannot change the Entity easily without restart/schema issues in this mock env,
        // I will overload the 'description' or add a transient field?
        // Actually the prompt says "In library can we also show the size".
        // I'll add `fileSize` to ScheduledVideo entity.
        video.setFileSize(size);
        repository.save(video);
    }

    @GetMapping
    public ResponseEntity<?> getLibraryVideos(java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        List<ScheduledVideo> videos = repository.findByUsername(username).stream()
                .filter(v -> v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("Library fetched", videos));
    }

    @GetMapping("/stream/{id}")
    public ResponseEntity<Resource> streamVideo(@PathVariable Long id, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String username = SecurityUtils.getEmail(principal);

        ScheduledVideo video = repository.findById(id).orElse(null);
        if (video == null || !video.getUsername().equals(username)) {
            return ResponseEntity.notFound().build();
        }

        try {
            Resource resource = storageService.loadFileAsResource(video.getS3Key());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(AppConstants.MIME_VIDEO_MP4))
                    .body(resource);
        } catch (Exception e) {
            log.error("Failed to stream video", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/auto-schedule")
    public ResponseEntity<?> autoSchedule(
            @RequestBody Map<String, Object> payload,
            java.security.Principal principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        try {
            List<String> timeSlots = (List<String>) payload.get("timeSlots"); // e.g. ["10:00", "14:00"]
            String startDateStr = (String) payload.get("startDate"); // "2023-10-27"

            if (timeSlots == null || timeSlots.isEmpty() || startDateStr == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Missing timeSlots or startDate"));
            }

            LocalDate currentDate = LocalDate.parse(startDateStr);
            List<LocalTime> times = timeSlots.stream().map(LocalTime::parse).sorted().toList();

            boolean useAi = Boolean.TRUE.equals(payload.get("useAi"));
            String topic = (String) payload.get("topic");

            // Get all LIBRARY videos for user
            List<ScheduledVideo> libraryVideos = repository.findByUsername(username).stream()
                    .filter(v -> v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY)
                    .collect(Collectors.toList());

            int videoIndex = 0;
            while (videoIndex < libraryVideos.size()) {
                for (LocalTime time : times) {
                    if (videoIndex >= libraryVideos.size()) break;

                    ScheduledVideo video = libraryVideos.get(videoIndex);
                    LocalDateTime schedule = LocalDateTime.of(currentDate, time);

                    if (schedule.isBefore(LocalDateTime.now())) {
                        schedule = schedule.plusDays(1);
                    }

                    if (useAi) {
                        String context = (topic != null && !topic.isEmpty()) ? topic : video.getTitle();
                        video.setTitle(aiService.generateTitle(context));
                        video.setDescription(aiService.generateDescription(video.getTitle()));
                        video.setTags(aiService.generateTags(context));
                    }

                    video.setScheduledTime(schedule);
                    video.setStatus(ScheduledVideo.VideoStatus.PENDING);
                    repository.save(video);

                    videoIndex++;
                }
                currentDate = currentDate.plusDays(1);
            }

            return ResponseEntity.ok(ApiResponse.success("Scheduled " + videoIndex + " videos", null));

        } catch (Exception e) {
            log.error("Auto-schedule failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Scheduling failed: " + e.getMessage()));
        }
    }
}
