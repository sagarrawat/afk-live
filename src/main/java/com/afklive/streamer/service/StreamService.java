package com.afklive.streamer.service;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.dto.StreamResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.ScheduledVideoRepository;
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

    // Store active streams: Job ID -> Process
    private final ConcurrentHashMap<Long, Process> activeStreams = new ConcurrentHashMap<>();
    @Autowired
    private UserFileService userFileService;
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private UserService userService;
    @Autowired
    private AudioService audioService;
    @Autowired
    private ScheduledVideoRepository scheduledVideoRepository;
    @Autowired
    private YouTubeService youTubeService;

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
            int streamQuality,
            String title,
            String description,
            String privacy
    ) throws IOException {
        
        log.info("username [{}]", username);

        if (streamKeys == null) {
             throw new IllegalArgumentException("At least one destination is required.");
        }

        // Filter out empty keys
        List<String> validKeys = streamKeys.stream()
                .filter(k -> k != null && !k.trim().isEmpty())
                .collect(Collectors.toList());

        if (validKeys.isEmpty()) {
             throw new IllegalArgumentException("At least one valid destination stream key is required.");
        }

        // 1. SAFETY CHECK: Check DB to see if this user is already live
        // Also check quota limits
        int activeCount = (int) streamJobRepo.countByUsernameAndIsLiveTrue(username);
        userService.checkStreamQuota(username, activeCount);

        // REMOVED SINGLE STREAM CHECK TO ALLOW MULTIPLE STREAMS
        // if (streamJobRepo.findByUsernameAndIsLiveTrue(username).isPresent()) {
        //    throw new IllegalStateException("You already have an active stream running!");
        // }

        // 0. UPDATE METADATA (Optional)
        // If title/desc provided, try to update YouTube broadcast
        if (title != null || description != null || privacy != null) {
            try {
                youTubeService.updateActiveBroadcast(username, title, description, privacy);
            } catch (Exception e) {
                log.error("Failed to update broadcast metadata", e);
                // Continue streaming anyway, non-fatal
            }
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
                // Resolve user audio file from DB/S3
                String audioKey = musicName;
                Optional<ScheduledVideo> audioFileOpt = scheduledVideoRepository.findByUsernameAndTitle(username, musicName);
                if (audioFileOpt.isPresent()) {
                    audioKey = audioFileOpt.get().getS3Key();
                }

                Path localAudioPath = userDir.resolve("audio_" + audioKey).toAbsolutePath();
                if (!Files.exists(localAudioPath)) {
                    log.info("Downloading audio from Storage: {}", audioKey);
                    try {
                        storageService.downloadFileToPath(audioKey, localAudioPath);
                    } catch (Exception e) {
                        log.error("Failed to download audio from Storage", e);
                        // Fallback: check if it exists with original name
                        localAudioPath = userDir.resolve(musicName).toAbsolutePath();
                    }
                }

                if (Files.exists(localAudioPath)) {
                    musicPath = localAudioPath;
                } else {
                    // Final fallback
                    musicPath = userDir.resolve(musicName).toAbsolutePath();
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
        int planMax = userService.getOrCreateUser(username).getPlanType().getMaxResolution();
        int maxHeight = (streamQuality > 0 && streamQuality < planMax) ? streamQuality : planMax;

        // CHECK FOR OPTIMIZED VERSION
        // Logic: If user wants "original" stream mode, no watermark, no music, AND an optimized version exists,
        // we can use it and potentially copy the stream.
        boolean isOptimized = false;
        if (streamMode.equals("original") && watermarkPath == null && musicPath == null) {
            // Check if current file is already optimized (DB check)
            boolean isCurrentOptimized = false;
            try {
                // Inefficient but safe: find by S3 key
                Optional<ScheduledVideo> currentVideoOpt = scheduledVideoRepository.findByUsername(username).stream()
                        .filter(v -> v.getS3Key() != null && v.getS3Key().equals(videoKey))
                        .findFirst();

                if (currentVideoOpt.isPresent()) {
                    ScheduledVideo v = currentVideoOpt.get();
                    if (v.getOptimizationStatus() == ScheduledVideo.OptimizationStatus.COMPLETED) {
                         isCurrentOptimized = true;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to check optimization status from DB", e);
            }

            // Fallback: Check filename pattern if DB check failed or not found
            if (!isCurrentOptimized) {
                String fName = videoPath.getFileName().toString();
                if (fName.contains("_portrait_") || fName.contains("_landscape_") || fName.contains("_optimized")) {
                    isCurrentOptimized = true;
                }
            }

            if (isCurrentOptimized) {
                 log.info("Video detected as pre-optimized. Enabling Stream Copy mode.");
                 isOptimized = true;
            } else {
                // Try to find a sibling optimized file (Legacy behavior)
                String originalFileName = videoPath.getFileName().toString();
                String baseName = originalFileName.toLowerCase().endsWith(".mp4") ? originalFileName.substring(0, originalFileName.length() - 4) : originalFileName;
                Path optimizedPath = videoPath.resolveSibling(baseName + "_optimized.mp4");

                if (Files.exists(optimizedPath)) {
                    log.info("Found sibling optimized video version: {}", optimizedPath);
                    videoPath = optimizedPath;
                    isOptimized = true;
                }
            }
        }

        List<Long> startedJobIds = new ArrayList<>();
        clearLogs();

        // Loop through keys to start individual processes
        for (String key : validKeys) {
            List<String> command =
                    FFmpegCommandBuilder.buildStreamCommand(videoPath, List.of(key), musicPath, musicVolume, loopCount, watermarkPath, muteVideoAudio, streamMode, maxHeight, isOptimized);

            log.info("Starting stream for key [{}]: command [{}]", key, String.join(" ", command));

            // 4. Start the Process
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);

            Process process = builder.start();

            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (process.isAlive() && (line = reader.readLine()) != null) {
                        addLog("[" + process.pid() + "] " + line);
                    }
                } catch (IOException e) {
                    addLog("Log Capture Error: " + e.getMessage());
                    log.error("Error", e);
                }
            });

            // 5. SAVE STATE TO DATABASE
            StreamJob job = new StreamJob(
                    username,
                    key,
                    videoKey,
                    musicName,
                    musicVolume,
                    true,
                    process.pid(),
                    title,
                    description,
                    privacy,
                    java.time.LocalDateTime.now()
            );
            job = streamJobRepo.save(job);
            final Long jobId = job.getId();
            startedJobIds.add(jobId);

            activeStreams.put(jobId, process);

            // 6. EXIT HANDLER (Auto-Update DB)
            process.onExit().thenRun(() -> {
                log.warn("Stream Process Exited for job {}", jobId);
                Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);
                if (jobOpt.isPresent()) {
                    StreamJob existingJob = jobOpt.get();
                    if (existingJob.isLive()) {
                        existingJob.setLive(false);
                        streamJobRepo.save(existingJob);
                    }
                }
                activeStreams.remove(jobId);
            });
        }

        return ApiResponse.success("Stream started", new StreamResponse(
                startedJobIds.toString(),
                streamKeys.toString(),
                "RUNNING",
                "Started " + startedJobIds.size() + " separate stream processes."
        ));
    }

    public ApiResponse<?> stopAllStreams(String username) {
        List<StreamJob> jobs = streamJobRepo.findAllByUsernameAndIsLiveTrue(username);

        if (jobs.isEmpty()) {
            return ApiResponse.success("No active streams found", null);
        }

        int stopped = 0;
        for (StreamJob job : jobs) {
            ProcessHandle.of(job.getPid()).ifPresent(ProcessHandle::destroyForcibly);
            job.setLive(false);
            streamJobRepo.save(job);
            stopped++;
        }

        // Clean up memory map
        for (StreamJob job : jobs) {
            activeStreams.remove(job.getId());
        }

        return ApiResponse.success(stopped + " streams stopped", null);
    }

    public ApiResponse<?> stopStream(Long jobId, String username) {
        Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);

        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            if (!job.getUsername().equals(username)) {
                return ApiResponse.error("Unauthorized");
            }

            if (job.isLive()) {
                ProcessHandle.of(job.getPid()).ifPresent(ProcessHandle::destroyForcibly);
                job.setLive(false);
                streamJobRepo.save(job);
                return ApiResponse.success("Stream stopped", null);
            }
        }
        return ApiResponse.error("Stream not found or not active");
    }

    public List<StreamJob> getActiveStreams(String username) {
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