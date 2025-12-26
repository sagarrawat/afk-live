package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

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
    public ApiResponse<Object> uploadVideo(@RequestParam("file") MultipartFile file, Principal principal) throws IOException {

        return ApiResponse.success("SUCCESS", fileUploadService.handleFileUpload(file, principal.getName()));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Principal principal) {
        // 'Principal' is injected by Spring Security (contains the logged-in username)
        StreamJob job = streamService.getCurrentStatus(principal.getName());
        if (job == null) {
            return ResponseEntity.ok("OFFLINE");
        }
        return ResponseEntity.ok(job);
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<?>> start(@RequestParam String streamKey, @RequestParam String fileName, @RequestParam(required = false) String musicName, @RequestParam(required = false, defaultValue = "1.0") String musicVolume,
                                                             Principal principal) {

        if (streamManager.tryStartStream(principal.getName())) {
            try {
                return ResponseEntity.ok(streamService.startStream(principal.getName(), streamKey, fileName, musicName, musicVolume));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(ApiResponse.error("Error: " + e.getMessage()));
            }
        }
        return ResponseEntity.status(413).body(ApiResponse.error("Too many concurrent streams"));
    }

    @PostMapping("/stop")
    public ApiResponse<?> stop(Principal principal) {
        streamManager.endStream(principal.getName());
        return streamService.stopStream(principal.getName());
    }

    @PostMapping("/convert")
    public ResponseEntity<String> startConversion(@RequestParam String fileName, Principal principal) throws IOException {
        videoConversionService.convertVideo(userFileService.getUserUploadDir(principal.getName()), principal.getName(), fileName);
        return ResponseEntity.ok("Conversion Started");
    }

    @GetMapping("/convert/status")
    public ResponseEntity<Integer> getConversionStatus(@RequestParam String fileName, Principal principal) {
        return ResponseEntity.ok(videoConversionService.getProgress(principal.getName(), fileName).orElse(0));
    }

    @GetMapping("/library")
    public List<String> getLibrary(Principal principal) throws IOException {
        return userFileService.listConvertedVideos(principal.getName());
    }

    @GetMapping("/logs")
    public ResponseEntity<List<String>> getFfmpegLogs() {
        return ResponseEntity.ok(streamService.getLogs());
    }
}