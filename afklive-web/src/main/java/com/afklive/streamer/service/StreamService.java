package com.afklive.streamer.service;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.dto.StreamResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobQueue;
import com.afklive.streamer.repository.StreamJobQueueRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StreamService {

    @Autowired
    private StreamJobRepository streamJobRepo;
    
    @Autowired
    private StreamJobQueueRepository streamJobQueueRepo;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private AppConfigService appConfigService;
    
    @Autowired
    private PlanService planService;
    
    @Autowired
    private com.afklive.streamer.repository.StreamDestinationRepository streamDestinationRepo;
    
    @Autowired
    private YouTubeService youTubeService;
    
    @Autowired
    private EmailService emailService;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.afklive.streamer.repository.UserRepository userRepository;

    /** Domain exception for stream enqueue failures — preserves original cause for callers. */
    public static class StreamEnqueueException extends RuntimeException {
        public StreamEnqueueException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Transactional
    public ApiResponse<StreamResponse> startStream(
            String username,
            List<String> streamKeys,
            String videoKey,
            String musicName,
            String musicVolume,
            int loopCount,
            MultipartFile watermarkFile, // currently ignored since we push this to worker
            boolean muteVideoAudio,
            String streamMode,
            int streamQuality,
            String title,
            String description,
            String privacy,
            boolean overlayEnabled,
            String overlayTemplate,
            boolean autoReplyEnabled,
            boolean poweredBy
    ) throws IOException {
        
        log.info("Starting stream request for username [{}]", username);

        if (streamKeys == null) {
             throw new IllegalArgumentException("At least one destination is required.");
        }

        List<String> validKeys = streamKeys.stream()
                .filter(k -> k != null && !k.trim().isEmpty())
                .flatMap(k -> java.util.Arrays.stream(k.split(",")))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toList());

        if (validKeys.isEmpty()) {
             throw new IllegalArgumentException("At least one valid destination stream key is required.");
        }

        com.afklive.streamer.model.User user = userService.getOrCreateUser(username);

        // LOCK the user row to prevent race conditions when checking quotas
        userRepository.findByEmailForUpdate(username);

        // 1. Check quotas and limits AFTER securing the lock
        int activeCount = (int) streamJobRepo.countByUserEmailAndStatusLive(username);
        userService.checkStreamQuota(username, activeCount);

        if (user.getPlanType() == com.afklive.streamer.model.PlanType.FREE) {
            int globalMaxStreams = appConfigService.getGlobalStreamLimit();
            long currentActive = streamJobRepo.countByStatusLive();
            if (currentActive >= globalMaxStreams) {
                throw new IllegalStateException("Platform capacity reached. Please upgrade to Essentials.");
            }
            if (!userService.checkCreditLimit(username)) {
                throw new IllegalStateException("Credit limit exceeded.");
            }
        }

        // Update YouTube metadata if needed
        if (title != null || description != null || privacy != null || poweredBy) {
            try {
                String finalTitle = title;
                String finalDesc = description;
                
                if (poweredBy) {
                    if (finalTitle != null && !finalTitle.contains("Powered by afklive.in")) {
                        finalTitle = finalTitle + " | Powered by afklive.in";
                    } else if (finalTitle == null) {
                        finalTitle = "Live Stream | Powered by afklive.in";
                    }
                    
                    if (finalDesc != null && !finalDesc.contains("afklive.in")) {
                        finalDesc = finalDesc + "\n\nStreamed effortlessly using https://afklive.in";
                    } else if (finalDesc == null) {
                        finalDesc = "Streamed effortlessly using https://afklive.in";
                    }
                }
                
                youTubeService.updateActiveBroadcast(username, finalTitle, finalDesc, privacy);
                // Ensure the database job also records the updated title
                title = finalTitle;
            } catch (Exception e) {
                log.error("Failed to update broadcast metadata", e);
            }
        }

        List<Long> startedJobIds = new ArrayList<>();

        List<String> destNames = new ArrayList<>();
        for (String key : validKeys) {
            String destName = "Unknown Destination";
            try {
                List<com.afklive.streamer.model.StreamDestination> dests = streamDestinationRepo.findByStreamKeyAndUser(key, user);
                if (!dests.isEmpty()) {
                    destName = dests.get(0).getName();
                }
            } catch (Exception e) {
                log.warn("Failed to resolve destination name for key: {}", key);
            }
            destNames.add(destName);
        }

        String combinedKeys = String.join(",", validKeys);
        if (combinedKeys.length() > 255) combinedKeys = combinedKeys.substring(0, 250) + "...";
        
        String combinedDestName = String.join(" + ", destNames);
        if (combinedDestName.length() > 255) combinedDestName = combinedDestName.substring(0, 250) + "...";

        // Create a single StreamJob for all destinations (Simulcast)
        StreamJob job = new StreamJob(
                user,
                combinedKeys,
                videoKey,
                musicName,
                musicVolume,
                title,
                description,
                privacy,
                java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")),
                combinedDestName,
                autoReplyEnabled
        );
        
        // Calculate final locked hourly rate using central billing logic
        Integer planDiscount = planService.getPlanConfig(user.getPlanType()).getDiscountPercentage();
        int discountPercent = planDiscount != null ? planDiscount : 0;
        
        java.math.BigDecimal finalRate = appConfigService.calculateFinalRate(
            user.getCurrency(),
            streamQuality,
            validKeys.size(),
            discountPercent,
            poweredBy
        );
        
        job.setLockedHourlyRate(finalRate);

        // Idempotency key: prevents duplicate jobs if user double-clicks "Go Live".
        String minuteBucket = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"))
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
        // hash all valid keys to keep idempotency key short
        job.setIdempotencyKey(username + ":" + validKeys.hashCode() + ":" + minuteBucket);
        job.setStatus(StreamJob.StreamStatus.PREPARING);
        try {
            job = streamJobRepo.save(job);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Duplicate stream request detected for user {}", username);
            return (ApiResponse<StreamResponse>) (ApiResponse<?>) ApiResponse.error("A stream is already starting for these destinations. Please wait a moment.");
        }
        final Long jobId = job.getId();
        startedJobIds.add(jobId);

        // Assemble StreamSpec JSON
        Map<String, Object> specMap = new HashMap<>();
        specMap.put("source_s3_key", videoKey);
        specMap.put("destinations", validKeys); // ALL keys passed to worker
        specMap.put("stream_mode", streamMode);
        specMap.put("stream_quality", streamQuality);
        specMap.put("loop_count", loopCount);
        specMap.put("music_name", musicName);
        specMap.put("music_volume", musicVolume);
        specMap.put("mute_video_audio", muteVideoAudio);
        specMap.put("overlay_enabled", overlayEnabled);
        specMap.put("overlay_template", overlayTemplate);
        
        try {
            String specJson = objectMapper.writeValueAsString(specMap);
            
            // Save spec on job for watchdog recovery
            job.setStreamSpecJson(specJson);
            streamJobRepo.save(job);
            
            // Save to Queue
            StreamJobQueue queueJob = new StreamJobQueue();
            queueJob.setStreamJob(job);
            queueJob.setStreamSpec(specJson);
            queueJob.setStatus(StreamJobQueue.QueueStatus.PENDING);
            
            streamJobQueueRepo.save(queueJob);
        } catch (Exception e) {
            log.error("Failed to enqueue job for jobId={}", job.getId(), e);
            job.setStatus(StreamJob.StreamStatus.FAILED);
            streamJobRepo.save(job);
            throw new StreamEnqueueException("Failed to enqueue stream job " + job.getId(), e);
        }

        emailService.sendStreamStatusEmail(user.getEmail(), title != null ? title : videoKey, "QUEUED for Processing", "Stream will begin momentarily.");

        return ApiResponse.success("Stream queued successfully", new StreamResponse(
                startedJobIds.toString(),
                streamKeys.toString(),
                "QUEUED",
                "Queued " + startedJobIds.size() + " stream jobs."
        ));
    }

    public ApiResponse<?> stopAllStreams(String username) {
        List<StreamJob> jobs = streamJobRepo.findAllByUserEmailAndStatusLive(username);

        // Indexed query — no more findAll() table scan
        List<StreamJob> preparingJobs = streamJobRepo
                .findAllByUserEmailAndStatus(username, StreamJob.StreamStatus.PREPARING);
        jobs.addAll(preparingJobs);

        if (jobs.isEmpty()) {
            return ApiResponse.success("No active streams found", null);
        }

        int stopped = 0;
        for (StreamJob job : jobs) {
            job.setStatus(StreamJob.StreamStatus.STOP_REQUESTED);
            streamJobRepo.save(job);
            stopped++;
        }

        log.info("Stop requested for {} stream(s) for user {}", stopped, username);
        if (stopped > 0) {
            emailService.sendStreamStatusEmail(username, "Multiple Streams", "STOPPED", "All active streams have been stopped by request.");
        }
        return ApiResponse.success(stopped + " streams requested to stop", null);
    }

    public ApiResponse<?> stopStream(Long jobId, String username) {
        Optional<StreamJob> jobOpt = streamJobRepo.findById(jobId);

        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            if (!job.getUser().getEmail().equals(username)) {
                return ApiResponse.error("Unauthorized");
            }

            if (job.getStatus() == StreamJob.StreamStatus.LIVE || job.getStatus() == StreamJob.StreamStatus.PREPARING) {
                job.setStatus(StreamJob.StreamStatus.STOP_REQUESTED);
                streamJobRepo.save(job);
                emailService.sendStreamStatusEmail(username, job.getTitle(), "STOPPED", "Stream stop requested.");
                return ApiResponse.success("Stream stop requested", null);
            }
        }
        return ApiResponse.error("Stream not found or not active");
    }

    public List<StreamJob> getActiveStreams(String username) {
        List<StreamJob> active = streamJobRepo.findAllByUserEmailAndStatusLive(username);
        // Indexed query — no more findAll() table scan
        List<StreamJob> preparing = streamJobRepo
                .findAllByUserEmailAndStatus(username, StreamJob.StreamStatus.PREPARING);
        active.addAll(preparing);
        return active;
    }
}
