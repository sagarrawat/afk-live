package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledStream;
import com.afklive.streamer.repository.ScheduledStreamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamSchedulerService {

    private final ScheduledStreamRepository repository;
    private final StreamService streamService;
    private final StreamManagerService streamManager;

    // Run via JobRunr
    public void processScheduledStreams() {
        log.info("Checking for scheduled live streams...");
        List<ScheduledStream> dueStreams = repository.findByStatusAndScheduledTimeLessThanEqual(
                ScheduledStream.StreamStatus.PENDING, ZonedDateTime.now(ZoneId.of("UTC")));

        for (ScheduledStream stream : dueStreams) {
            startScheduledStream(stream);
        }
    }

    private void startScheduledStream(ScheduledStream stream) {
        log.info("Starting scheduled stream ID: {} for user: {}", stream.getId(), stream.getUsername());

        // 1. Check concurrent limits
        if (!streamManager.tryStartStream(stream.getUsername())) {
            log.warn("User {} reached max concurrent streams. Marking failed.", stream.getUsername());
            stream.setStatus(ScheduledStream.StreamStatus.FAILED);
            stream.setErrorMessage("Max concurrent streams reached");
            repository.save(stream);
            return;
        }

        // 2. Start Stream
        try {
            // We pass watermarkFile as null for now (not supported in scheduling yet unless we store path)
            // If watermark was needed, we'd need to store the path in DB or S3 key.
            // Assuming simplified scheduling for now.

            streamService.startStream(
                    stream.getUsername(),
                    stream.getStreamKeys(),
                    stream.getVideoKey(),
                    stream.getMusicName(),
                    stream.getMusicVolume(),
                    stream.getLoopCount(),
                    null, // Watermark file (Multipart) not available here.
                    stream.isMuteVideoAudio(),
                    stream.getStreamMode(),
                    0,    // streamQuality (default)
                    null, // Title
                    null, // Description
                    null, // Privacy
                    false, // Overlay enabled
                    null,   // Overlay template
                    false // Auto Reply
            );

            stream.setStatus(ScheduledStream.StreamStatus.COMPLETED); // Marked as "Started" basically
            log.info("Scheduled stream started successfully: {}", stream.getId());

        } catch (Exception e) {
            log.error("Failed to start scheduled stream {}", stream.getId(), e);
            stream.setStatus(ScheduledStream.StreamStatus.FAILED);
            stream.setErrorMessage(e.getMessage());
            // Release lock if failed immediately
            streamManager.endStream(stream.getUsername());
        } finally {
            repository.save(stream);
        }
    }
}
