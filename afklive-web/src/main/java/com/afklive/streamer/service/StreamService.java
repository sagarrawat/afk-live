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
    @Autowired
    private AppConfigService appConfigService;

    private final List<String> logBuffer =
            Collections.synchronizedList(new ArrayList<>());

    // Store active streams: Job ID -> Process
    private final ConcurrentHashMap<Long, Process> activeStreams = new ConcurrentHashMap<>();
    /** Why we asked a job's process to stop; absent means it exited on its own. */
    private final ConcurrentHashMap<Long, String> stopReasons = new ConcurrentHashMap<>();
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
    @Autowired
    private com.afklive.streamer.repository.StreamDestinationRepository streamDestinationRepo;
    @Autowired
    private PlanService planService;
    @Autowired
    private com.afklive.streamer.repository.MediaAssetRepository mediaAssetRepository;
    @Autowired
    private MediaProbe mediaProbe;

    private final java.util.concurrent.ScheduledExecutorService scheduledExecutorService = java.util.concurrent.Executors.newScheduledThreadPool(5);
    private final ConcurrentHashMap<Long, java.util.concurrent.ScheduledFuture<?>> jobTasks = new ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledFuture<?> billingTask;

    // We need to pass 'username' (email) now
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
            String privacy,
            boolean overlayEnabled,
            String overlayTemplate,
            boolean autoReplyEnabled
    ) throws IOException {
        
        log.info("username [{}]", username);

        if (streamKeys == null) {
             throw new IllegalArgumentException("At least one destination is required.");
        }

        // Flatten and Filter out empty keys
        List<String> validKeys = streamKeys.stream()
                .filter(k -> k != null && !k.trim().isEmpty())
                .flatMap(k -> java.util.Arrays.stream(k.split(","))) // Split comma-separated keys
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toList());

        if (validKeys.isEmpty()) {
             throw new IllegalArgumentException("At least one valid destination stream key is required.");
        }

        // Validate destinations: bare keys or public rtmp/rtmps/srt URLs only (SSRF protection)
        for (String key : validKeys) {
            com.afklive.streamer.util.NetworkSafety.requireSafeStreamDestination(key);
        }

        // 1. SAFETY CHECK: Check DB to see if this user is already live
        // Also check quota limits
        int activeCount = (int) streamJobRepo.countByUserEmailAndIsLiveTrue(username);
        userService.checkStreamQuota(username, activeCount);

        com.afklive.streamer.model.User user = userService.getOrCreateUser(username);
        if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
            // Check Global Rate Limit
            int globalMaxStreams = appConfigService.getGlobalStreamLimit();

            long currentActive = streamJobRepo.countByIsLiveTrue();
            if (currentActive >= globalMaxStreams) {
                throw new IllegalStateException("Platform capacity reached for Pay As You Go users. Please try again later or upgrade to Essentials.");
            }

            if (!userService.checkCreditLimit(username)) {
                throw new IllegalStateException("Credit limit exceeded. Please clear your pending balance.");
            }
        }

        // Validation
        if (title != null && title.length() > 100) {
            throw new IllegalArgumentException("Title must be 100 characters or less.");
        }
        if (description != null && description.length() > 5000) {
            throw new IllegalArgumentException("Description must be 5000 characters or less.");
        }

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

        // 2. Resolve Paths & Download from storage. The video must be in this user's own library.
        if (videoKey == null || videoKey.isBlank()
                || mediaAssetRepository.findFirstByUserEmailAndS3Key(username, videoKey).isEmpty()) {
            throw new IllegalArgumentException("Video not found in your library.");
        }
        Path userDir = userFileService.getUserUploadDir(username);
        // We use the key as filename prefix to avoid collisions
        Path videoPath = com.afklive.streamer.util.SafePaths.resolveInside(userDir, "stream_" + videoKey);

        if (!java.nio.file.Files.exists(videoPath)) {
            log.info("Downloading video from Storage: {}", videoKey);
            try {
                storageService.downloadFileToPath(videoKey, videoPath);
            } catch (Exception e) {
                log.error("Failed to download video from Storage", e);
                throw new IOException("Video could not be loaded from storage: " + videoKey);
            }
        }
        
        log.info("userDir [{}]", userDir);
        log.info("videoPath [{}]", videoPath);

        // 3. Build the FFmpeg Command
        Path musicPath = null;
        if (musicName != null && !musicName.isEmpty()) {
            if (musicName.contains("..") || musicName.contains("/") || musicName.contains("\\")) {
                throw new IllegalArgumentException("Invalid music filename");
            }

            if (musicName.startsWith("stock:")) {
                String trackId = musicName.substring(6); // remove "stock:"
                musicPath = audioService.getAudioPath(trackId);
            } else {
                // The music must be a file in this user's own library.
                Optional<ScheduledVideo> audioFileOpt = scheduledVideoRepository.findByUserEmailAndTitle(username, musicName);
                if (audioFileOpt.isEmpty() || audioFileOpt.get().getMediaAsset() == null) {
                    throw new IllegalArgumentException("Music file not found in your library.");
                }
                String audioKey = audioFileOpt.get().getMediaAsset().getS3Key();
                Path localAudioPath = com.afklive.streamer.util.SafePaths.resolveInside(userDir, "audio_" + audioKey);
                if (!Files.exists(localAudioPath)) {
                    log.info("Downloading audio from Storage: {}", audioKey);
                    try {
                        storageService.downloadFileToPath(audioKey, localAudioPath);
                    } catch (Exception e) {
                        log.error("Failed to download audio from Storage", e);
                        throw new IOException("Music file could not be loaded from storage.");
                    }
                }
                musicPath = localAudioPath;
            }
        }

        Path watermarkPath = null;
        if (watermarkFile != null && !watermarkFile.isEmpty()) {
             // Create temp file for watermark
             watermarkPath = Files.createTempFile("watermark_", ".png");
             watermarkFile.transferTo(watermarkPath);
        }

        Path overlayTextPath = null;
        if (overlayEnabled) {
            overlayTextPath = userDir.resolve("subs_" + System.currentTimeMillis() + ".txt");
            Files.writeString(overlayTextPath, "Subs: Loading...");
        }

        log.info("musicPath [{}]", musicPath);

        // Get User Plan Limits
        int planMax = planService.getPlanConfig(user.getPlanType()).getMaxResolution();
        int maxHeight = (streamQuality > 0 && streamQuality < planMax) ? streamQuality : planMax;

        // CHECK FOR OPTIMIZED VERSION: a file normalized at upload can be sent without re-encoding
        // (about 200x less CPU), but only if nothing has to be drawn on it and it fits the plan's cap.
        MediaProbe.MediaInfo info = mediaProbe.probe(videoPath);
        boolean isOptimized = false;
        if (streamMode.equals("original") && watermarkPath == null && musicPath == null && overlayTextPath == null) {
            boolean markedOptimized = false;
            try {
                Optional<ScheduledVideo> currentVideoOpt = scheduledVideoRepository.findFirstByUserEmailAndS3Key(username, videoKey);
                markedOptimized = currentVideoOpt.isPresent()
                        && currentVideoOpt.get().getOptimizationStatus() == ScheduledVideo.OptimizationStatus.COMPLETED;
            } catch (Exception e) {
                log.warn("Failed to check optimization status from DB", e);
            }
            boolean fitsPlan = info.height() > 0 && info.height() <= maxHeight;
            if (markedOptimized && "h264".equals(info.videoCodec()) && fitsPlan) {
                log.info("Video is pre-optimized and within the plan cap; using stream copy.");
                isOptimized = true;
            }
        }
        boolean sourceHasAudio = info.hasAudio();

        List<Long> startedJobIds = new ArrayList<>();
        clearLogs();

        // Loop through keys to start individual processes
        for (String key : validKeys) {
            List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, List.of(key), musicPath, musicVolume, loopCount, watermarkPath, muteVideoAudio, streamMode, maxHeight, isOptimized, overlayTextPath, sourceHasAudio);

            log.info("Starting stream for key [{}]: command [{}]", key, String.join(" ", command));

            // 4. Start the Process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

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

            // Resolve Destination Name
            String destName = "Unknown Destination";
            try {
                // Assuming streamDestinationRepo.findByStreamKeyAndUser expects User entity, which we have
                List<com.afklive.streamer.model.StreamDestination> dests = streamDestinationRepo.findByStreamKeyAndUser(key, user);
                if (!dests.isEmpty()) {
                    destName = dests.get(0).getName();
                }
            } catch (Exception e) {
                log.warn("Failed to resolve destination name for key: {}", key);
            }

            // 5. SAVE STATE TO DATABASE
            StreamJob job = new StreamJob(
                    user,
                    key,
                    videoKey,
                    musicName,
                    musicVolume,
                    true,
                    process.pid(),
                    title,
                    description,
                    privacy,
                    java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")),
                    destName,
                    autoReplyEnabled
            );
            job.setLastBillingTime(job.getStartTime());
            job = streamJobRepo.save(job);
            final Long jobId = job.getId();
            startedJobIds.add(jobId);

            activeStreams.put(jobId, process);

            // Start Overlay Updater if enabled
            if (overlayEnabled && overlayTextPath != null) {
                final Path textPath = overlayTextPath;
                java.util.concurrent.ScheduledFuture<?> task = scheduledExecutorService.scheduleAtFixedRate(() -> {
                    try {
                        String subs = youTubeService.getSubscriberCount(username);
                        String content = "Subscribers: " + subs;
                        if ("GOAL".equalsIgnoreCase(overlayTemplate)) {
                            content = "Goal: " + subs + "/10K";
                        }
                        Files.writeString(textPath, content);
                    } catch (Exception e) {
                        log.error("Failed to update overlay text", e);
                    }
                }, 0, 30, java.util.concurrent.TimeUnit.SECONDS);
                jobTasks.put(jobId, task);
            }

            // 6. EXIT HANDLER (Auto-Update DB)
            process.onExit().thenAccept(exited -> {
                String reason = stopReasons.remove(jobId);
                if (reason == null) {
                    reason = "Stream process exited unexpectedly (code " + exited.exitValue() + ")";
                    log.warn("Stream process for job {} exited on its own with code {}", jobId, exited.exitValue());
                }
                finalizeJob(jobId, reason);
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
        List<StreamJob> jobs = streamJobRepo.findAllByUserEmailAndIsLiveTrue(username);
        if (jobs.isEmpty()) {
            return ApiResponse.success("No active streams found", null);
        }
        for (StreamJob job : jobs) {
            stopJob(job, "Stopped by user");
        }
        return ApiResponse.success(jobs.size() + " streams stopped", null);
    }

    public ApiResponse<?> stopStream(Long jobId, String username) {
        return stopStream(jobId, username, "Stopped by user");
    }

    ApiResponse<?> stopStream(Long jobId, String username, String reason) {
        Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);
        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            if (!job.getUser().getEmail().equals(username)) {
                return ApiResponse.error("Unauthorized");
            }
            if (job.isLive()) {
                stopJob(job, reason);
                return ApiResponse.success("Stream stopped", null);
            }
        }
        return ApiResponse.error("Stream not found or not active");
    }

    /**
     * Stops a job's process. Only processes this server started are signalled (never a bare PID,
     * which the OS may have reused for something else). A job with no process here is stale and is
     * closed in the database directly.
     */
    private void stopJob(StreamJob job, String reason) {
        Process process = activeStreams.get(job.getId());
        if (process == null || !process.isAlive()) {
            finalizeJob(job.getId(), reason);
            return;
        }
        stopReasons.put(job.getId(), reason);
        process.destroy(); // SIGTERM: lets FFmpeg close the connection cleanly
        scheduledExecutorService.schedule(() -> {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Sets (or clears, with null) the time a running stream stops automatically. */
    public java.time.ZonedDateTime setStopAt(Long jobId, String username, java.time.ZonedDateTime stopAt) {
        StreamJob job = streamJobRepo.findById(jobId)
                .filter(j -> j.getUser().getEmail().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("Stream not found."));
        if (!job.isLive()) {
            throw new IllegalStateException("This stream isn't live.");
        }
        if (stopAt != null && !stopAt.isAfter(java.time.ZonedDateTime.now())) {
            throw new IllegalArgumentException("Stop time must be in the future.");
        }
        job.setStopAt(stopAt);
        streamJobRepo.save(job);
        return stopAt;
    }

    /** Stops streams whose scheduled stop time has passed. */
    void stopDueStreams() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"));
        for (StreamJob job : streamJobRepo.findAllByIsLiveTrueAndStopAtLessThanEqual(now)) {
            log.info("Scheduled stop reached for job {}", job.getId());
            stopJob(job, "Scheduled stop");
        }
    }

    /**
     * On startup, no stream processes exist yet, so any job still marked live was left over from
     * a previous run (crash or restart). Close those jobs so they stop counting against plan limits,
     * and don't bill the time the server was down.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileStaleJobs() {
        for (StreamJob job : streamJobRepo.findAllByIsLiveTrue()) {
            if (activeStreams.containsKey(job.getId())) {
                continue;
            }
            job.setLive(false);
            java.time.ZonedDateTime lastKnown = job.getLastBillingTime() != null ? job.getLastBillingTime() : job.getStartTime();
            job.setEndTime(lastKnown != null ? lastKnown : java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")));
            job.setEndReason("Server restarted");
            if (job.getCost() == null) {
                job.setCost(job.getAccumulatedCost());
            }
            streamJobRepo.save(job);
            log.warn("Closed stale live job {} left from a previous run", job.getId());
        }
    }

    // Package-private for testing
    void finalizeJob(Long jobId) {
        finalizeJob(jobId, "Ended");
    }

    void finalizeJob(Long jobId, String reason) {
        Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);
        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            if (job.isLive()) {
                job.setLive(false);
                job.setEndTime(java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")));
                job.setEndReason(reason);

                // Final pay-as-you-go charge since the last billing tick
                String email = job.getUser().getEmail();
                com.afklive.streamer.model.User user = userService.getOrCreateUser(email);

                if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
                    java.time.ZonedDateTime startCalc = job.getLastBillingTime();
                    if (startCalc == null) startCalc = job.getStartTime();

                    long durationSeconds = java.time.Duration.between(startCalc, job.getEndTime()).getSeconds();
                    if (durationSeconds > 0) {
                        double hours = durationSeconds / 3600.0;
                        double incrementalCost = hours * 1.25;
                        incrementalCost = Math.round(incrementalCost * 10000.0) / 10000.0;
                        job.setAccumulatedCost(job.getAccumulatedCost() + incrementalCost);
                        userService.addUnpaidBalance(email, incrementalCost);
                    }
                    job.setCost(job.getAccumulatedCost());
                }

                streamJobRepo.save(job);
            }
        }
        // Cleanup tasks
        activeStreams.remove(jobId);
        java.util.concurrent.ScheduledFuture<?> task = jobTasks.remove(jobId);
        if (task != null) task.cancel(true);
    }

    // Called on startup to init scheduled billing
    @jakarta.annotation.PostConstruct
    public void initBillingTask() {
        billingTask = scheduledExecutorService.scheduleAtFixedRate(this::processPeriodicBilling, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                stopDueStreams();
            } catch (Exception e) {
                log.error("Scheduled-stop check failed", e);
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void processPeriodicBilling() {
        // Find all active streams
        // Ideally we filter by Pay As You Go users, but for now we iterate active streams and check user plan
        // Optimization: findAllByIsLiveTrue() then filter in memory or join in DB.
        // Assuming StreamJobRepository has findAllByIsLiveTrue()
        // Actually we have getActiveStreams(username). We need global active streams.
        // Let's use the activeStreams map which contains IDs of locally running processes

        activeStreams.forEach((jobId, process) -> {
            try {
                Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);
                if (jobOpt.isPresent()) {
                    StreamJob job = jobOpt.get();
                    String email = job.getUser().getEmail();
                    com.afklive.streamer.model.User user = userService.getOrCreateUser(email);

                    if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
                        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"));
                        java.time.ZonedDateTime lastCalc = job.getLastBillingTime();
                        if (lastCalc == null) lastCalc = job.getStartTime();

                        long durationSeconds = java.time.Duration.between(lastCalc, now).getSeconds();
                        if (durationSeconds > 0) {
                            double hours = durationSeconds / 3600.0;
                            double incrementalCost = hours * 1.25;
                            incrementalCost = Math.round(incrementalCost * 10000.0) / 10000.0;

                            job.setAccumulatedCost(job.getAccumulatedCost() + incrementalCost);
                            job.setLastBillingTime(now);

                            // Atomic update to user balance
                            userService.addUnpaidBalance(email, incrementalCost);
                            streamJobRepo.save(job);
                        }

                        // Check Credit Limit
                        if (!userService.checkCreditLimit(email)) {
                            log.warn("User {} exceeded credit limit. Stopping stream {}.", email, jobId);
                            stopStream(jobId, email, "Credit limit reached");
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error in billing task for job " + jobId, e);
            }
        });
    }

    public List<StreamJob> getActiveStreams(String username) {
        return streamJobRepo.findAllByUserEmailAndIsLiveTrue(username);
    }

    // SAFETY NET: Kill all streams if the Java Server shuts down
    @PreDestroy
    public void onShutdown() {
        log.info("Application shutdown - Terminating {} active streams", activeStreams.size());
        activeStreams.values().forEach(Process::destroyForcibly);
    }

    public void addLog(String line) {
        if (line != null) {
            // Redact RTMP keys/URLs
            line = line.replaceAll("rtmp://[^\\s]+", "rtmp://[REDACTED]");
        }

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
