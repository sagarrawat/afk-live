package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
@Slf4j
public class LibraryController {

    private final StorageService storageService;
    private final ScheduledVideoRepository repository;

    @PostMapping("/upload")
    public ResponseEntity<?> bulkUpload(
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal OAuth2User principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = principal.getName();

        try {
            int successCount = 0;
            for (MultipartFile file : files) {
                if (file.isEmpty()) continue;

                String s3Key = storageService.uploadFile(file.getInputStream(), file.getOriginalFilename(), file.getSize());

                ScheduledVideo video = new ScheduledVideo();
                video.setUsername(username);
                video.setTitle(file.getOriginalFilename()); // Default title
                video.setS3Key(s3Key);
                video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
                video.setPrivacyStatus("private"); // Default

                repository.save(video);
                successCount++;
            }
            return ResponseEntity.ok(ApiResponse.success("Uploaded " + successCount + " videos to library", null));
        } catch (Exception e) {
            log.error("Bulk upload failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Bulk upload failed: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getLibraryVideos(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = principal.getName();

        List<ScheduledVideo> videos = repository.findByUsername(username).stream()
                .filter(v -> v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("Library fetched", videos));
    }

    @PostMapping("/auto-schedule")
    public ResponseEntity<?> autoSchedule(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal OAuth2User principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = principal.getName();

        try {
            List<String> timeSlots = (List<String>) payload.get("timeSlots"); // e.g. ["10:00", "14:00"]
            String startDateStr = (String) payload.get("startDate"); // "2023-10-27"

            if (timeSlots == null || timeSlots.isEmpty() || startDateStr == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Missing timeSlots or startDate"));
            }

            LocalDate currentDate = LocalDate.parse(startDateStr);
            List<LocalTime> times = timeSlots.stream().map(LocalTime::parse).sorted().toList();

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

                    // Skip if time is in past (simple check, assume user knows what they are doing or start tomorrow)
                    if (schedule.isBefore(LocalDateTime.now())) {
                        schedule = schedule.plusDays(1); // crude fix, or just let it schedule for next occurrence
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
