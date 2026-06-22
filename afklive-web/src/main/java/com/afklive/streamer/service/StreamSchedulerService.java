package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledStream;
import com.afklive.streamer.repository.ScheduledStreamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamSchedulerService {

    private final ScheduledStreamRepository repository;
    private final StreamService streamService;

    @Autowired
    private ApplicationContext applicationContext;

    public void processScheduledStreams() {
        log.info("Checking for scheduled live streams...");
        List<ScheduledStream> dueStreams = repository.findByStatusAndScheduledTimeLessThanEqual(
                ScheduledStream.StreamStatus.PENDING, ZonedDateTime.now(ZoneId.of("UTC")));

        StreamSchedulerService selfProxy = applicationContext.getBean(StreamSchedulerService.class);

        for (ScheduledStream stream : dueStreams) {
            try {
                selfProxy.startScheduledStreamInTransaction(stream.getId());
            } catch (Exception e) {
                log.error("Failed to start scheduled stream {}", stream.getId(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startScheduledStreamInTransaction(Long id) {
        ScheduledStream stream = repository.findByIdForUpdate(id).orElse(null);
        if (stream == null || stream.getStatus() != ScheduledStream.StreamStatus.PENDING) {
            log.info("Scheduled stream {} already processed or not found.", id);
            return;
        }

        log.info("Starting scheduled stream ID: {} for user: {}", stream.getId(), stream.getUsername());

        // 1. Start Stream
        try {
            streamService.startStream(
                    stream.getUsername(),
                    stream.getStreamKeys(),
                    stream.getVideoKey(),
                    stream.getMusicName(),
                    stream.getMusicVolume(),
                    stream.getLoopCount(),
                    null, // Watermark file
                    stream.isMuteVideoAudio(),
                    stream.getStreamMode(),
                    stream.getStreamQuality(),
                    "Scheduled Stream: " + stream.getMediaAsset().getTitle(),
                    null, // Description
                    null, // Privacy
                    false, // Overlay enabled
                    null,   // Overlay template
                    false, // Auto Reply
                    false // Powered by
            );

            stream.setStatus(ScheduledStream.StreamStatus.COMPLETED); // Marked as "Started" basically
            log.info("Scheduled stream started successfully: {}", stream.getId());

        } catch (Exception e) {
            log.error("Failed to start scheduled stream {}", stream.getId(), e);
            stream.setStatus(ScheduledStream.StreamStatus.FAILED);
            stream.setErrorMessage(e.getMessage());
        } finally {
            repository.save(stream);
        }
    }
}
