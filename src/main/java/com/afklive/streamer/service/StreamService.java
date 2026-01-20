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
    @Autowired
    private com.afklive.streamer.repository.ScheduledVideoRepository scheduledVideoRepo;

    public final ConcurrentHashMap<String, Integer> conversionProgress = new ConcurrentHashMap<>();
    private final List<String> logBuffer =
            Collections.synchronizedList(new ArrayList<>());

    // Store active streams: JobID -> Process
    private final ConcurrentHashMap<Long, Process> activeStreams = new ConcurrentHashMap<>();
    // UserFileService removed as requested for startStream
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private UserService userService;
    @Autowired
    private AudioService audioService;
    @Autowired
    private StreamManagerService streamManager;

    // We need to pass 'username' now
    public ApiResponse<StreamResponse> startStream(
            String username,
            List<String> streamKeys,
            String videoKey,
            String musicName,
            String musicVolume,
            int loopCount,
            MultipartFile watermarkFile,
            boolean muteVideoAudio,
            String streamMode,
            String jobToken
    ) throws IOException {
        
        log.info("username [{}]", username);

        if (streamKeys == null || streamKeys.isEmpty()) {
             throw new IllegalArgumentException("At least one destination is required.");
        }

        // 1. SAFETY CHECK: Check DB to see if this user is already live
        // Also check quota limits
        int activeCount = (int) streamJobRepo.countByUsernameAndIsLiveTrue(username);
        userService.checkStreamQuota(username, activeCount);

        // Removed single stream check to allow concurrency if quota permits

        // 2. Resolve Paths & Download from S3 (Using DB)
        // Verify Video Existence in DB
        var videoEntity = scheduledVideoRepo.findByS3KeyAndUsername(videoKey, username)
                .orElseThrow(() -> new IllegalArgumentException("Video not found or access denied: " + videoKey));

        // Define Local Cache Directory
        Path cacheDir = java.nio.file.Paths.get("data/stream_cache");
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }

        // Resolve Video Path
        Path videoPath = cacheDir.resolve(videoEntity.getS3Key()).toAbsolutePath();

        if (!Files.exists(videoPath)) {
            log.info("Downloading video from Storage: {}", videoKey);
            storageService.downloadFileToPath(videoKey, videoPath);
        }
        
        log.info("videoPath [{}]", videoPath);

        // 3. Build the FFmpeg Command
        Path musicPath = null;
        if (musicName != null && !musicName.isEmpty()) {
            if (musicName.startsWith("stock:")) {
                String trackId = musicName.substring(6); // remove "stock:"
                musicPath = audioService.getAudioPath(trackId);
            } else {
                // Verify Music in DB
                var musicEntity = scheduledVideoRepo.findByS3KeyAndUsername(musicName, username)
                        .orElseThrow(() -> new IllegalArgumentException("Music file not found or access denied: " + musicName));

                musicPath = cacheDir.resolve(musicEntity.getS3Key()).toAbsolutePath();
                if (!Files.exists(musicPath)) {
                    log.info("Downloading music from Storage: {}", musicName);
                    storageService.downloadFileToPath(musicName, musicPath);
                }
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

        // Get User Plan Limits
        int maxHeight = userService.getOrCreateUser(username).getPlanType().getMaxResolution();
        
        List<String> command =
                FFmpegCommandBuilder.buildStreamCommand(videoPath, streamKeys, musicPath, musicVolume, loopCount, watermarkPath, muteVideoAudio, streamMode, maxHeight);
        
        log.info("command : [{}]", String.join(" ", command));

        // 4. Start the Process
        ProcessBuilder builder = new ProcessBuilder(command);

        // Redirect logs to console so you can debug "Connection Failed" errors
        builder.redirectErrorStream(true);
        // builder.redirectOutput(ProcessBuilder.Redirect.INHERIT); // Removing INHERIT to capture logs in getInputStream

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
        StreamJob savedJob = streamJobRepo.save(job);

        // Store reference in memory map using Job ID
        activeStreams.put(savedJob.getId(), process);

        // 6. EXIT HANDLER (Auto-Update DB)
        process.onExit().thenRun(() -> {
            log.warn("Stream Process Exited for user: {} job: {}", username, savedJob.getId());

            // Mark as Offline in Database
            Optional<StreamJob> jobOpt = streamJobRepo.findById(savedJob.getId());
            if (jobOpt.isPresent()) {
                StreamJob existingJob = jobOpt.get();
                existingJob.setLive(false);
                streamJobRepo.save(existingJob);
            }

            // Clear memory map
            activeStreams.remove(savedJob.getId());

            // Release token
            if (jobToken != null) {
                streamManager.endStream(jobToken);
            }
        });

        return ApiResponse.success("Stream started", new StreamResponse(
                String.valueOf(savedJob.getId()), // Return ID as job ID
                primaryKey,
                "RUNNING",
                "Stream is now live to " + streamKeys.size() + " destinations"
        ));
    }

    public ApiResponse<?> stopStream(String username, Long jobId) {
        // 1. Find active job in DB
        Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);

        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();

            if (!job.getUsername().equals(username)) {
                return ApiResponse.error("Unauthorized to stop this stream");
            }

            // 2. Kill Process
            ProcessHandle.of(job.getPid()).ifPresent(ProcessHandle::destroyForcibly);

            // Also check map
            Process p = activeStreams.remove(jobId);
            if (p != null) p.destroyForcibly();

            // 3. Update DB
            job.setLive(false);
            streamJobRepo.save(job);

            return ApiResponse.success("Stream stopped successfully", null);
        }
        return ApiResponse.success("No active stream found", null);
    }

    public List<StreamJob> getCurrentStatus(String username) {
        return streamJobRepo.findAllByUsernameAndIsLiveTrue(username);
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