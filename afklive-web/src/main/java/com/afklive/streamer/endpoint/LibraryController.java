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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final com.afklive.streamer.service.ImportService importService;
    private final com.afklive.streamer.service.VideoConversionService conversionService;

    @PostMapping("/import-youtube")
    public ResponseEntity<?> importFromYouTube(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.ImportRequest request, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        importService.downloadFromYouTube(request.getUrl(), username);
        return ResponseEntity.ok(ApiResponse.success("Import started. Check library shortly.", null));
    }

    @PostMapping("/import-drive")
    public ResponseEntity<?> importFromGoogleDrive(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.ImportRequest request, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        importService.downloadFromGoogleDrive(request.getUrl(), username);
        return ResponseEntity.ok(ApiResponse.success("Import started. Check library shortly.", null));
    }

    @PostMapping("/merge")
    public ResponseEntity<?> mergeVideos(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.MergeRequest request, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);
        List<String> files = request.getFiles();

        // Verify ownership
        List<ScheduledVideo> userVideos = repository.findByUsername(username);
        List<String> safeFiles = files.stream()
                .filter(f -> userVideos.stream().anyMatch(v -> v.getTitle().equals(f) && v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY))
                .collect(Collectors.toList());

        if (safeFiles.size() != files.size()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid file selection"));
        }

        String outputName = "merged_" + System.currentTimeMillis() + ".mp4";

        try {
            List<ScheduledVideo> selectedVideos = safeFiles.stream()
                    .map(f -> userVideos.stream().filter(uv -> uv.getTitle().equals(f)).findFirst().orElseThrow())
                    .collect(Collectors.toList());

            // Trigger async merge
            conversionService.mergeVideosAsync(selectedVideos, username, outputName);

            return ResponseEntity.ok(ApiResponse.success("Merge started. Check library shortly.", null));
        } catch (Exception e) {
            log.error("Merge init failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Merge failed: " + e.getMessage()));
        }
    }

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
    public ResponseEntity<?> getLibraryVideos(
            java.security.Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        Page<ScheduledVideo> videoPage = repository.findByUsernameAndStatus(
                username,
                ScheduledVideo.VideoStatus.LIBRARY,
                PageRequest.of(page, size, Sort.by("id").descending())
        );

        return ResponseEntity.ok(ApiResponse.success("Library fetched", videoPage));
    }

    @GetMapping("/stream/{id}")
    public ResponseEntity<?> streamVideo(@PathVariable Long id, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String username = SecurityUtils.getEmail(principal);

        ScheduledVideo video = repository.findById(id).orElse(null);
        if (video == null || !video.getUsername().equals(username)) {
            return ResponseEntity.notFound().build();
        }

        // Try Presigned URL first (Optimization for S3)
        Optional<String> presignedUrl = storageService.generatePresignedUrl(video.getS3Key());
        if (presignedUrl.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, presignedUrl.get())
                    .build();
        }

        // Fallback to streaming through backend (e.g. Local Storage)
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


    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLibraryVideo(@PathVariable Long id, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        ScheduledVideo video = repository.findById(id).orElse(null);
        if (video == null) {
            return ResponseEntity.notFound().build();
        }
        if (!video.getUsername().equals(username)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Forbidden"));
        }

        try {
            // Delete from S3/Storage
            storageService.deleteFile(video.getS3Key());

            // Delete Thumbnail if exists
            if (video.getThumbnailS3Key() != null) {
                storageService.deleteFile(video.getThumbnailS3Key());
            }

            // Delete from DB
            repository.delete(video);

            if (video.getFileSize() != null) {
                userService.updateStorageUsage(username, -video.getFileSize());
            }

            return ResponseEntity.ok(ApiResponse.success("Video deleted", null));
        } catch (Exception e) {
            log.error("Failed to delete video", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete video"));
        }
    }

    @PostMapping("/auto-schedule")
    public ResponseEntity<?> autoSchedule(
            @jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.AutoScheduleRequest request,
            java.security.Principal principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        try {
            List<String> timeSlots = request.getTimeSlots();
            String startDateStr = request.getStartDate();

            LocalDate currentDate = LocalDate.parse(startDateStr);
            List<LocalTime> times = timeSlots.stream().map(LocalTime::parse).sorted().toList();

            boolean useAi = request.isUseAi();
            String topic = request.getTopic();

            // Get all LIBRARY videos for user
            List<ScheduledVideo> libraryVideos = repository.findByUsername(username).stream()
                    .filter(v -> v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY)
                    .collect(Collectors.toList());

            int videoIndex = 0;
            while (videoIndex < libraryVideos.size()) {
                for (LocalTime time : times) {
                    if (videoIndex >= libraryVideos.size()) break;

                    ScheduledVideo video = libraryVideos.get(videoIndex);
                    // Treat input time as UTC
                    ZonedDateTime schedule = ZonedDateTime.of(currentDate, time, ZoneId.of("UTC"));

                    if (schedule.isBefore(ZonedDateTime.now(ZoneId.of("UTC")))) {
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
