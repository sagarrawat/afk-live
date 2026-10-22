package com.afklive.streamer.streaming;

import com.afklive.streamer.model.ScheduledStream;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.ScheduledStreamRepository;
import com.afklive.streamer.repository.StreamJobDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.YouTubeService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs streams. Every couple of seconds it:
 * <ol>
 *   <li>reports health for the streams it runs (heartbeat) and stops any the web app asked to stop;</li>
 *   <li>resumes its own streams after a restart;</li>
 *   <li>claims new streams, up to its capacity;</li>
 *   <li>takes over streams whose worker stopped reporting (so a lost machine costs under a minute).</li>
 * </ol>
 * Every ownership change is a conditional database update, so two workers never run the same stream.
 * Enabled when {@code app.streaming.role} is "all" (default) or "worker".
 */
@Component
@ConditionalOnExpression("'${app.streaming.role:all}' != 'web'")
@Slf4j
public class StreamWorker {

    private final StreamJobRepository jobs;
    private final StreamJobDestinationRepository destinations;
    private final ScheduledStreamRepository scheduledStreams;
    private final StreamService streamService;
    private final StreamPreparer preparer;
    private final FileStorageService storage;
    private final YouTubeService youTubeService;
    private final String workerId;
    private final int maxStreams;
    private final long tickMillis;
    private final boolean autoStart;
    private final StreamRunner.Settings settings;

    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService overlayUpdater = Executors.newScheduledThreadPool(2);

    public StreamWorker(StreamJobRepository jobs,
                        StreamJobDestinationRepository destinations,
                        ScheduledStreamRepository scheduledStreams,
                        StreamService streamService,
                        StreamPreparer preparer,
                        FileStorageService storage,
                        YouTubeService youTubeService,
                        @Value("${app.streaming.worker-id:}") String workerId,
                        @Value("${app.streaming.worker.max-streams:20}") int maxStreams,
                        @Value("${app.streaming.worker.tick-millis:2000}") long tickMillis,
                        @Value("${app.streaming.worker.auto-start:true}") boolean autoStart) {
        this.jobs = jobs;
        this.destinations = destinations;
        this.scheduledStreams = scheduledStreams;
        this.streamService = streamService;
        this.preparer = preparer;
        this.storage = storage;
        this.youTubeService = youTubeService;
        this.workerId = workerId == null || workerId.isBlank() ? defaultWorkerId() : workerId;
        this.maxStreams = maxStreams;
        this.tickMillis = tickMillis;
        this.autoStart = autoStart;
        this.settings = StreamRunner.Settings.defaults();
    }

