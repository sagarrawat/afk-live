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
    private final com.afklive.streamer.repository.MediaAssetRepository mediaAssetRepository;
    private final com.afklive.streamer.service.TagService tagService;

    @PostMapping("/import-youtube")
    public ResponseEntity<?> importFromYouTube(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.ImportRequest request, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        requireImportUrl(request.getUrl(), java.util.Set.of("youtube.com", "youtu.be"));
        importService.downloadFromYouTube(request.getUrl(), username);
        return ResponseEntity.ok(ApiResponse.success("Import started. Check library shortly.", null));
    }

    @PostMapping("/import-drive")
    public ResponseEntity<?> importFromGoogleDrive(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.ImportRequest request, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        requireImportUrl(request.getUrl(), java.util.Set.of("drive.google.com", "docs.google.com"));
        importService.downloadFromGoogleDrive(request.getUrl(), username);
        return ResponseEntity.ok(ApiResponse.success("Import started. Check library shortly.", null));
    }

    @PostMapping("/merge")
    public ResponseEntity<?> mergeVideos(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.MergeRequest request, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);
        List<String> files = request.getFiles();

        // Verify ownership
        List<ScheduledVideo> userVideos = repository.findByUserEmail(username);
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

                            // Entry names and sizes come from the uploader and can't be trusted:
                            // keep only the file name, and stop reading once the user's storage is full.
                            String entryName = com.afklive.streamer.util.SafePaths.sanitizeFilename(entry.getName());
                            long remaining = userService.remainingStorageBytes(username);
                            com.afklive.streamer.util.LimitedInputStream limited =
                                    new com.afklive.streamer.util.LimitedInputStream(zis, remaining);
                            String s3Key;
                            try {
                                long declared = entry.getSize() > 0 ? entry.getSize() : -1;
                                s3Key = storageService.uploadFile(limited, entryName, declared);
                            } catch (RuntimeException ex) {
                                if (ex.getCause() instanceof com.afklive.streamer.util.LimitedInputStream.LimitExceededException limitEx) {
                                    throw new IllegalStateException(limitEx.getMessage());
                                }
                                throw ex;
                            }
                            long size = limited.getCount();
                            userService.updateStorageUsage(username, size);
                            createLibraryEntry(username, entryName, s3Key, size);
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

    /** Import URLs are fetched by the server, so only public http(s) URLs on the expected sites are allowed. */
    private static void requireImportUrl(String url, java.util.Set<String> allowedDomains) {
        com.afklive.streamer.util.NetworkSafety.requireSafeWebUrl(url);
        String host = java.net.URI.create(url.trim()).getHost().toLowerCase(java.util.Locale.ROOT);
        boolean allowed = allowedDomains.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
        if (!allowed) {
            throw new IllegalArgumentException("Please paste a link from " + String.join(" or ", allowedDomains) + ".");
        }
    }

    private boolean isVideo(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".avi");
    }

    private void createLibraryEntry(String username, String filename, String key, long size) {
        com.afklive.streamer.model.User user = userService.getOrCreateUser(username);

        // Create Media Asset
        com.afklive.streamer.model.MediaAsset asset = new com.afklive.streamer.model.MediaAsset(
            user,
            filename,
            key,
            size,
            com.afklive.streamer.model.MediaAsset.ContentType.VIDEO
        );
        asset = mediaAssetRepository.save(asset);

        // Create Scheduled Video
        ScheduledVideo video = new ScheduledVideo();
        video.setUser(user);
        video.setTitle(filename);
        video.setMediaAsset(asset);
        video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
        video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
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

        Page<ScheduledVideo> videoPage = repository.findByUserEmailAndStatus(
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
        if (video == null || !video.getUser().getEmail().equals(username)) {
            return ResponseEntity.notFound().build();
        }

        // Try Presigned URL first (Optimization for S3)
        String key = video.getMediaAsset() != null ? video.getMediaAsset().getS3Key() : null;
        if (key == null) return ResponseEntity.notFound().build();

        Optional<String> presignedUrl = storageService.generatePresignedUrl(key);
        if (presignedUrl.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, presignedUrl.get())
                    .build();
        }

        // Fallback to streaming through backend (e.g. Local Storage)
        try {
            Resource resource = storageService.loadFileAsResource(key);
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
        if (!video.getUser().getEmail().equals(username)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Forbidden"));
        }

        try {
            // Delete from S3/Storage
            if (video.getMediaAsset() != null) {
                storageService.deleteFile(video.getMediaAsset().getS3Key());
                mediaAssetRepository.delete(video.getMediaAsset());
            }

            // Delete Thumbnail if exists
            if (video.getThumbnailAsset() != null) {
                storageService.deleteFile(video.getThumbnailAsset().getS3Key());
                mediaAssetRepository.delete(video.getThumbnailAsset());
            }

            // Delete from DB
            repository.delete(video);

            // Re-calc quota from User's total used bytes, or decrement
            if (video.getMediaAsset() != null) {
                userService.updateStorageUsage(username, -video.getMediaAsset().getFileSize());
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
            List<ScheduledVideo> libraryVideos = repository.findByUserEmail(username).stream()
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
                        String generatedTags = aiService.generateTags(context);
                        video.setTags(generatedTags);
                        video.setTagEntities(tagService.parseAndSaveTags(generatedTags));
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
