package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.service.*;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private com.afklive.streamer.repository.ScheduledStreamRepository scheduledStreamRepo;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
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

    @PostMapping("/convert/optimize")
    public ResponseEntity<?> optimizeVideo(@RequestParam String fileName, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        String email = SecurityUtils.getEmail(principal);
        videoConversionService.optimizeVideo(userFileService.getUserUploadDir(email), email, fileName);
        return ResponseEntity.ok(Map.of("success", true, "message", "Optimization started"));
    }

    @GetMapping("/stream-library")
    public ResponseEntity<?> getLibrary(Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(userFileService.listConvertedVideos(SecurityUtils.getEmail(principal)));
    }

    @DeleteMapping("/api/delete")
    public ResponseEntity<?> deleteFile(@RequestParam String fileName) {
        try {
            // Assuming files are stored in a "uploads" folder
            Path filePath = Paths.get("uploads").resolve(fileName);
            Files.deleteIfExists(filePath);
            return ResponseEntity.ok(Map.of("success", true, "message", "File deleted"));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Could not delete file"));
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<List<String>> getFfmpegLogs() {
        return ResponseEntity.ok(streamService.getLogs());
    }

    @PostMapping("/stream/schedule")
    public ResponseEntity<?> scheduleStream(@RequestBody com.afklive.streamer.model.ScheduledStream stream, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);

        stream.setUsername(email);
        stream.setStatus(com.afklive.streamer.model.ScheduledStream.StreamStatus.PENDING);

        // Basic validation
        if (stream.getStreamKeys() == null || stream.getStreamKeys().isEmpty()) {
             return ResponseEntity.badRequest().body(ApiResponse.error("At least one destination required"));
        }
        if (stream.getScheduledTime() == null || stream.getScheduledTime().isBefore(java.time.LocalDateTime.now())) {
             return ResponseEntity.badRequest().body(ApiResponse.error("Scheduled time must be in the future"));
        }

        scheduledStreamRepo.save(stream);
        return ResponseEntity.ok(ApiResponse.success("Stream Scheduled", stream));
    }

    @GetMapping("/stream/scheduled")
    public ResponseEntity<?> getScheduledStreams(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String email = SecurityUtils.getEmail(principal);
        return ResponseEntity.ok(ApiResponse.success("Success", scheduledStreamRepo.findByUsername(email)));
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
}