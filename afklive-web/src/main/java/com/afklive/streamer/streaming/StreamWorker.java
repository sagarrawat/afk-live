package com.afklive.streamer.streaming;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.YouTubeService;
import com.afklive.streamer.util.Redact;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs streams. Every couple of seconds it:
 * <ol>
 *   <li>reports health for the streams it runs (a fenced heartbeat) and stops any the web app asked to stop;</li>
 *   <li>on its first pass after starting, resumes streams assigned to its id;</li>
 *   <li>claims new streams, up to its capacity;</li>
 *   <li>takes over other workers' streams that stopped reporting (so a lost machine costs under a minute).</li>
 * </ol>
 * Every ownership change is a conditional database update, and each heartbeat only succeeds if the row
 * still holds the value this worker last wrote, so two processes can never both keep a stream: the
 * second writer makes the first one's next heartbeat fail, and it stops. A worker that can't reach
 * the database for {@link #FENCE_AFTER} stops its streams, since another worker will take them over.
 */
@Component
@ConditionalOnExpression("'${app.streaming.role:all}' != 'web'")
@Slf4j
public class StreamWorker {

    /** Shorter than the takeover timeout, so a cut-off worker stops before anyone takes over. */
    static final Duration FENCE_AFTER = Duration.ofSeconds(30);

    private final StreamJobRepository jobs;
    private final StreamJobDestinationRepository destinations;
    private final StreamService streamService;
    private final StreamPreparer preparer;
    private final FileStorageService storage;
    private final YouTubeService youTubeService;
    private final StudioRuns studioRuns;
    private final com.afklive.streamer.repository.StreamCommandRepository commands;
    private final ScheduledExecutorService commandPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "studio-commands");
        t.setDaemon(true);
        return t;
    });
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
    private final ScheduledExecutorService overlayUpdater = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "overlay-updater");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean firstPassDone;
    private volatile long lastSuccessfulTickNanos = System.nanoTime();

    public StreamWorker(StreamJobRepository jobs,
                        StreamJobDestinationRepository destinations,
                        StreamService streamService,
                        StreamPreparer preparer,
                        FileStorageService storage,
                        YouTubeService youTubeService,
                        StudioRuns studioRuns,
                        com.afklive.streamer.repository.StreamCommandRepository commands,
                        @Value("${app.streaming.worker-id:}") String workerId,
                        @Value("${app.streaming.worker.max-streams:20}") int maxStreams,
                        @Value("${app.streaming.worker.tick-millis:2000}") long tickMillis,
                        @Value("${app.streaming.worker.auto-start:true}") boolean autoStart) {
        this.jobs = jobs;
        this.destinations = destinations;
        this.streamService = streamService;
        this.preparer = preparer;
        this.storage = storage;
        this.youTubeService = youTubeService;
        this.studioRuns = studioRuns;
        this.commands = commands;
        this.workerId = workerId == null || workerId.isBlank() ? defaultWorkerId() : workerId;
        this.maxStreams = maxStreams;
        this.tickMillis = tickMillis;
        this.autoStart = autoStart;
        this.settings = StreamRunner.Settings.defaults();
    }

    /**
     * Without a configured id each process gets a unique one (host plus random suffix), so two
     * processes on one machine never share an id. Set STREAMING_WORKER_ID to a stable value to let a
     * restarted worker resume its own streams immediately instead of waiting for takeover.
     */
    private static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
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
                fenceIfCutOff();
            }
        }, 0, tickMillis, TimeUnit.MILLISECONDS);
        commandPoller.scheduleWithFixedDelay(() -> {
            try {
                pollCommands();
            } catch (Exception e) {
                log.debug("Command poll failed: {}", e.getMessage());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    /** No successful pass for a while (database unreachable): stop everything rather than risk running twice. */
    void fenceIfCutOff() {
        if (System.nanoTime() - lastSuccessfulTickNanos > FENCE_AFTER.toNanos() && !running.isEmpty()) {
            log.error("Worker {} can't reach the database; stopping {} streams so another worker can take over", workerId, running.size());
            for (Long id : Set.copyOf(running.keySet())) {
                lose(id, "Worker lost its database connection");
            }
        }
    }

    /** One pass of the loop; public so tests can drive it step by step. */
    public synchronized void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);

        // 1. Streams assigned to this worker id.
        Set<Long> owned = new HashSet<>();
        for (StreamJob job : jobs.findAllByWorkerIdAndIsLiveTrue(workerId)) {
            long id = job.getId();
            owned.add(id);
            Running r = running.get(id);
            if (r != null && r.finishPending != null) {
                r.tryFinalize(); // a previous attempt to record the end failed: retry
                continue;
            }
            if (StreamStates.STOP.equals(job.getDesiredState())) {
                String reason = job.getEndReason() != null ? job.getEndReason() : "Stopped by user";
                if (r != null) {
                    r.requestStop(reason);
                } else {
                    streamService.finalizeJob(id, reason);
                }
            } else if (r == null) {
                // Only right after startup: these are streams this worker ran before it restarted. Later on,
                // a job assigned to our id that we aren't running belongs to another process with the same id.
                if (!firstPassDone && running.size() < maxStreams && jobs.resumeOwn(id, workerId, now) == 1) {
                    log.info("Resuming stream {} after worker restart", id);
                    start(job, now);
                }
            } else {
                if (!r.markedLive && StreamStates.LIVE.equals(r.status())) {
                    jobs.markFirstLive(id, now);
                    r.markedLive = true;
                }
                if (jobs.heartbeat(id, workerId, r.lastHeartbeat, now, r.status(), r.bitrate, r.fps, r.speed, r.drops, r.lastError) == 1) {
                    r.lastHeartbeat = now;
                    StudioRuns.Handle h = r.studio;
                    if (h != null) {
                        try {
                            if (r.maxAppliedCommand > r.reportedAppliedCommand) {
                                jobs.saveAppliedCommand(id, workerId, r.maxAppliedCommand);
                                r.reportedAppliedCommand = r.maxAppliedCommand;
                            }
                            studioRuns.report(id, workerId, h);
                        } catch (Exception e) {
                            log.debug("Studio report for {} failed: {}", id, e.getMessage());
                        }
                    }
                } else {
                    lose(id, "Moved to another worker");
                }
            }
        }
        // Streams running here that the database no longer gives us (ended elsewhere, or taken over).
        for (Long id : Set.copyOf(running.keySet())) {
            Running r = running.get(id);
            if (!owned.contains(id) && r != null && r.finishPending == null) {
                lose(id, "No longer assigned to this worker");
            }
        }

        // 2. New streams, then 3. other workers' streams that stopped reporting.
        for (Long id : jobs.findUnclaimedIds(workerId, now.minusMinutes(1))) {
            if (running.size() >= maxStreams) {
                break;
            }
            if (!running.containsKey(id) && jobs.claim(id, workerId, now) == 1) {
                jobs.findById(id).ifPresent(j -> start(j, now));
            }
        }
        ZonedDateTime cutoff = now.minus(StreamService.HEARTBEAT_TIMEOUT);
        for (Long id : jobs.findOrphanIds(cutoff, workerId)) {
            if (running.size() >= maxStreams) {
                break;
            }
            if (!running.containsKey(id) && jobs.takeOver(id, workerId, now, cutoff) == 1) {
                log.warn("Took over stream {} from a worker that stopped reporting", id);
                jobs.findById(id).ifPresent(j -> start(j, now));
            }
        }
        firstPassDone = true;
        lastSuccessfulTickNanos = System.nanoTime();
    }

    private void start(StreamJob job, ZonedDateTime heartbeatWritten) {
        long id = job.getId();
        StreamSpec spec;
        try {
            spec = StreamSpec.fromJson(job.getSpec());
        } catch (Exception e) {
            streamService.finalizeJob(id, "Stream settings are unreadable", true);
            return;
        }
        Running r = new Running(id, spec);
        r.lastHeartbeat = heartbeatWritten;
        running.put(id, r);
        if (spec.studio() != null) {
            r.commandsAfter = job.getAppliedCommandId();
            startStudio(job, spec, r);
            return;
        }
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
                        cleanup(r, false);
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
                r.onFinished("Could not load the stream's files: " + Redact.streamKeys(e.getMessage()), true);
            }
        });
    }

    /** A studio stream: playlists, scenes, phone input and overlays, all behind one encoder. */
    private void startStudio(StreamJob job, StreamSpec spec, Running r) {
        long id = job.getId();
        String scene = job.getScene();
        Thread.ofVirtual().name("prepare-" + id).start(() -> {
            try {
                StudioRuns.Handle h = studioRuns.start(id, spec, scene, new com.afklive.streamer.streaming.studio.Studio.Listener() {
                    @Override
                    public void onInputSwitch(boolean live) {
                        streamService.addLog("[job " + id + "] phone input " + (live ? "back: live" : "lost: showing BRB"));
                    }
                });
                r.studio = h;
                StreamRunner runner = new StreamRunner(id, h.studio(), relayCommands(spec.destinations()), r, settings);
                synchronized (r) {
                    if (r.lost) {
                        cleanup(r, false);
                        return;
                    }
                    if (r.pendingStop != null) {
                        r.onFinished(r.pendingStop, false);
                        return;
                    }
                    h.studio().start();
                    r.runner = runner;
                    runner.start();
                }
            } catch (Exception e) {
                log.error("Could not prepare studio stream {}", id, e);
                r.onFinished("Could not load the stream's files: " + Redact.streamKeys(e.getMessage()), true);
            }
        });
    }

    /** Commands are looked at for this long: covers ids committed out of order and clock differences. */
    static final Duration COMMAND_WINDOW = Duration.ofMinutes(5);

    /**
     * Live changes from the web app, picked up every half second so they're on air within 2 s.
     * Ids can become visible out of order (two web instances, long transactions), so instead of a
     * single "last id" each run remembers which recent commands it applied. Lasting changes are also
     * written into the run's spec by the web app, so a takeover rebuilds them without replaying.
     */
    public void pollCommands() {
        List<Running> ready = running.values().stream().filter(x -> x.studio != null && x.finishPending == null).toList();
        if (ready.isEmpty()) {
            return;
        }
        long after = ready.stream().mapToLong(x -> x.commandsAfter).min().orElse(0);
        ZonedDateTime since = ZonedDateTime.now(ZoneId.of("UTC")).minus(COMMAND_WINDOW);
        List<Long> ids = ready.stream().map(x -> x.id).toList();
        for (com.afklive.streamer.model.StreamCommand c : commands.findRecent(ids, after, since)) {
            Running r = running.get(c.getJobId());
            if (r == null || r.studio == null || c.getId() <= r.commandsAfter || !r.appliedCommands.add(c.getId())) {
                continue;
            }
            r.maxAppliedCommand = Math.max(r.maxAppliedCommand, c.getId());
            try {
                if (c.getType() == com.afklive.streamer.model.StreamCommand.Type.TARGETS) {
                    StreamRunner runner = r.runner;
                    if (runner != null) {
                        runner.setRelays(relayCommands(StreamSpec.destinationsFromJson(c.getPayload())));
                    }
                } else {
                    studioRuns.apply(r.studio, c);
                }
            } catch (Exception e) {
                log.warn("Command {} for job {} failed: {}", c.getType(), c.getJobId(), e.getMessage());
            }
        }
    }

    /** A relay command per destination position; null for one turned off while live (DS-5). */
    static List<List<String>> relayCommands(List<StreamSpec.Destination> destinations) {
        List<List<String>> out = new java.util.ArrayList<>();
        for (StreamSpec.Destination d : destinations) {
            out.add(d.off() ? null : com.afklive.streamer.service.FFmpegCommandBuilder.buildRelayCommand(d.url()));
        }
        return out;
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

    /** Stop the local copy without ending the job: someone else owns it now (or will). */
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

    private void cleanup(Running r, boolean ended) {
        StudioRuns.Handle h = r.studio;
        if (h != null) {
            r.studio = null;
            try {
                studioRuns.close(h);
            } catch (Exception e) {
                log.warn("Studio cleanup for job {} failed", r.id, e);
            }
        }
        if (r.overlayTask != null) {
            r.overlayTask.cancel(true);
        }
        if (r.prepared != null) {
            preparer.cleanup(r.prepared.workDir());
        }
        if (ended && r.spec.watermarkKey() != null) {
            try {
                storage.deleteFile(r.spec.watermarkKey());
            } catch (Exception e) {
                log.debug("Watermark cleanup failed", e);
            }
        }
    }

    /**
     * On shutdown, stop the processes but leave the jobs assigned here: with a stable worker id the
     * restarted worker resumes them; otherwise another worker takes them over after the timeout.
     */
    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        commandPoller.shutdownNow();
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
        volatile StudioRuns.Handle studio;
        /** Commands up to this id were applied by an earlier worker of this run (from the job row). */
        volatile long commandsAfter;
        /** Commands this worker applied recently (bounded). */
        final java.util.Set<Long> appliedCommands = java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > 2000;
            }
        });
        volatile long maxAppliedCommand;
        long reportedAppliedCommand;
        volatile ScheduledFuture<?> overlayTask;
        volatile boolean lost;
        volatile String pendingStop;
        volatile ZonedDateTime lastHeartbeat;
        volatile boolean markedLive;
        volatile long lastProgressNanos;
        volatile boolean everProgressed;
        volatile Double bitrate;
        volatile Double fps;
        volatile Double speed;
        volatile Long drops;
        volatile String lastError;
        /** Set when the stream ended but recording that failed; retried on the next pass. */
        volatile String finishPending;
        volatile boolean finishFailed;

        Running(long id, StreamSpec spec) {
            this.id = id;
            this.spec = spec;
        }

        /** Stop now, or (if still preparing) make sure it never starts. */
        synchronized void requestStop(String reason) {
            if (runner != null) {
                runner.stop(reason);
            } else {
                pendingStop = reason;
            }
        }

        String status() {
            if (!everProgressed) {
                return lastError == null ? StreamStates.STARTING : StreamStates.RECONNECTING;
            }
            boolean fresh = System.nanoTime() - lastProgressNanos < Duration.ofSeconds(15).toNanos();
            return fresh ? StreamStates.LIVE : StreamStates.RECONNECTING;
        }

        @Override
        public void onProgress(StreamRunner.Progress p) {
            lastProgressNanos = System.nanoTime();
            everProgressed = true;
            bitrate = p.bitrateKbps();
            fps = p.fps();
            speed = p.speed();
            drops = p.droppedFrames();
            lastError = null;
        }

        @Override
        public void onDestination(int position, String status, int restarts, String error) {
            String safeError = Redact.streamKeys(error);
            try {
                destinations.updateState(id, position, status, restarts, safeError, ZonedDateTime.now(ZoneId.of("UTC")));
            } catch (Exception e) {
                log.debug("Destination state update failed", e);
            }
            if (safeError != null) {
                streamService.addLog("[job " + id + " dest " + position + "] " + safeError);
            }
        }

        @Override
        public void onEncoderFailure(int restarts, String error) {
            lastError = Redact.streamKeys(error);
            lastProgressNanos = 0;
            streamService.addLog("[job " + id + "] encoder restart " + restarts + ": " + lastError);
        }

        @Override
        public void onFinished(String reason, boolean failed) {
            if (lost) {
                running.remove(id, this);
                cleanup(this, false);
                return;
            }
            finishPending = reason;
            finishFailed = failed;
            tryFinalize();
        }

        /** Record the end first; only then forget the stream, so a failed write can't let it restart. */
        void tryFinalize() {
            try {
                streamService.finalizeJob(id, finishPending, finishFailed);
            } catch (Exception e) {
                log.error("Could not record the end of stream {}; will retry", id, e);
                return;
            }
            running.remove(id, this);
            cleanup(this, true);
        }
    }
}
