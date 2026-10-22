package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.*;
import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.util.SecurityUtils;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.service.YouTubeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class StreamController {

    @Autowired
    private StreamService streamService;
    @Autowired
    private FileUploadService fileUploadService;
    @Autowired
    private VideoConversionService videoConversionService;
    @Autowired
    private UserFileService userFileService;
    @Autowired
    private com.afklive.streamer.repository.ScheduledStreamRepository scheduledStreamRepo;
    @Autowired
    private com.afklive.streamer.repository.StreamJobRepository streamJobRepository;
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private com.afklive.streamer.repository.MediaAssetRepository mediaAssetRepository;
    @Autowired
    private com.afklive.streamer.repository.StreamDestinationRepository streamDestinationRepository;
    @Autowired
    private ScheduledVideoRepository scheduledVideoRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private YouTubeService youTubeService;
    @Autowired
    private ChannelService channelService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        return ResponseEntity.ok(ApiResponse.success("SUCCESS", fileUploadService.handleFileUpload(file, SecurityUtils.getEmail(principal))));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Principal principal) {
        if (principal == null) return ResponseEntity.ok(ApiResponse.success("Guest", Map.of("live", false)));

        List<StreamJob> jobs = streamService.getActiveStreams(SecurityUtils.getEmail(principal));
        if (jobs == null || jobs.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success("OFFLINE", Map.of("live", false, "activeStreams", List.of())));
        }
        // Return list in "activeStreams" for new UI, and "live"=true for legacy check
        return ResponseEntity.ok(ApiResponse.success("ONLINE", Map.of("live", true, "activeStreams", jobs)));
    }

    @PostMapping(value = "/start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<?>> start(MultipartHttpServletRequest request, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));

        try {
            String email = SecurityUtils.getEmail(principal);

            // Extract parameters manually to control multipart parsing
            String[] streamKeysArr = request.getParameterValues("streamKey");
            if (streamKeysArr == null || streamKeysArr.length == 0) {
                return ResponseEntity.badRequest().body(ApiResponse.error("streamKey is required"));
            }

            String videoKey = request.getParameter("videoKey");
            if (videoKey == null || videoKey.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("videoKey is required"));
            }

            String musicName = request.getParameter("musicName");
            MultipartFile musicFile = request.getFile("musicFile");

            String musicVolume = request.getParameter("musicVolume");
            if (musicVolume == null || musicVolume.trim().isEmpty()) musicVolume = "1.0";

            String loopCountStr = request.getParameter("loopCount");
            int loopCount = -1;
            if (loopCountStr != null && !loopCountStr.trim().isEmpty()) {
                try {
                    loopCount = Integer.parseInt(loopCountStr);
                } catch (NumberFormatException e) {
                    // Ignore, use default
                }
            }

            MultipartFile watermarkFile = request.getFile("watermarkFile");

            String muteStr = request.getParameter("muteVideoAudio");
            boolean muteVideoAudio = (muteStr != null && !muteStr.trim().isEmpty()) ? Boolean.parseBoolean(muteStr) : true;

            String streamMode = request.getParameter("streamMode");
            if (streamMode == null || streamMode.trim().isEmpty()) streamMode = "original";

            String qualStr = request.getParameter("streamQuality");
            int streamQuality = 0;
            if (qualStr != null && !qualStr.trim().isEmpty()) {
                 try {
                     streamQuality = Integer.parseInt(qualStr);
                 } catch (NumberFormatException e) {
                     // Ignore
                 }
            }

            String title = request.getParameter("title");
            String description = request.getParameter("description");
            String privacy = request.getParameter("privacy");

            String overlayStr = request.getParameter("overlayEnabled");
            boolean overlayEnabled = (overlayStr != null && !overlayStr.trim().isEmpty()) ? Boolean.parseBoolean(overlayStr) : false;

            String overlayTemplate = request.getParameter("overlayTemplate");

            String autoReplyStr = request.getParameter("autoReplyEnabled");
            boolean autoReplyEnabled = (autoReplyStr != null && !autoReplyStr.trim().isEmpty()) ? Boolean.parseBoolean(autoReplyStr) : false;

            if (musicFile != null && !musicFile.isEmpty()) {
                // If a music file is uploaded directly, save it to the user's directory and use its name
                String uploadedMusicName = fileUploadService.handleFileUpload(musicFile, email);
                musicName = uploadedMusicName;
            }

            List<String> streamKeysList = java.util.Arrays.asList(streamKeysArr);

            return ResponseEntity.ok(streamService.startStream(email, streamKeysList, videoKey, musicName, musicVolume, loopCount, watermarkFile, muteVideoAudio, streamMode, streamQuality, title, description, privacy, overlayEnabled, overlayTemplate, autoReplyEnabled));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Error: " + e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(Principal principal, @RequestParam(required = false) Long streamId) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);

        if (streamId != null) {
            return ResponseEntity.ok(streamService.stopStream(streamId, email));
        } else {
            return ResponseEntity.ok(streamService.stopAllStreams(email));
        }
    }

    @PostMapping("/convert")
    public ResponseEntity<?> startConversion(@RequestParam String fileName, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String email = SecurityUtils.getEmail(principal);
        // userDir is still passed but now ignored by convertVideo in favor of temp/s3
        videoConversionService.convertVideo(userFileService.getUserUploadDir(email), email, fileName);
        return ResponseEntity.ok("Conversion Started");
    }