    /** Stable across restarts on the same machine, so the worker resumes its own streams. */
    private static String defaultWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }

    public String workerId() {
        return workerId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLoop() {
        if (!autoStart) {
            return;
        }
        log.info("Stream worker {} started (capacity {})", workerId, maxStreams);
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Exception e) {
                log.error("Stream worker tick failed", e);
            }
        }, 0, tickMillis, TimeUnit.MILLISECONDS);
    }

    /** One pass of the loop; public so tests can drive it step by step. */
    public synchronized void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);

        // 1. Streams this worker owns.
        List<StreamJob> mine = jobs.findAllByWorkerIdAndIsLiveTrue(workerId);
        Set<Long> owned = new HashSet<>();
        for (StreamJob job : mine) {
            owned.add(job.getId());
            Running r = running.get(job.getId());
            if (StreamStates.STOP.equals(job.getDesiredState())) {
                String reason = job.getEndReason() != null ? job.getEndReason() : "Stopped by user";
                if (r != null) {
                    r.requestStop(reason);
                } else {
                    streamService.finalizeJob(job.getId(), reason);
                }
            } else if (r == null) {
                start(job); // e.g. this worker restarted: resume its streams
            } else if (jobs.heartbeat(job.getId(), workerId, now, r.status(), r.bitrate, r.fps, r.speed, r.drops, r.lastError) == 0) {
                lose(job.getId(), "Moved to another worker");
            }
        }
        // Streams running here that the database no longer gives us (ended elsewhere, or taken over).
        for (Long id : Set.copyOf(running.keySet())) {
            if (!owned.contains(id)) {
                lose(id, "No longer assigned to this worker");
            }
        }

        // 2. New streams, then 3. streams whose worker disappeared.
        for (Long id : jobs.findUnclaimedIds()) {
            if (running.size() >= maxStreams) {
                break;
            }
            if (jobs.claim(id, workerId, now) == 1) {
                jobs.findById(id).ifPresent(this::start);
            }
        }
        ZonedDateTime cutoff = now.minus(StreamService.HEARTBEAT_TIMEOUT);
        for (Long id : jobs.findOrphanIds(cutoff)) {
            if (running.size() >= maxStreams) {
                break;
            }
            if (jobs.takeOver(id, workerId, now, cutoff) == 1) {
                log.warn("Took over stream {} from a worker that stopped reporting", id);
                jobs.findById(id).ifPresent(this::start);
            }
        }
    }

    private void start(StreamJob job) {
        long id = job.getId();
        StreamSpec spec;
        try {
            spec = StreamSpec.fromJson(job.getSpec());
        } catch (Exception e) {
            streamService.finalizeJob(id, "Stream settings are unreadable", true);
            return;
        }
        Running r = new Running(id, spec);
        running.put(id, r);
        // Downloads can take a while: prepare off the loop thread. Heartbeats keep the job ours meanwhile.
        Thread.ofVirtual().name("prepare-" + id).start(() -> {
            try {
                StreamPreparer.Prepared prepared = preparer.prepare(id, spec);
                r.prepared = prepared;
                if (prepared.overlayFile() != null) {
                    r.overlayTask = overlayUpdater.scheduleAtFixedRate(() -> updateOverlay(spec, prepared), 0, 30, TimeUnit.SECONDS);
                }
                StreamRunner runner = new StreamRunner(id, prepared::encoderCommand, prepared.relayCommands(), r, settings);
                synchronized (r) {
                    if (r.lost) {
                        cleanup(r, spec, false);
                        return;
                    }
                    if (r.pendingStop != null) {
                        // Stopped while its files were downloading: end it without going live.
                        r.onFinished(r.pendingStop, false);
                        return;
                    }
                    r.runner = runner;
                    runner.start();
                }
            } catch (Exception e) {
                log.error("Could not prepare stream {}", id, e);
                running.remove(id);
                streamService.finalizeJob(id, "Could not load the stream's files: " + e.getMessage(), true);
                cleanup(r, spec, false);
            }
        });
    }

    private void updateOverlay(StreamSpec spec, StreamPreparer.Prepared prepared) {
        try {
            String subs = youTubeService.getSubscriberCount(spec.username());
            String text = "GOAL".equalsIgnoreCase(spec.overlayTemplate()) ? "Goal: " + subs + "/10K" : "Subscribers: " + subs;
            Files.writeString(prepared.overlayFile(), text);
        } catch (Exception e) {
            log.debug("Overlay update failed: {}", e.getMessage());
        }
    }

    /** Stop the local copy without ending the job: someone else owns it now. */
    private void lose(Long id, String why) {
        Running r = running.remove(id);
        if (r != null) {
            synchronized (r) {
                r.lost = true;
                if (r.runner != null) {
                    r.runner.stop(why);
                }
            }
            log.warn("Stream {} stopped on this worker: {}", id, why);
        }
    }

    private void cleanup(Running r, StreamSpec spec, boolean ended) {
        if (r.overlayTask != null) {
            r.overlayTask.cancel(true);
        }
        if (r.prepared != null) {
            preparer.cleanup(r.prepared.workDir());
        }
        if (ended && spec.watermarkKey() != null) {
            try {
                storage.deleteFile(spec.watermarkKey());
            } catch (Exception e) {
                log.debug("Watermark cleanup failed", e);
            }
        }
    }

    /**
     * On worker shutdown, stop the processes but leave the jobs assigned here: after a restart the
     * worker resumes them; if it never comes back, another worker takes them over.
     */
    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        overlayUpdater.shutdownNow();
        for (Long id : Set.copyOf(running.keySet())) {
            lose(id, "Worker shutting down");
        }
    }

    public int runningCount() {
        return running.size();
    }

    // ------------------------------------------------------------------ one running stream

    private final class Running implements StreamRunner.Listener {
        final long id;
        final StreamSpec spec;
        volatile StreamRunner runner;
        volatile StreamPreparer.Prepared prepared;
        volatile ScheduledFuture<?> overlayTask;
        volatile boolean lost;
        volatile boolean encoderUp;
        volatile long lastProgressNanos;
        volatile Double bitrate;
        volatile Double fps;
        volatile Double speed;
        volatile Long drops;
        volatile String lastError;

        Running(long id, StreamSpec spec) {
            this.id = id;
            this.spec = spec;
        }

        volatile String pendingStop;

        /** Stop now, or (if still preparing) make sure it never starts. */
        synchronized void requestStop(String reason) {
            if (runner != null) {
                runner.stop(reason);
            } else {
                pendingStop = reason;
            }
        }

        String status() {
            if (!encoderUp) {
                return lastError == null ? StreamStates.STARTING : StreamStates.RECONNECTING;
            }
            boolean fresh = System.nanoTime() - lastProgressNanos < Duration.ofSeconds(15).toNanos();
            return fresh ? StreamStates.LIVE : StreamStates.RECONNECTING;
        }

        @Override
        public void onEncoderStarted(int restarts) {
            encoderUp = true;
        }

        @Override
        public void onProgress(StreamRunner.Progress p) {
            lastProgressNanos = System.nanoTime();
            bitrate = p.bitrateKbps();
            fps = p.fps();
            speed = p.speed();
            drops = p.droppedFrames();
            lastError = null;
        }

        @Override
        public void onDestination(int position, String status, int restarts, String error) {
            try {
                destinations.updateState(id, position, status, restarts, error,
                        ZonedDateTime.now(ZoneId.of("UTC")));
            } catch (Exception e) {
                log.debug("Destination state update failed", e);
            }
            if (error != null) {
                streamService.addLog("[job " + id + " dest " + position + "] " + error);
            }
        }

        @Override
        public void onEncoderFailure(int restarts, String error) {
            encoderUp = false;
            lastError = error;
            streamService.addLog("[job " + id + "] encoder restart " + restarts + ": " + error);
        }

        @Override
        public void onFinished(String reason, boolean failed) {
            running.remove(id, this);
            if (!lost) {
                streamService.finalizeJob(id, reason, failed);
                if (spec.scheduledStreamId() != null) {
                    scheduledStreams.findById(spec.scheduledStreamId()).ifPresent(s -> {
                        s.setStatus(failed ? ScheduledStream.StreamStatus.FAILED : ScheduledStream.StreamStatus.COMPLETED);
                        if (failed) {
                            s.setErrorMessage(reason);
                        }
                        scheduledStreams.save(s);
                    });
                }
            }
            cleanup(this, spec, !lost);
        }
    }
}
