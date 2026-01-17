package com.afklive.streamer.service;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.dto.StreamResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StreamService {
    @Autowired
    private StreamJobRepository streamJobRepo;

    public final ConcurrentHashMap<String, Integer> conversionProgress = new ConcurrentHashMap<>();
    private final List<String> logBuffer =
            Collections.synchronizedList(new ArrayList<>());

    // Store active streams: StreamKey -> Process
    // (In a real app, use a Database ID or User ID as the key, not the stream key itself)
    private final ConcurrentHashMap<String, Process> activeStreams = new ConcurrentHashMap<>();
    @Autowired
    private UserFileService userFileService;
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private UserService userService;
    @Autowired
    private AudioService audioService;

    // We need to pass 'username' now
    public ApiResponse<StreamResponse> startStream(
            String username,
            List<String> streamKeys,
            String videoKey,
            String musicName,
            String musicVolume,
            int loopCount,
            MultipartFile watermarkFile
    ) throws IOException {
        
        log.info("username [{}]", username);

        if (streamKeys == null || streamKeys.isEmpty()) {
             throw new IllegalArgumentException("At least one destination is required.");
        }

        // 1. SAFETY CHECK: Check DB to see if this user is already live
        // Also check quota limits
        int activeCount = (int) streamJobRepo.countByUsernameAndIsLiveTrue(username);
        userService.checkStreamQuota(username, activeCount);

        if (streamJobRepo.findByUsernameAndIsLiveTrue(username).isPresent()) {
            throw new IllegalStateException("You already have an active stream running!");
        }

        // 2. Resolve Paths & Download from S3
        Path userDir = userFileService.getUserUploadDir(username);
        // We use the key as filename prefix to avoid collisions
        Path videoPath = userDir.resolve("stream_" + videoKey).toAbsolutePath();

        if (!java.nio.file.Files.exists(videoPath)) {
            log.info("Downloading video from Storage: {}", videoKey);
            try {
                storageService.downloadFileToPath(videoKey, videoPath);
            } catch (Exception e) {
                log.error("Failed to download video from Storage", e);
                // Fallback: Check if it's a local file (legacy support)
                videoPath = userDir.resolve(videoKey).toAbsolutePath();
                if (!java.nio.file.Files.exists(videoPath)) {
                    throw new IOException("Video not found in Storage or local storage: " + videoKey);
                }
            }
        }
        
        log.info("userDir [{}]", userDir);
        log.info("videoPath [{}]", videoPath);

        // 3. Build the FFmpeg Command
        Path musicPath = null;
        if (musicName != null && !musicName.isEmpty()) {
            if (musicName.startsWith("stock:")) {
                String trackId = musicName.substring(6); // remove "stock:"
                musicPath = audioService.getAudioPath(trackId);
            } else {
                musicPath = userDir.resolve(musicName).toAbsolutePath();
            }
        }

        Path watermarkPath = null;
        if (watermarkFile != null && !watermarkFile.isEmpty()) {
             // Create temp file for watermark
             watermarkPath = Files.createTempFile("watermark_", ".png");
             watermarkFile.transferTo(watermarkPath);
             // Note: Temp file will linger until OS cleans up, or we can track it to delete on exit.
             // Ideally we should manage lifecycle, but for now this works.
        }

        log.info("musicPath [{}]", musicPath);
        
        List<String> command =
                FFmpegCommandBuilder.buildStreamCommand(videoPath, streamKeys, musicPath, musicVolume, loopCount, watermarkPath);
        
        log.info("command : [{}]", String.join(" ", command));

        // 4. Start the Process
        ProcessBuilder builder = new ProcessBuilder(command);

        // Redirect logs to console so you can debug "Connection Failed" errors
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process process = builder.start();

        clearLogs();

        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (process.isAlive() && (line = reader.readLine()) != null) {
                    addLog(line);
                }
            } catch (IOException e) {
                addLog("Log Capture Error: " + e.getMessage());
                log.error("Error", e);
            }
        });

        // 5. SAVE STATE TO DATABASE
        // We save the 'pid' so we can kill specifically THIS process later
        String primaryKey = streamKeys.getFirst();
        StreamJob job =
                new StreamJob(username, primaryKey, videoKey, musicName, musicVolume, true, process.pid());
        streamJobRepo.save(job);

        // Store reference in memory map as backup (optional, but good for speed)
        activeStreams.put(username, process);

        // 6. EXIT HANDLER (Auto-Update DB)
        process.onExit().thenRun(() -> {
            log.warn("Stream Process Exited for user: {}", username);

            // Mark as Offline in Database
            Optional<StreamJob> jobOpt = streamJobRepo.findByUsernameAndIsLiveTrue(username);
            if (jobOpt.isPresent()) {
                StreamJob existingJob = jobOpt.get();
                existingJob.setLive(false);
                streamJobRepo.save(existingJob);
            }

            // Clear memory map
            activeStreams.remove(username);
        });

        return ApiResponse.success("Stream started", new StreamResponse(
                String.valueOf(process.pid()),
                primaryKey,
                "RUNNING",
                "Stream is now live to " + streamKeys.size() + " destinations"
        ));
    }

    public ApiResponse<?> stopStream(String username) {
        // 1. Find active job in DB
        Optional<StreamJob> jobOpt = streamJobRepo.findByUsernameAndIsLiveTrue(username);

        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            // 2. Kill Process
            ProcessHandle.of(job.getPid()).ifPresent(ProcessHandle::destroyForcibly);

            // 3. Update DB
            job.setLive(false);
            streamJobRepo.save(job);
            return ApiResponse.success("Stream stopped successfully", null);
        }
        return ApiResponse.success("No active stream found", null);
    }

    public StreamJob getCurrentStatus(String username) {
        return streamJobRepo.findByUsernameAndIsLiveTrue(username).orElse(null);
    }

    // SAFETY NET: Kill all streams if the Java Server shuts down
    @PreDestroy
    public void onShutdown() {
        log.info("Application shutdown - Terminating {} active streams", activeStreams.size());
        activeStreams.values().forEach(Process::destroyForcibly);
    }

    public void addLog(String line) {
        if (logBuffer.size() > 50) {
            logBuffer.removeFirst();
        }
        logBuffer.add(line);
    }

    public List<String> getLogs() {
        return new ArrayList<>(logBuffer);
    }

    public void clearLogs() {
        logBuffer.clear();
    }
}