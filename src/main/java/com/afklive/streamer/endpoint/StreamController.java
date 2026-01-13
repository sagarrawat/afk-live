package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.service.*;
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

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        return ResponseEntity.ok(ApiResponse.success("SUCCESS", fileUploadService.handleFileUpload(file, principal.getName())));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Principal principal) {
        if (principal == null) return ResponseEntity.ok(ApiResponse.success("Guest", Map.of("live", false)));

        StreamJob job = streamService.getCurrentStatus(principal.getName());
        if (job == null) {
            return ResponseEntity.ok(ApiResponse.success("OFFLINE", Map.of("live", false)));
        }
        return ResponseEntity.ok(ApiResponse.success("ONLINE", job));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<?>> start(@RequestParam String streamKey, @RequestParam String videoKey, @RequestParam(required = false) String musicName, @RequestParam(required = false, defaultValue = "1.0") String musicVolume,
                                                Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));

        if (streamManager.tryStartStream(principal.getName())) {
            try {
                return ResponseEntity.ok(streamService.startStream(principal.getName(), streamKey, videoKey, musicName, musicVolume));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(ApiResponse.error("Error: " + e.getMessage()));
            }
        }
        return ResponseEntity.status(413).body(ApiResponse.error("Too many concurrent streams"));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        streamManager.endStream(principal.getName());
        return ResponseEntity.ok(streamService.stopStream(principal.getName()));
    }

    @PostMapping("/convert")
    public ResponseEntity<?> startConversion(@RequestParam String fileName, Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).body("Unauthorized");
        videoConversionService.convertVideo(userFileService.getUserUploadDir(principal.getName()), principal.getName(), fileName);
        return ResponseEntity.ok("Conversion Started");
    }

    @GetMapping("/convert/status")
    public ResponseEntity<?> getConversionStatus(@RequestParam String fileName, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(0);
        return ResponseEntity.ok(videoConversionService.getProgress(principal.getName(), fileName).orElse(0));
    }

    @GetMapping("/stream-library")
    public ResponseEntity<?> getLibrary(Principal principal) throws IOException {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(userFileService.listConvertedVideos(principal.getName()));
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
}