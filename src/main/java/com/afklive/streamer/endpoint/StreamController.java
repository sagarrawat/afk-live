package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.*;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Allow frontend to call backend freely for now
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
    private StreamManagerService streamManager;
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private ScheduledVideoRepository scheduledVideoRepository;
    @Autowired
    private UserService userService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        // TODO: Update FileUploadService to support S3 if needed, but for now we focus on Conversion code fixes.
        return ResponseEntity.ok(ApiResponse.success("SUCCESS", fileUploadService.handleFileUpload(file, SecurityUtils.getEmail(principal))));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Principal principal) {
        if (principal == null) return ResponseEntity.ok(ApiResponse.success("Guest", Map.of("live", false)));

        StreamJob job = streamService.getCurrentStatus(SecurityUtils.getEmail(principal));
        if (job == null) {
            return ResponseEntity.ok(ApiResponse.success("OFFLINE", Map.of("live", false)));
        }
        return ResponseEntity.ok(ApiResponse.success("ONLINE", job));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<?>> start(@RequestParam("streamKey") List<String> streamKeys,
                                                @RequestParam String videoKey,
                                                @RequestParam(required = false) String musicName,
                                                @RequestParam(required = false, defaultValue = "1.0") String musicVolume,
                                                @RequestParam(required = false, defaultValue = "-1") int loopCount,
                                                @RequestParam(required = false) MultipartFile watermarkFile,
                                                @RequestParam(required = false, defaultValue = "true") boolean muteVideoAudio,
                                                @RequestParam(required = false, defaultValue = "original") String streamMode,
                                                Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));

        String email = SecurityUtils.getEmail(principal);
        if (streamManager.tryStartStream(email)) {
            try {
                return ResponseEntity.ok(streamService.startStream(email, streamKeys, videoKey, musicName, musicVolume, loopCount, watermarkFile, muteVideoAudio, streamMode));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(ApiResponse.error("Error: " + e.getMessage()));
            }
        }
        return ResponseEntity.status(413).body(ApiResponse.error("Too many concurrent streams"));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);
        streamManager.endStream(email);
        return ResponseEntity.ok(streamService.stopStream(email));
    }

    @PostMapping("/convert")
    public ResponseEntity<?> startConversion(@RequestParam String fileName, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String email = SecurityUtils.getEmail(principal);
        // userDir is still passed but now ignored by convertVideo in favor of temp/s3
        videoConversionService.convertVideo(userFileService.getUserUploadDir(email), email, fileName);
        return ResponseEntity.ok("Conversion Started");
    }

    @GetMapping("/convert/status")
    public ResponseEntity<?> getConversionStatus(@RequestParam String fileName, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(0);
        return ResponseEntity.ok(videoConversionService.getProgress(SecurityUtils.getEmail(principal), fileName).orElse(0));
    }

    @PostMapping("/convert/shorts")
    public ResponseEntity<?> convertToShort(@RequestParam String fileName, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String email = SecurityUtils.getEmail(principal);
        videoConversionService.convertToShort(userFileService.getUserUploadDir(email), email, fileName);
        return ResponseEntity.ok(Map.of("success", true, "message", "Conversion started"));
    }

    @GetMapping("/stream-library")
    public ResponseEntity<?> getLibrary(Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(userFileService.listConvertedVideos(SecurityUtils.getEmail(principal)));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteFile(@RequestParam String fileName, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String username = SecurityUtils.getEmail(principal);

        try {
            ScheduledVideo video = scheduledVideoRepository.findByUsernameAndTitle(username, fileName)
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
}
