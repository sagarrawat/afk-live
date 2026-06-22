package com.afklive.streamer.service;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class StreamEventPublisher {

    @Autowired
    private StreamJobRepository streamJobRepo;

    // Map of jobId -> list of active SSE connections
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 5000)
    public void pushLiveMetrics() {
        if (emitters.isEmpty()) return;

        List<StreamJob> liveJobs = streamJobRepo.findAllById(emitters.keySet());
        for (StreamJob job : liveJobs) {
            long elapsedSeconds = 0;
            if (job.getStartTime() != null) {
                elapsedSeconds = ChronoUnit.SECONDS.between(job.getStartTime(), ZonedDateTime.now(ZoneId.of("UTC")));
            }

            Map<String, Object> data = Map.of(
                    "status", job.getStatus().toString(),
                    "fps", job.getCurrentFps() != null ? job.getCurrentFps() : 0,
                    "bitrateKbps", job.getCurrentBitrateKbps() != null ? job.getCurrentBitrateKbps() : 0,
                    "cost", job.getAccumulatedCost(),
                    "bytes", job.getBytesStreamed() != null ? job.getBytesStreamed() : 0,
                    "elapsedSeconds", elapsedSeconds
            );
            publish(job.getId(), data);
        }
    }

    public void addEmitter(Long jobId, SseEmitter emitter) {
        emitters.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError((e) -> removeEmitter(jobId, emitter));
    }

    private void removeEmitter(Long jobId, SseEmitter emitter) {
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters != null) {
            jobEmitters.remove(emitter);
            if (jobEmitters.isEmpty()) {
                emitters.remove(jobId);
            }
        }
    }

    public void publish(Long jobId, Object eventData) {
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters != null) {
            for (SseEmitter emitter : jobEmitters) {
                try {
                    emitter.send(SseEmitter.event().name("metrics").data(eventData));
                } catch (IOException e) {
                    emitter.complete();
                    removeEmitter(jobId, emitter);
                }
            }
        }
    }
}
