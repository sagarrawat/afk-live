package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledStream;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.repository.ScheduledStreamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Starts scheduled streams when their time comes (run every minute by JobRunr). */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamSchedulerService {

    private final ScheduledStreamRepository repository;
    private final StreamService streamService;

    public void processScheduledStreams() {
        List<ScheduledStream> due = repository.findByStatusAndScheduledTimeLessThanEqual(
                ScheduledStream.StreamStatus.PENDING, ZonedDateTime.now(ZoneId.of("UTC")));
        for (ScheduledStream stream : due) {
            if (repository.claim(stream.getId()) == 1) {
                startScheduledStream(stream);
            }
        }
    }

    void startScheduledStream(ScheduledStream stream) {
        log.info("Starting scheduled stream {} for {}", stream.getId(), stream.getUsername());
        try {
            List<String> keys = stream.getDestinations() == null ? List.of()
                    : stream.getDestinations().stream().map(StreamDestination::getStreamKey).toList();
            if (keys.isEmpty()) {
                throw new IllegalStateException("No destinations left for this scheduled stream.");
            }
            if (stream.getVideoKey() == null) {
                throw new IllegalStateException("The video for this scheduled stream was deleted.");
            }
            // Plan limits (concurrent streams, quality, credit) are enforced inside startStream.
            streamService.startStream(
                    stream.getUsername(),
                    keys,
                    stream.getVideoKey(),
                    stream.getMusicName(),
                    stream.getMusicVolume(),
                    stream.getLoopCount(),
                    null,  // watermark upload isn't stored for scheduled streams yet
                    stream.isMuteVideoAudio(),
                    stream.getStreamMode(),
                    0,     // plan's maximum quality
                    null, null, null,
                    false, null, false);
            stream.setStatus(ScheduledStream.StreamStatus.RUNNING);
            stream.setErrorMessage(null);
        } catch (Exception e) {
            log.error("Failed to start scheduled stream {}", stream.getId(), e);
            stream.setStatus(ScheduledStream.StreamStatus.FAILED);
            stream.setErrorMessage(e.getMessage());
        } finally {
            repository.save(stream);
        }
    }
}
