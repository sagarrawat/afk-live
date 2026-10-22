package com.afklive.streamer.service;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.dto.StreamResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StreamStates;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The web side of streaming. It validates a start request, records what should run as a
 * {@link StreamJob} (spec + desired state), and lets a worker ({@code StreamWorker}) run it. Stopping
 * sets the desired state; the worker acts on it. Web and workers share only the database, so
 * deploying the web app never touches running streams.
 */
@Service
@Slf4j
public class StreamService {

    /** A worker that hasn't reported for this long is considered gone; its streams are taken over. */
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(45);

    @Autowired
    private StreamJobRepository streamJobRepo;
    @Autowired
    private AppConfigService appConfigService;
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private UserService userService;
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

    /** "all" (default: web and worker in one process), "web" or "worker". */
    @Value("${app.streaming.role:all}")
    private String role = "all";

    private final List<String> logBuffer = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

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
        return startStream(username, streamKeys, videoKey, musicName, musicVolume, loopCount, watermarkFile,
                muteVideoAudio, streamMode, streamQuality, title, description, privacy, overlayEnabled,
                overlayTemplate, autoReplyEnabled, null);
    }

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
            boolean autoReplyEnabled,
            Long scheduledStreamId
    ) throws IOException {
        if (streamMode == null || streamMode.isBlank()) {
            streamMode = "original";
        }
        if (streamKeys == null) {
            throw new IllegalArgumentException("At least one destination is required.");
        }
        List<String> validKeys = streamKeys.stream()
                .filter(k -> k != null && !k.trim().isEmpty())
                .flatMap(k -> java.util.Arrays.stream(k.split(",")))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (validKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one valid destination stream key is required.");
        }
        // Bare keys or public rtmp/rtmps/srt URLs only (SSRF protection)
        for (String key : validKeys) {
            com.afklive.streamer.util.NetworkSafety.requireSafeStreamDestination(key);
        }

        // Plan limits: one job is one stream, whatever the number of destinations.
        int activeCount = (int) streamJobRepo.countByUserEmailAndIsLiveTrue(username);
        userService.checkStreamQuota(username, activeCount);

        com.afklive.streamer.model.User user = userService.getOrCreateUser(username);
        if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
            int globalMaxStreams = appConfigService.getGlobalStreamLimit();
            if (streamJobRepo.countByIsLiveTrue() >= globalMaxStreams) {
                throw new IllegalStateException("Platform capacity reached for Pay As You Go users. Please try again later or upgrade to Essentials.");
            }
            if (!userService.checkCreditLimit(username)) {
                throw new IllegalStateException("Credit limit exceeded. Please clear your pending balance.");
            }
        }
        if (title != null && title.length() > 100) {
            throw new IllegalArgumentException("Title must be 100 characters or less.");
        }
        if (description != null && description.length() > 5000) {
            throw new IllegalArgumentException("Description must be 5000 characters or less.");
        }

        // The video must be in this user's own library.
        if (videoKey == null || videoKey.isBlank()
                || mediaAssetRepository.findFirstByUserEmailAndS3Key(username, videoKey).isEmpty()) {
            throw new IllegalArgumentException("Video not found in your library.");
        }

        // Music: stock track or a file from the user's own library.
        String musicKey = null;
        if (musicName != null && !musicName.isEmpty()) {
            if (musicName.contains("..") || musicName.contains("/") || musicName.contains("\\")) {
                throw new IllegalArgumentException("Invalid music filename");
            }
            if (musicName.startsWith("stock:")) {
                musicKey = musicName;
            } else {
                Optional<ScheduledVideo> audioFile = scheduledVideoRepository.findByUserEmailAndTitle(username, musicName);
                if (audioFile.isEmpty() || audioFile.get().getMediaAsset() == null) {
                    throw new IllegalArgumentException("Music file not found in your library.");
                }
                musicKey = audioFile.get().getMediaAsset().getS3Key();
            }
        }

        // Optional YouTube metadata update (non-fatal).
        if (title != null || description != null || privacy != null) {
            try {
                youTubeService.updateActiveBroadcast(username, title, description, privacy);
            } catch (Exception e) {
                log.error("Failed to update broadcast metadata", e);
            }
        }

        // The worker may be another machine: the watermark goes to storage, not a temp file.
        String watermarkKey = null;
        if (watermarkFile != null && !watermarkFile.isEmpty()) {
            watermarkKey = storageService.uploadFile(watermarkFile.getInputStream(), "watermark.png", watermarkFile.getSize());
        }

        int planMax = planService.getPlanConfig(user.getPlanType()).getMaxResolution();
        int maxHeight = (streamQuality > 0 && streamQuality < planMax) ? streamQuality : planMax;

        List<StreamSpec.Destination> destinations = new ArrayList<>();
        for (String key : validKeys) {
            destinations.add(new StreamSpec.Destination(destinationName(key, user), key));
        }
        StreamSpec spec = new StreamSpec(username, videoKey, musicKey, musicVolume, loopCount, watermarkKey,
                muteVideoAudio, streamMode, maxHeight, overlayEnabled, overlayTemplate, scheduledStreamId, destinations);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = new StreamJob(user, validKeys.get(0), videoKey, musicName, musicVolume, true, 0,
                title, description, privacy, now,
                destinations.stream().map(StreamSpec.Destination::name).collect(Collectors.joining(", ")),
                autoReplyEnabled);
        job.setLastBillingTime(now);
        job.setStatus(StreamStates.STARTING);
        job.setDesiredState(StreamStates.RUN);
        job.setSpec(spec.toJson());
        for (int i = 0; i < destinations.size(); i++) {
            job.getDestinations().add(new StreamJobDestination(job, i, destinations.get(i).name()));
        }
        job = streamJobRepo.save(job);
        log.info("Stream job {} created for {} ({} destinations)", job.getId(), username, destinations.size());

        return ApiResponse.success("Stream starting", new StreamResponse(
                String.valueOf(job.getId()), String.valueOf(destinations.size()), StreamStates.STARTING,
                "Going live on " + destinations.size() + " destination" + (destinations.size() == 1 ? "" : "s") + "."));
    }

    private String destinationName(String key, com.afklive.streamer.model.User user) {
        try {
            List<com.afklive.streamer.model.StreamDestination> saved = streamDestinationRepo.findByStreamKeyAndUser(key, user);
            if (saved != null && !saved.isEmpty() && saved.get(0).getName() != null) {
                return saved.get(0).getName();
            }
        } catch (Exception e) {
            log.debug("Destination lookup failed", e);
        }
        return key.contains("://") ? java.net.URI.create(key).getHost() : "YouTube";
    }

    // ------------------------------------------------------------------ stopping

    public ApiResponse<?> stopAllStreams(String username) {
        List<StreamJob> jobs = streamJobRepo.findAllByUserEmailAndIsLiveTrue(username);
        if (jobs.isEmpty()) {
            return ApiResponse.success("No active streams found", null);
        }
        for (StreamJob job : jobs) {
            requestStop(job, "Stopped by user");
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
                requestStop(job, reason);
                return ApiResponse.success("Stream stopped", null);
            }
        }
        return ApiResponse.error("Stream not found or not active");
    }

    /**
     * Asks the worker running the job to stop it. If no live worker owns it (not yet claimed, or
     * its worker is gone), the job is ended here, since nothing else will.
     */
    void requestStop(StreamJob job, String reason) {
        streamJobRepo.requestStop(job.getId(), reason);
        ZonedDateTime cutoff = ZonedDateTime.now(ZoneId.of("UTC")).minus(HEARTBEAT_TIMEOUT);
        boolean noLiveWorker = job.getWorkerId() == null || job.getHeartbeatAt() == null || job.getHeartbeatAt().isBefore(cutoff);
        if (noLiveWorker) {
            finalizeJob(job.getId(), reason);
        }
    }

    /** Sets (or clears, with null) the time a running stream stops automatically. */
    public ZonedDateTime setStopAt(Long jobId, String username, ZonedDateTime stopAt) {
        StreamJob job = streamJobRepo.findById(jobId)
                .filter(j -> j.getUser().getEmail().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("Stream not found."));
        if (!job.isLive()) {
            throw new IllegalStateException("This stream isn't live.");
        }
        if (stopAt != null && !stopAt.isAfter(ZonedDateTime.now())) {
            throw new IllegalArgumentException("Stop time must be in the future.");
        }
        job.setStopAt(stopAt);
        streamJobRepo.save(job);
        return stopAt;
    }

    /** Stops streams whose scheduled stop time has passed. */
    void stopDueStreams() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        for (StreamJob job : streamJobRepo.findAllByIsLiveTrueAndStopAtLessThanEqual(now)) {
            if (!StreamStates.STOP.equals(job.getDesiredState())) {
                log.info("Scheduled stop reached for job {}", job.getId());
                requestStop(job, "Scheduled stop");
            }
        }
    }

    // ------------------------------------------------------------------ ending and billing

    void finalizeJob(Long jobId) {
        finalizeJob(jobId, "Ended");
    }

    public void finalizeJob(Long jobId, String reason) {
        finalizeJob(jobId, reason, false);
    }

    /**
     * Ends a job and makes the final pay-as-you-go charge. The atomic live-to-ended update means only
     * one caller (worker exit, stop request, cleanup) ever does this for a job.
     */
    public void finalizeJob(Long jobId, String reason, boolean failed) {
        ZonedDateTime end = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        if (streamJobRepo.markEnded(jobId, end, reason, failed ? StreamStates.FAILED : StreamStates.ENDED) != 1) {
            return;
        }
        streamJobRepo.findById(jobId).ifPresent(job -> {
            String email = job.getUser().getEmail();
            com.afklive.streamer.model.User user = userService.getOrCreateUser(email);
            if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
                ZonedDateTime from = job.getLastBillingTime() != null ? job.getLastBillingTime() : job.getStartTime();
                double increment = from == null ? 0 : charge(from, end);
                job.setAccumulatedCost(job.getAccumulatedCost() + increment);
                job.setCost(job.getAccumulatedCost());
                streamJobRepo.save(job);
                if (increment > 0) {
                    userService.addUnpaidBalance(email, increment);
                }
            }
        });
    }

    /** Pay-as-you-go: hourly rate in rupees. */
    static final double HOURLY_RATE = 1.25;

    static double charge(ZonedDateTime from, ZonedDateTime to) {
        long seconds = Duration.between(from, to).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return Math.round(seconds / 3600.0 * HOURLY_RATE * 10000.0) / 10000.0;
    }

    /** Charges live pay-as-you-go streams every minute; conditional updates make it safe on any number of instances. */
    void processPeriodicBilling() {
        for (StreamJob job : streamJobRepo.findAllByIsLiveTrue()) {
            try {
                String email = job.getUser().getEmail();
                com.afklive.streamer.model.User user = userService.getOrCreateUser(email);
                if (user.getPlanType() != com.afklive.streamer.model.PlanType.FREE || job.getLastBillingTime() == null) {
                    continue;
                }
                ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
                double increment = charge(job.getLastBillingTime(), now);
                if (increment > 0 && streamJobRepo.addBilling(job.getId(), increment, now, job.getLastBillingTime()) == 1) {
                    userService.addUnpaidBalance(email, increment);
                }
                if (!userService.checkCreditLimit(email) && !StreamStates.STOP.equals(job.getDesiredState())) {
                    log.warn("User {} exceeded credit limit. Stopping stream {}.", email, job.getId());
                    requestStop(job, "Credit limit reached");
                }
            } catch (Exception e) {
                log.error("Error in billing task for job " + job.getId(), e);
            }
        }
    }

    @jakarta.annotation.PostConstruct
    public void initBillingTask() {
        if ("worker".equalsIgnoreCase(role)) {
            return; // billing and scheduled stops run on the web side
        }
        scheduler.scheduleAtFixedRate(() -> {
            try {
                processPeriodicBilling();
            } catch (Exception e) {
                log.error("Billing task failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                stopDueStreams();
            } catch (Exception e) {
                log.error("Scheduled-stop check failed", e);
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void onShutdown() {
        // Streams belong to workers and keep running; the web app just stops its own timers.
        scheduler.shutdownNow();
    }

    public List<StreamJob> getActiveStreams(String username) {
        return streamJobRepo.findAllByUserEmailAndIsLiveTrue(username);
    }

    // ------------------------------------------------------------------ admin log view

    public void addLog(String line) {
        if (line != null) {
            line = line.replaceAll("(rtmps?|srt)://[^\\s]+", "$1://[REDACTED]");
        }
        synchronized (logBuffer) {
            if (logBuffer.size() > 200) {
                logBuffer.removeFirst();
            }
            logBuffer.add(line);
        }
    }

    public List<String> getLogs() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public void clearLogs() {
        logBuffer.clear();
    }
}