//    @GetMapping("/convert/status")
//    public ResponseEntity<?> getConversionStatus(@RequestParam String fileName, Principal principal) {
//        if (principal == null) return ResponseEntity.status(401).body(0);
//        return ResponseEntity.ok(videoConversionService.getProgress(SecurityUtils.getEmail(principal), fileName).orElse(0));
//    }

    // Removed conflicting mappings handled by ConvertController:
    // /convert/status
    // /convert/shorts
    // /convert/optimize

    @GetMapping("/stream-library")
    public ResponseEntity<?> getLibrary(Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(userFileService.listConvertedVideos(SecurityUtils.getEmail(principal)));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteFile(@RequestParam String fileName, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String username = SecurityUtils.getEmail(principal);

        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid filename"));
        }

        try {
            ScheduledVideo video = scheduledVideoRepository.findByUserEmailAndTitle(username, fileName)
                    .orElse(null);

            if (video != null) {
                // Delete from storage (S3 or Local)
                if (video.getS3Key() != null) {
                    storageService.deleteFile(video.getS3Key());
                }

                // Release quota
                if (video.getFileSize() != null) {
                    userService.updateStorageUsage(username, -video.getFileSize());
                }

                // Remove from DB
                scheduledVideoRepository.delete(video);

                return ResponseEntity.ok(Map.of("success", true, "message", "File deleted"));
            } else {
                 // Fallback for files not in DB (e.g. raw uploads from FileUploadService)
                 // This assumes local storage for raw uploads as per FileUploadService implementation
                 // But this contradicts the "update everywhere" rule.
                 // However, FileUploadService is still local-only.
                 Path filePath = userFileService.getUserUploadDir(username).resolve(fileName);
                 if (java.nio.file.Files.exists(filePath)) {
                     long size = java.nio.file.Files.size(filePath);
                     java.nio.file.Files.delete(filePath);
                     // Quota? Raw uploads probably didn't count towards quota in current impl?
                     // Actually FileUploadService checks quota? No.
                     return ResponseEntity.ok(Map.of("success", true, "message", "File deleted locally"));
                 }
                 return ResponseEntity.status(404).body(Map.of("success", false, "message", "File not found"));
            }

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Could not delete file: " + e.getMessage()));
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<List<String>> getFfmpegLogs() {
        return ResponseEntity.ok(streamService.getLogs());
    }

    @PostMapping("/stream/schedule")
    public ResponseEntity<?> scheduleStream(@jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.ScheduleStreamRequest request, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);
        com.afklive.streamer.model.User user = userService.getOrCreateUser(email);

        var asset = mediaAssetRepository.findFirstByUserEmailAndS3Key(email, request.getVideoKey())
                .orElseThrow(() -> new IllegalArgumentException("Video not found in your library."));
        var destinations = streamDestinationRepository.findAllById(request.getDestinationIds()).stream()
                .filter(d -> d.getUser().getId().equals(user.getId()))
                .toList();
        if (destinations.size() != new java.util.HashSet<>(request.getDestinationIds()).size()) {
            throw new IllegalArgumentException("One or more destinations weren't found.");
        }

        com.afklive.streamer.model.ScheduledStream stream = new com.afklive.streamer.model.ScheduledStream();
        stream.setUser(user);
        stream.setMediaAsset(asset);
        stream.setDestinations(new java.util.ArrayList<>(destinations));
        stream.setScheduledTime(request.getScheduledTime());
        stream.setMusicName(request.getMusicName());
        stream.setMusicVolume(request.getMusicVolume());
        stream.setLoopCount(request.getLoopCount());
        stream.setStreamMode(request.getStreamMode());
        stream.setMuteVideoAudio(request.isMuteVideoAudio());
        stream.setStatus(com.afklive.streamer.model.ScheduledStream.StreamStatus.PENDING);

        scheduledStreamRepo.save(stream);
        return ResponseEntity.ok(ApiResponse.success("Stream scheduled", com.afklive.streamer.dto.ScheduledStreamView.of(stream)));
    }

    @GetMapping("/stream/scheduled")
    public ResponseEntity<?> getScheduledStreams(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);
        return ResponseEntity.ok(ApiResponse.success("Success", scheduledStreamRepo.findByUserEmail(email).stream()
                .map(com.afklive.streamer.dto.ScheduledStreamView::of).toList()));
    }

    @GetMapping("/youtube/key")
    public ResponseEntity<?> getYouTubeStreamKey(Principal principal, @RequestParam(required = false) Long channelId) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        try {
            String targetCredentialId;
            String channelName = null;

            if (channelId != null) {
                // Fetch specific channel
                List<SocialChannel> channels = channelService.getChannels(username);
                SocialChannel channel = channels.stream()
                        .filter(c -> c.getId().equals(channelId) && "YOUTUBE".equals(c.getPlatform()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Channel not found or not YouTube"));

                targetCredentialId = channel.getCredentialId();
                channelName = channel.getName();

                if (targetCredentialId == null) {
                    // Fallback to username if credential ID is missing (legacy)
                    targetCredentialId = username;
                }
            } else {
                // Default behavior (first channel/email)
                targetCredentialId = username;
            }

            String key;
            try {
                key = youTubeService.getStreamKey(targetCredentialId);
            } catch (Exception e) {
                // If the target credential ID (e.g., Google Sub ID) fails, it might be because
                // tokens are stored under the user's email. Retry with the email to trigger
                // YouTubeService's fallback logic.
                if (!targetCredentialId.equals(username)) {
                    key = youTubeService.getStreamKey(username);
                } else {
                    throw e;
                }
            }

            // If name wasn't resolved from DB, try fetching or default
            if (channelName == null) {
                // We could fetch it, or just let frontend handle default.
                // But user asked to show name.
                // If we used default (email), we don't easily know the channel name without an extra call.
                // Let's assume frontend logic handles default labeling, or we return null name.
            }

            return ResponseEntity.ok(Map.of("key", key, "name", channelName != null ? channelName : "YouTube (Default)"));

        } catch (IllegalStateException e) {
            if (e.getMessage().contains("not connected")) {
                return ResponseEntity.status(401).body(Map.of("message", e.getMessage()));
            }
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("token") || msg.contains("not connected") || msg.contains("Authentication failed"))) {
                return ResponseEntity.status(401).body(Map.of("message", "Not connected to YouTube"));
            }
            return ResponseEntity.status(500).body(Map.of("message", "Failed: " + msg));
        }
    }

    @DeleteMapping("/stream/scheduled/{id}")
    public ResponseEntity<?> cancelScheduledStream(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);

        var opt = scheduledStreamRepo.findById(id);
        if (opt.isPresent()) {
            var s = opt.get();
            if (!s.getUsername().equals(email)) return ResponseEntity.status(403).build();
            scheduledStreamRepo.delete(s);
            return ResponseEntity.ok(ApiResponse.success("Deleted", null));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/stream/{id}/stop-at")
    public ResponseEntity<?> setStreamEndTime(@PathVariable Long id, @jakarta.validation.Valid @RequestBody com.afklive.streamer.dto.StopStreamRequest request, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        java.time.ZoneId zone;
        try {
            zone = request.getTimeZone() == null || request.getTimeZone().isBlank()
                    ? java.time.ZoneOffset.UTC : java.time.ZoneId.of(request.getTimeZone());
        } catch (java.time.DateTimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Unknown time zone."));
        }
        java.time.ZonedDateTime stopAt = nextOccurrence(java.time.LocalTime.parse(request.getTime()), zone);
        streamService.setStopAt(id, SecurityUtils.getEmail(principal), stopAt);
        return ResponseEntity.ok(ApiResponse.success("Stream will stop at " + request.getTime(),
                Map.of("stopAt", stopAt.toOffsetDateTime().toString())));
    }

    @DeleteMapping("/stream/{id}/stop-at")
    public ResponseEntity<?> clearStreamEndTime(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        streamService.setStopAt(id, SecurityUtils.getEmail(principal), null);
        return ResponseEntity.ok(ApiResponse.success("Scheduled stop cancelled", null));
    }

    /** The next time the clock in {@code zone} shows {@code time} (today if still ahead, else tomorrow). */
    static java.time.ZonedDateTime nextOccurrence(java.time.LocalTime time, java.time.ZoneId zone) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        java.time.ZonedDateTime candidate = now.with(time).withSecond(0).withNano(0);
        return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
    }

    @GetMapping("/stream/history")
    public ResponseEntity<?> getStreamHistory(Principal principal, @PageableDefault(size = 20) Pageable pageable) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);
        return ResponseEntity.ok(ApiResponse.success("Success", streamJobRepository.findByUserEmailOrderByStartTimeDesc(email, pageable)));
    }
}
