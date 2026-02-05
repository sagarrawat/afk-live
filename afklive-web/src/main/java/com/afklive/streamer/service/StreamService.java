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
    @Autowired
    private com.afklive.streamer.repository.StreamDestinationRepository streamDestinationRepo;
    @Autowired
    private PlanService planService;

    private final java.util.concurrent.ScheduledExecutorService scheduledExecutorService = java.util.concurrent.Executors.newScheduledThreadPool(5);
    private final ConcurrentHashMap<Long, java.util.concurrent.ScheduledFuture<?>> jobTasks = new ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledFuture<?> billingTask;

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

        // Validate Keys (SSRF Protection)
        for (String key : validKeys) {
             if (key.startsWith("rtmp://") || key.startsWith("rtmps://")) {
                  try {
                      java.net.URI uri = new java.net.URI(key);
                      String host = uri.getHost();
                      if (host == null) throw new IllegalArgumentException("Invalid Stream URL");

                      // Remove brackets from IPv6 if present
                      if (host.startsWith("[") && host.endsWith("]")) {
                          host = host.substring(1, host.length() - 1);
                      }

                      if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.startsWith("192.168.") || host.startsWith("10.")) {
                           throw new IllegalArgumentException("Streaming to local/private network is not allowed.");
                      }

                      if (host.startsWith("172.")) {
                          String[] parts = host.split("\\.");
                          if (parts.length >= 2) {
                              try {
                                  int second = Integer.parseInt(parts[1]);
                                  if (second >= 16 && second <= 31) {
                                      throw new IllegalArgumentException("Streaming to local/private network is not allowed.");
                                  }
                              } catch (NumberFormatException ignored) {}
                          }
                      }
                  } catch (java.net.URISyntaxException e) {
                      throw new IllegalArgumentException("Invalid Stream URL syntax");
                  }
             }
        }

        // 1. SAFETY CHECK: Check DB to see if this user is already live
        // Also check quota limits
        int activeCount = (int) streamJobRepo.countByUsernameAndIsLiveTrue(username);
        userService.checkStreamQuota(username, activeCount);

        com.afklive.streamer.model.User user = userService.getOrCreateUser(username);
        if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
            if (!userService.checkCreditLimit(username)) {
                throw new IllegalStateException("Credit limit exceeded. Please clear your pending balance.");
            }
        }

        // REMOVED SINGLE STREAM CHECK TO ALLOW MULTIPLE STREAMS
        // if (streamJobRepo.findByUsernameAndIsLiveTrue(username).isPresent()) {
        //    throw new IllegalStateException("You already have an active stream running!");
        // }

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
            if (musicName.contains("..") || musicName.contains("/") || musicName.contains("\\")) {
                throw new IllegalArgumentException("Invalid music filename");
            }

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

        // CHECK FOR OPTIMIZED VERSION
        // Logic: If user wants "original" stream mode, no watermark, no music, AND an optimized version exists,
        // we can use it and potentially copy the stream.
        boolean isOptimized = false;
        if (streamMode.equals("original") && watermarkPath == null && musicPath == null) {
            // Check if current file is already optimized (DB check)
            boolean isCurrentOptimized = false;
            try {
                // Efficient: find by S3 key directly
                Optional<ScheduledVideo> currentVideoOpt = scheduledVideoRepository.findFirstByUsernameAndS3Key(username, videoKey);

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
            List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, List.of(key), musicPath, musicVolume, loopCount, watermarkPath, muteVideoAudio, streamMode, maxHeight, isOptimized, overlayTextPath);

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
                List<com.afklive.streamer.model.StreamDestination> dests = streamDestinationRepo.findByStreamKeyAndUser(key, user);
                if (!dests.isEmpty()) {
                    destName = dests.get(0).getName();
                }
            } catch (Exception e) {
                log.warn("Failed to resolve destination name for key: {}", key);
            }

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
            process.onExit().thenRun(() -> {
                log.warn("Stream Process Exited for job {}", jobId);
                finalizeJob(jobId);
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
            // finalizeJob is called by process.onExit()
            stopped++;
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
                // finalizeJob is called by process.onExit()
                return ApiResponse.success("Stream stopped", null);
            }
        }
        return ApiResponse.error("Stream not found or not active");
    }

    // Package-private for testing
    void finalizeJob(Long jobId) {
        Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);
        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            // Use computeIfPresent-like logic or simply check live status.
            // Since we removed synchronized, we rely on the fact that once setLive(false) is saved, future calls do nothing.
            // But there is a small race window.
            if (job.isLive()) {
                job.setLive(false);
                job.setEndTime(java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")));

                // Calculate Final Cost Increment for Pay As You Go
                com.afklive.streamer.model.User user = userService.getOrCreateUser(job.getUsername());
                if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
                    java.time.ZonedDateTime startCalc = job.getLastBillingTime();
                    if (startCalc == null) startCalc = job.getStartTime();

                    long durationSeconds = java.time.Duration.between(startCalc, job.getEndTime()).getSeconds();
                    if (durationSeconds > 0) {
                        double hours = durationSeconds / 3600.0;
                        double incrementalCost = hours * 1.25;

                        incrementalCost = Math.round(incrementalCost * 10000.0) / 10000.0;

                        job.setAccumulatedCost(job.getAccumulatedCost() + incrementalCost);
                        userService.addUnpaidBalance(job.getUsername(), incrementalCost);
                    }
                    job.setCost(job.getAccumulatedCost());
                }

                streamJobRepo.save(job);

                // Cleanup tasks
                activeStreams.remove(jobId);
                java.util.concurrent.ScheduledFuture<?> task = jobTasks.remove(jobId);
                if (task != null) task.cancel(true);
            }
        }
    }

    // Called on startup to init scheduled billing
    @jakarta.annotation.PostConstruct
    public void initBillingTask() {
        billingTask = scheduledExecutorService.scheduleAtFixedRate(this::processPeriodicBilling, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    private void processPeriodicBilling() {
        // Find all active streams
        // Ideally we filter by Pay As You Go users, but for now we iterate active streams and check user plan
        // Optimization: findAllByIsLiveTrue() then filter in memory or join in DB.
        // Assuming StreamJobRepository has findAllByIsLiveTrue()
        List<StreamJob> activeJobs = streamJobRepo.findAllByIsLiveTrue(); // We might need to add this method to repo if not exists, or filter from getAll
        // Actually we have getActiveStreams(username). We need global active streams.
        // Let's use the activeStreams map which contains IDs of locally running processes

        activeStreams.forEach((jobId, process) -> {
            try {
                Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);
                if (jobOpt.isPresent()) {
                    StreamJob job = jobOpt.get();
                    com.afklive.streamer.model.User user = userService.getOrCreateUser(job.getUsername());

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
                            userService.addUnpaidBalance(job.getUsername(), incrementalCost);
                            streamJobRepo.save(job);
                        }

                        // Check Credit Limit
                        if (!userService.checkCreditLimit(job.getUsername())) {
                            log.warn("User {} exceeded credit limit. Stopping stream {}.", job.getUsername(), jobId);
                            stopStream(jobId, job.getUsername());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error in billing task for job " + jobId, e);
            }
        });
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