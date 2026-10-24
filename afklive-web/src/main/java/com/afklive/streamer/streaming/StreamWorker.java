package com.afklive.streamer.streaming;

import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *   <li>takes over other workers' streams that stopped reporting (so a lost machine costs under a minute);</li>
 *   <li>OPS-3: hands its streams, one at a time, to another worker when it is draining (a new version is
 *       being deployed), or takes over streams another worker offers. See {@link Handover}.</li>
 * </ol>
 * Every ownership change is a conditional database update, and each heartbeat only succeeds if the row
 * still holds the value this worker last wrote, so two processes can never both keep a stream: the
 * second writer makes the first one's next heartbeat fail, and it stops. A worker that can't reach
 * the database for {@link #FENCE_AFTER} stops its streams, since another worker will take them over.
 * During a handover the new worker's copy is not the stream's: it writes nothing but its own claim
 * (no heartbeat, health, position or destination state) until the old worker hands the stream over.
 */
@Component
@ConditionalOnExpression("'${app.streaming.role:all}' != 'web'")
@Slf4j
public class StreamWorker {

    /** Shorter than the takeover timeout, so a cut-off worker stops before anyone takes over. */
    static final Duration FENCE_AFTER = Duration.ofSeconds(30);
    /** A stream this worker failed to take over in a handover is left alone this long before another try. */
    static final Duration FAILED_HANDOVER_COOLDOWN = Duration.ofMinutes(2);
    /** After a handover, the old worker's primary connection is given this long to close before we reconnect it. */
    static final Duration RETURN_TO_PRIMARY_DELAY = Duration.ofSeconds(5);

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
    private final ScheduledExecutorService handoverPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "handover-poll");
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
    /** Don't take on a new stream while the machine is this busy (0..1), unless no one else has for a while. */
    private final double maxCpuToClaim;
    /** A new stream waiting this long starts here even when busy: late beats never (e.g. a single worker). */
    public static final java.time.Duration BUSY_CLAIM_GRACE = java.time.Duration.ofSeconds(45);
    private volatile java.util.function.DoubleSupplier cpuLoad = StreamWorker::systemCpuLoad;
    private double smoothedCpu = -1;
    /** FO-2 copy mode (off unless app.streaming.copy-mode=true); null in tests that don't wire it. */
    private CopyRuns copyRuns;
    static final Duration COPY_CHECK_EVERY = Duration.ofSeconds(60);
    private volatile Duration copyCheckEvery = COPY_CHECK_EVERY;
    static final Duration HAND_OFF_TIMEOUT = Duration.ofSeconds(30);
    private volatile boolean firstPassDone;
    private volatile long lastSuccessfulTickNanos = System.nanoTime();

    // ------------------------------------------------ OPS-3: handovers
    /** Where draining is recorded (stream_workers.draining_since); null in tests that don't wire it. */
    private WorkerStatusRepository workerStatus;
    private volatile boolean draining;
    private volatile IngestRouting routing = YouTubeIngest.INSTANCE;
    private volatile Handover.Settings handoverSettings = Handover.Settings.defaults();
    @Value("${app.streaming.handover.return-to-primary:true}")
    private volatile boolean returnToPrimary = true;
    private volatile Duration returnDelay = RETURN_TO_PRIMARY_DELAY;
    /** Streams whose handover this worker failed to complete, and when (nanoTime). */
    private final Map<Long, Long> failedHandovers = new ConcurrentHashMap<>();
    /** Draining: when each stream was last offered (nanoTime), so a stream that failed waits its turn. */
    private final Map<Long, Long> lastOffered = new ConcurrentHashMap<>();

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
                        @Value("${app.streaming.worker.auto-start:true}") boolean autoStart,
                        @Value("${app.streaming.worker.max-cpu-to-claim:0.85}") double maxCpuToClaim) {
        this.maxCpuToClaim = maxCpuToClaim;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCopyRuns(CopyRuns copyRuns) {
        this.copyRuns = copyRuns;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkerStatus(WorkerStatusRepository workerStatus) {
        this.workerStatus = workerStatus;
    }

    /** Handover timings from configuration; tests set them with {@link #setHandoverSettings}. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureHandover(@Value("${app.streaming.handover.timeout-seconds:60}") long timeoutSeconds,
                                  @Value("${app.streaming.handover.ready-seconds:5}") long readySeconds) {
        Handover.Settings d = Handover.Settings.defaults();
        this.handoverSettings = new Handover.Settings(Duration.ofSeconds(Math.max(10, timeoutSeconds)),
                Duration.ofSeconds(Math.max(1, readySeconds)), d.beatStale());
    }

    /** For tests: handover timings. */
    public void setHandoverSettings(Handover.Settings settings) {
        this.handoverSettings = settings;
    }

    /** For tests: which destinations have a backup ingest, and their addresses (production: YouTube's). */
    public void setIngestRouting(IngestRouting routing) {
        this.routing = routing;
    }

    /** For tests: whether, and how soon, YouTube destinations move back to the primary ingest after a handover. */
    public void returnToPrimary(boolean enabled, Duration delay) {
        this.returnToPrimary = enabled;
        this.returnDelay = delay;
    }

    /** AD-6: keeps each run's last FFmpeg lines (keys removed) for the admin console; null in bare tests. */
    private volatile JobLogs jobLogs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJobLogs(JobLogs jobLogs) {
        this.jobLogs = jobLogs;
    }

    /** For tests: how often studio runs are checked for moving to copy mode. */
    public void copyCheckEvery(Duration every) {
        this.copyCheckEvery = every;
    }

    private boolean copyMode() {
        return copyRuns != null && copyRuns.enabled();
    }

    public String workerId() {
        return workerId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLoop() {
        if (!autoStart) {
            return;
        }
        // A fresh process is never draining, whatever an earlier process with this id was doing.
        if (workerStatus != null) {
            try {
                workerStatus.setDraining(workerId, now(), null);
            } catch (Exception e) {
                log.warn("Couldn't clear the draining mark for {}: {}", workerId, e.getMessage());
            }
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
        handoverPoller.scheduleWithFixedDelay(() -> {
            try {
                pollHandovers();
            } catch (Exception e) {
                log.debug("Handover poll failed: {}", e.getMessage());
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
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

    // ------------------------------------------------------------------ draining (OPS-3)

    /**
     * A new version of the worker is starting next to this one: take on nothing new and hand every stream
     * to another worker, one at a time. Recorded in the database, which every pass reads.
     */
    public void drain() {
        if (workerStatus != null) {
            workerStatus.setDraining(workerId, now(), now());
        }
        if (!draining) {
            draining = true;
            log.info("Worker {} is draining: handing its {} streams to another worker", workerId, ownedCount());
        }
    }

    /** Calls a drain off (a deploy was cancelled): offers nobody took yet are withdrawn. */
    public void stopDraining() {
        if (workerStatus != null) {
            workerStatus.setDraining(workerId, now(), null);
        }
        jobs.withdrawHandoverOffers(workerId);
        if (draining) {
            draining = false;
            log.info("Worker {} stopped draining", workerId);
        }
    }

    public boolean isDraining() {
        return draining;
    }

    /** The loop has completed a pass since this process started: ready to take streams (the deploy waits for it). */
    public boolean ready() {
        return firstPassDone;
    }

    /** Streams this worker runs as the owner (not copies it is still taking over). */
    public int ownedCount() {
        return (int) running.values().stream().filter(r -> !r.incoming).count();
    }

    /** Copies this worker is starting for streams another worker still owns. */
    public int incomingCount() {
        return (int) running.values().stream().filter(r -> r.incoming).count();
    }

    /** Live streams assigned to this worker in the database (what a deploy waits on). */
    public int ownedInDatabase() {
        return jobs.findAllByWorkerIdAndIsLiveTrue(workerId).size();
    }

    // ------------------------------------------------------------------ the loop

    /** One pass of the loop; public so tests can drive it step by step. */
    public synchronized void tick() {
        ZonedDateTime now = now();
        if (workerStatus != null) {
            boolean marked = workerStatus.findDrainingSince(workerId).isPresent();
            if (marked != draining) {
                log.info("Worker {} {} draining", workerId, marked ? "is now" : "is no longer");
                if (!marked) {
                    jobs.withdrawHandoverOffers(workerId);
                }
                draining = marked;
            }
        }

        // 1. Streams assigned to this worker id.
        Set<Long> owned = new HashSet<>();
        boolean handoverOpen = false;
        List<StreamJob> mine = jobs.findAllByWorkerIdAndIsLiveTrue(workerId);
        for (StreamJob job : mine) {
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
                if (r.incoming) {
                    // The owner handed it to us: ours now. The heartbeat below is fenced on our last beat,
                    // which the handover made the stream's heartbeat (anything else means another process).
                    promote(r, r.handoverBeat, "handed over by " + job.getHandoverFrom());
                }
                if (!r.markedLive && StreamStates.LIVE.equals(r.status())) {
                    jobs.markFirstLive(id, now);
                    r.markedLive = true;
                }
                if (jobs.heartbeat(id, workerId, r.lastHeartbeat, now, r.status(), r.bitrate, r.fps, r.speed, r.drops, r.lastError) == 1) {
                    r.lastHeartbeat = now;
                    CopyRuns.Handle copy = r.copy;
                    if (copy != null) {
                        try {
                            if (r.maxAppliedCommand > r.reportedAppliedCommand) {
                                jobs.saveAppliedCommand(id, workerId, r.maxAppliedCommand);
                                r.reportedAppliedCommand = r.maxAppliedCommand;
                            }
                            copyRuns.report(id, workerId, copy);
                        } catch (Exception e) {
                            log.debug("Copy report for {} failed: {}", id, e.getMessage());
                        }
                    }
                    if (!draining) {
                        maybeMoveToCopy(r); // a draining worker leaves that to the new version
                    }
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
                    if (workerId.equals(job.getHandoverFrom())) {
                        handoverOpen = true;
                        outgoing(job, r);
                    }
                } else {
                    lose(id, "Moved to another worker");
                }
            }
        }
        // Streams running here that the database no longer gives us (ended elsewhere, or taken over).
        // Copies we're taking over aren't ours yet: they're checked below.
        for (Long id : Set.copyOf(running.keySet())) {
            Running r = running.get(id);
            if (!owned.contains(id) && r != null && r.finishPending == null && !r.incoming) {
                lose(id, "No longer assigned to this worker");
            }
        }

        // 2. New streams, then 3. other workers' streams that stopped reporting. A draining worker leaves new
        // streams to the new version, unless they've waited long (the new version died: late beats never), and
        // still rescues orphans (whatever it takes on, it offers again like the rest).
        {
            boolean busy = ownedCount() > 0 && cpuBusy();
            for (Long id : jobs.findUnclaimedIds(workerId, now.minusMinutes(1))) {
                Running r = running.get(id);
                if (r != null && r.incoming) {
                    // Its worker let go of it (shut down, or an admin moved it) mid-handover: our copy is already on air.
                    if (!draining && jobs.claim(id, workerId, now) == 1) {
                        promote(r, now, "released by its worker");
                    }
                    continue;
                }
                if (running.size() >= maxStreams) {
                    break;
                }
                if ((busy || draining) && !waitedLong(id, now)) {
                    continue; // leave it to a less busy worker (or the new version) for now
                }
                if (r == null && jobs.claim(id, workerId, now) == 1) {
                    jobs.findById(id).ifPresent(j -> start(j, now));
                }
            }
            ZonedDateTime cutoff = now.minus(StreamService.HEARTBEAT_TIMEOUT);
            for (Long id : jobs.findOrphanIds(cutoff, workerId)) {
                Running r = running.get(id);
                if (r != null && r.incoming) {
                    // The owner died mid-handover: our copy, already on air, takes over.
                    if (jobs.takeOver(id, workerId, now, cutoff) == 1) {
                        log.warn("Took over stream {} mid-handover from a worker that stopped reporting", id);
                        promote(r, now, "its worker stopped reporting");
                    }
                    continue;
                }
                if (running.size() >= maxStreams) {
                    break;
                }
                if (r == null && jobs.takeOver(id, workerId, now, cutoff) == 1) {
                    log.warn("Took over stream {} from a worker that stopped reporting", id);
                    jobs.findById(id).ifPresent(j -> start(j, now));
                }
            }
        }

        // 4. Handovers: copies we're starting, then offers (when draining) or claims.
        for (Running r : List.copyOf(running.values())) {
            if (r.incoming && !r.lost) {
                incoming(r, now);
            }
        }
        if (draining) {
            if (!handoverOpen) {
                offerNext(mine, now);
            }
        } else {
            claimOffer(now);
        }
        firstPassDone = true;
        lastSuccessfulTickNanos = System.nanoTime();
    }

    /** CPU use of the whole machine, smoothed over a few ticks so one spike doesn't turn work away. */
    boolean cpuBusy() {
        double load = cpuLoad.getAsDouble();
        if (load < 0) {
            return false; // unknown
        }
        smoothedCpu = smoothedCpu < 0 ? load : 0.6 * smoothedCpu + 0.4 * load;
        return smoothedCpu > maxCpuToClaim;
    }

    private boolean waitedLong(Long id, ZonedDateTime now) {
        return jobs.findById(id).map(j -> j.getStartTime() == null
                || j.getStartTime().isBefore(now.minus(BUSY_CLAIM_GRACE))).orElse(true);
    }

    /** For tests: what the machine's CPU use reads as. */
    public void cpuLoadFrom(java.util.function.DoubleSupplier supplier) {
        this.cpuLoad = supplier;
        this.smoothedCpu = -1;
    }

    private static double systemCpuLoad() {
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getCpuLoad();
        }
        return -1;
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------ handover: the owner's side

    /** One of our streams is offered: keep the offer current, complete it once the claimer is ready, or drop a dead claimer. */
    private void outgoing(StreamJob job, Running r) {
        long id = r.id;
        Handover.Settings s = handoverSettings;
        ZonedDateTime now = now();
        if (job.getHandoverTo() == null) {
            if (!draining) {
                jobs.withdrawHandoverOffers(workerId);
            } else {
                jobs.refreshHandoverPosition(id, workerId, offeredPosition(r));
            }
            return;
        }
        ZonedDateTime beat = job.getHandoverBeatAt();
        boolean fresh = beat != null && beat.isAfter(now.minus(s.beatStale()));
        if (job.getHandoverReadyAt() != null && fresh) {
            handOver(job, r);
        } else if (!fresh && jobs.dropStaleHandoverClaim(id, workerId, now.minus(s.beatStale())) == 1) {
            log.warn("Stream {}: {} stopped answering mid-handover; keeping the stream here", id, job.getHandoverTo());
        }
    }

    /**
     * The claimer's copy is on air everywhere it publishes. Stop our destinations that have no backup
     * ingest (they take one connection at a time), make the claimer the owner in one conditional update,
     * then stop our copy. If the update doesn't apply (the claim went stale meanwhile), reconnect what
     * we stopped and carry on.
     */
    private void handOver(StreamJob job, Running r) {
        long id = r.id;
        String to = job.getHandoverTo();
        YouTubeIngest.Ingest theirs = YouTubeIngest.Ingest.of(job.getYoutubeIngest()).other();
        List<Integer> reconnecting = new ArrayList<>();
        List<StreamSpec.Destination> ds = r.targets;
        for (int i = 0; i < ds.size(); i++) {
            if (!ds.get(i).off() && !routing.hasBackup(ds.get(i).url())) {
                reconnecting.add(i);
            }
        }
        StreamRunner runner;
        synchronized (r) {
            r.handingOff = true;
            runner = r.runner;
        }
        if (runner != null && !reconnecting.isEmpty()) {
            runner.dropRelays(reconnecting, Duration.ofSeconds(3));
        }
        int done = jobs.completeHandover(id, workerId, to, now().minus(handoverSettings.beatStale()), theirs.name());
        if (done == 1) {
            log.info("Stream {} handed over to {} ({} destinations reconnect, the rest switch to the {} ingest)",
                    id, to, reconnecting.size(), theirs);
            streamService.addLog("[job " + id + "] moved to an updated stream server without stopping");
            lastOffered.remove(id);
            lose(id, "Handed over to " + to);
        } else {
            log.warn("Stream {}: handover to {} didn't apply; keeping the stream here", id, to);
            synchronized (r) {
                r.handingOff = false;
                if (r.runner != null) {
                    r.runner.setRelays(relaysFor(r));
                }
            }
        }
    }

    /**
     * Draining: offer the next stream that is on air, the one offered longest ago first (one at a time, so
     * two encodes overlap for one stream only, and a stream whose handover failed waits its turn).
     */
    private void offerNext(List<StreamJob> mine, ZonedDateTime now) {
        List<StreamJob> candidates = new ArrayList<>();
        for (StreamJob job : mine) {
            Running r = running.get(job.getId());
            if (r == null || r.incoming || r.lost || r.switching || r.finishPending != null || r.pendingStop != null
                    || r.runner == null || !StreamStates.RUN.equals(job.getDesiredState()) || !StreamStates.LIVE.equals(r.status())) {
                continue;
            }
            candidates.add(job);
        }
        candidates.sort(Comparator.comparingLong(j -> lastOffered.getOrDefault(j.getId(), Long.MIN_VALUE)));
        for (StreamJob job : candidates) {
            Running r = running.get(job.getId());
            if (r != null && jobs.offerHandover(job.getId(), workerId, now, offeredPosition(r)) == 1) {
                lastOffered.put(job.getId(), System.nanoTime());
                log.info("Offering stream {} to another worker (draining)", job.getId());
                return;
            }
        }
    }

    /** Where the run is now, with when that was, for the claimer to start from. Null without a playlist position. */
    private String offeredPosition(Running r) {
        StudioSpec.Position p = null;
        CopyRuns.Handle c = r.copy;
        StudioRuns.Handle h = r.studio;
        try {
            if (c != null) {
                p = c.player().position();
            } else if (h != null) {
                p = studioRuns.position(h);
            }
        } catch (Exception e) {
            log.debug("No position for job {}: {}", r.id, e.getMessage());
        }
        return p == null || p.itemId() == null ? null : StudioSpec.json(new Handover.OfferedPosition(p.itemId(), p.offsetSeconds(),
                p.loopsCompleted(), System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ handover: the claimer's side

    /** Claims the oldest offer, one at a time and within capacity. Not held back by a busy CPU: streams must move. */
    private void claimOffer(ZonedDateTime now) {
        if (running.size() >= maxStreams || running.values().stream().anyMatch(r -> r.incoming)) {
            return;
        }
        long nowNanos = System.nanoTime();
        failedHandovers.values().removeIf(t -> nowNanos - t > FAILED_HANDOVER_COOLDOWN.toNanos());
        for (Long id : jobs.findHandoverOfferIds(workerId)) {
            if (running.containsKey(id) || failedHandovers.containsKey(id)) {
                continue;
            }
            if (jobs.claimHandover(id, workerId, now) == 1) {
                StreamJob job = jobs.findById(id).orElse(null);
                if (job == null) {
                    jobs.abandonHandoverClaim(id, workerId);
                    continue;
                }
                startIncoming(job, now);
                return;
            }
        }
    }

    private void startIncoming(StreamJob job, ZonedDateTime claimedAt) {
        long id = job.getId();
        StreamSpec spec;
        try {
            spec = StreamSpec.fromJson(job.getSpec());
        } catch (Exception e) {
            jobs.abandonHandoverClaim(id, workerId);
            failedHandovers.put(id, System.nanoTime());
            return;
        }
        Running r = new Running(id, spec);
        r.incoming = true;
        r.handoverBeat = claimedAt;
        r.claimedNanos = System.nanoTime();
        r.ingest = YouTubeIngest.Ingest.of(job.getYoutubeIngest()).other();
        if (job.getHandoverPosition() != null) {
            try {
                r.offered = StudioSpec.parse(job.getHandoverPosition(), Handover.OfferedPosition.class);
            } catch (Exception e) {
                log.debug("Unreadable handover position for {}", id);
            }
        }
        running.put(id, r);
        log.info("Taking over stream {} from {} (planned handover, publishing to the {} ingest)", id, job.getWorkerId(), r.ingest);
        launch(job, spec, r);
    }

    /** Our copy of a stream another worker still owns: keep the claim alive, say when it's ready, give up in time. */
    private void incoming(Running r, ZonedDateTime now) {
        long id = r.id;
        Handover.Settings s = handoverSettings;
        StreamJob job = jobs.findById(id).orElse(null);
        if (job == null || !job.isLive() || !StreamStates.RUN.equals(job.getDesiredState())) {
            lose(id, "Handover called off: the stream ended");
            return;
        }
        if (workerId.equals(job.getWorkerId())) {
            return; // handed over already: the next pass (or the handover poll) makes it ours
        }
        boolean released = job.getWorkerId() == null; // its worker let go: the unclaimed pass claims it with our copy
        if (!released && !workerId.equals(job.getHandoverTo())) {
            lose(id, "Handover called off");
            return;
        }
        boolean ownerSilent = !released && (job.getHeartbeatAt() == null
                || job.getHeartbeatAt().isBefore(now.minus(s.beatStale())));
        if (draining || (System.nanoTime() - r.claimedNanos > s.timeout().toNanos() && !(r.readySent && ownerSilent))) {
            // (A ready copy whose owner went quiet stays: if the owner died, the takeover makes it ours.)
            String why = draining ? "this worker is draining" : "not on air everywhere within " + s.timeout().toSeconds() + " s";
            jobs.abandonHandoverClaim(id, workerId);
            failedHandovers.put(id, System.nanoTime());
            log.warn("Stream {}: giving up the handover ({}); it keeps running on {}", id, why, job.getWorkerId());
            lose(id, "Handover abandoned: " + why);
            return;
        }
        if (released) {
            return;
        }
        boolean ready = r.readySent || Handover.ready(StreamStates.LIVE.equals(r.status()) ? r.encoderLiveSince : 0,
                publishing(r), r.destLiveSince, System.nanoTime(), s.readyAfter());
        if (jobs.handoverBeat(id, workerId, r.handoverBeat, now, ready) == 1) {
            r.handoverBeat = now;
            if (ready && !r.readySent) {
                r.readySent = true;
                log.info("Stream {}: new copy on air, ready to switch from {}", id, job.getWorkerId());
            }
        } else if (!workerId.equals(jobs.findWorkerIdById(id).orElse(null))) {
            lose(id, "Handover called off");
        }
    }

    /**
     * Between passes (every quarter second): a ready copy whose owner just handed it over connects its
     * remaining destinations at once, so those reconnect in about a second rather than a full pass.
     */
    public void pollHandovers() {
        for (Running r : List.copyOf(running.values())) {
            if (r.incoming && r.readySent && !r.lost && workerId.equals(jobs.findWorkerIdById(r.id).orElse(null))) {
                synchronized (this) {
                    if (r.incoming && running.get(r.id) == r) {
                        promote(r, r.handoverBeat, "handed over");
                    }
                }
            }
        }
    }

    /** Our copy becomes the stream: report it, connect the destinations that had to wait, then go back to the primary ingest. */
    private void promote(Running r, ZonedDateTime heartbeat, String how) {
        synchronized (r) {
            if (!r.incoming) {
                return;
            }
            r.incoming = false;
            r.lastHeartbeat = heartbeat;
            if (r.runner != null) {
                r.runner.setRelays(relaysFor(r));
            }
        }
        ZonedDateTime at = now();
        r.lastDestination.forEach((position, d) -> {
            try {
                destinations.updateState(r.id, position, d.status(), d.restarts(), d.error(), at);
            } catch (Exception e) {
                log.debug("Destination state update failed", e);
            }
        });
        try {
            jobs.saveYoutubeIngest(r.id, workerId, r.ingest.name());
        } catch (Exception e) {
            log.debug("Couldn't record the ingest for {}: {}", r.id, e.getMessage());
        }
        log.info("Stream {} now runs on {} ({})", r.id, workerId, how);
        maybeReturnToPrimary(r);
    }

    /**
     * After a handover the YouTube destinations publish to the backup ingest. Google asks for the primary,
     * so once the old worker's primary connection has closed, connect the primary next to the backup from
     * the same encoder, and drop the backup when the primary has been up for a few seconds.
     */
    private void maybeReturnToPrimary(Running r) {
        if (!returnToPrimary || r.ingest != YouTubeIngest.Ingest.BACKUP || r.returning) {
            return;
        }
        r.returning = true;
        Thread.ofVirtual().name("to-primary-" + r.id).start(() -> {
            try {
                Thread.sleep(returnDelay.toMillis());
                StreamRunner runner = r.runner;
                if (runner == null || r.lost || r.incoming || running.get(r.id) != r) {
                    return;
                }
                Map<Integer, List<String>> primary = new LinkedHashMap<>();
                List<StreamSpec.Destination> ds = r.targets;
                List<List<String>> wanted = relayCommands(ds, YouTubeIngest.Ingest.PRIMARY, false, routing);
                for (int i = 0; i < ds.size(); i++) {
                    if (!ds.get(i).off() && routing.hasBackup(ds.get(i).url())) {
                        primary.put(i, wanted.get(i));
                    }
                }
                if (primary.isEmpty()) {
                    r.ingest = YouTubeIngest.Ingest.PRIMARY;
                    jobs.saveYoutubeIngest(r.id, workerId, r.ingest.name());
                    return;
                }
                Duration upFor = handoverSettings.readyAfter().plusSeconds(3);
                if (runner.replaceRelays(primary, upFor, handoverSettings.timeout())) {
                    synchronized (r) {
                        r.ingest = YouTubeIngest.Ingest.PRIMARY;
                    }
                    jobs.saveYoutubeIngest(r.id, workerId, r.ingest.name());
                    streamService.addLog("[job " + r.id + "] YouTube moved back to its primary ingest, without a break");
                    log.info("Stream {}: back on the primary ingest", r.id);
                } else {
                    log.warn("Stream {}: couldn't move back to the primary ingest; staying on the backup", r.id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Moving stream {} back to the primary ingest failed: {}", r.id, e.getMessage());
            } finally {
                r.returning = false;
            }
        });
    }

    /** Positions this copy publishes to while it isn't the owner: destinations with a backup ingest. */
    private List<Integer> publishing(Running r) {
        List<Integer> out = new ArrayList<>();
        List<StreamSpec.Destination> ds = r.targets;
        for (int i = 0; i < ds.size(); i++) {
            if (!ds.get(i).off() && routing.hasBackup(ds.get(i).url())) {
                out.add(i);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ starting a stream

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
        // A fresh start (claim, takeover, resume) is the only copy: it publishes to YouTube's primary ingest,
        // even if the last owner had been left on the backup.
        if (YouTubeIngest.Ingest.of(job.getYoutubeIngest()) != YouTubeIngest.Ingest.PRIMARY) {
            try {
                jobs.saveYoutubeIngest(id, workerId, YouTubeIngest.Ingest.PRIMARY.name());
            } catch (Exception e) {
                log.debug("Couldn't record the ingest for {}: {}", id, e.getMessage());
            }
        }
        launch(job, spec, r);
    }

    private void launch(StreamJob job, StreamSpec spec, Running r) {
        long id = r.id;
        if (spec.studio() != null) {
            r.commandsAfter = job.getAppliedCommandId();
            startStudio(job, spec, r);
            return;
        }
        // Downloads can take a while: prepare off the loop thread. Heartbeats keep the job ours meanwhile.
        // (A single-file stream has no playlist position: a handover starts it from the beginning.)
        Thread.ofVirtual().name("prepare-" + id).start(() -> {
            try {
                StreamPreparer.Prepared prepared = preparer.prepare(id, spec);
                r.prepared = prepared;
                if (prepared.overlayFile() != null) {
                    r.overlayTask = overlayUpdater.scheduleAtFixedRate(() -> updateOverlay(spec, prepared), 0, 30, TimeUnit.SECONDS);
                }
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
                    StreamRunner runner = new StreamRunner(id, prepared::encoderCommand, relaysFor(r), r, settings);
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
                if (copyMode()) {
                    // A handover starts where the old copy is now (computed as late as possible); otherwise the saved position.
                    StudioSpec.Position at = r.offered != null ? Handover.advance(r.offered, System.currentTimeMillis()) : null;
                    CopyRuns.Handle c = copyRuns.prepare(id, spec.streamId(), spec.studio(), scene, at != null ? at : copyRuns.resumeFor(spec));
                    if (c != null) {
                        StreamRunner runner = new StreamRunner(id, c.player(), relaysFor(r), r, settings).seamless();
                        synchronized (r) {
                            r.copy = c;
                            if (r.lost) {
                                cleanup(r, false);
                                return;
                            }
                            if (r.pendingStop != null) {
                                r.onFinished(r.pendingStop, false);
                                return;
                            }
                            r.runner = runner;
                            r.nextCopyCheckNanos = System.nanoTime();
                            runner.start();
                        }
                        if (!r.incoming) {
                            streamService.addLog("[job " + id + "] playing by copy (no re-encoding)");
                        }
                        return;
                    }
                }
                StudioSpec.Position at = r.offered != null ? Handover.advance(r.offered, System.currentTimeMillis()) : null;
                StudioRuns.Handle h = studioRuns.start(id, spec, scene, studioListener(r), at);
                r.studio = h;
                StreamRunner runner = new StreamRunner(id, h.studio(), relaysFor(r), r, settings);
                if (copyMode()) {
                    runner.seamless(); // so it can move to copy mode later without reconnecting
                    r.nextCopyCheckNanos = System.nanoTime() + copyCheckEvery.toNanos();
                }
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

    private com.afklive.streamer.streaming.studio.Studio.Listener studioListener(Running r) {
        return new com.afklive.streamer.streaming.studio.Studio.Listener() {
            @Override
            public void onInputSwitch(boolean live) {
                if (!r.incoming && !r.lost) {
                    streamService.addLog("[job " + r.id + "] phone input " + (live ? "back: live" : "lost: showing BRB"));
                }
            }
        };
    }

    // ---------------------------------------------------------------- copy mode (FO-2)

    /**
     * A change while playing by copy needs the studio: build it at the current position and hand the
     * stream over at its first keyframe (destinations stay connected), then apply the change. Changes
     * that arrive meanwhile wait and follow in order.
     */
    private void switchToStudio(Running r, com.afklive.streamer.model.StreamCommand first) {
        CopyRuns.Handle c = r.copy;
        StreamRunner runner = r.runner;
        if (c == null || runner == null) {
            return;
        }
        r.switching = true;
        synchronized (r.deferred) {
            r.deferred.add(first);
        }
        Thread.ofVirtual().name("to-studio-" + r.id).start(() -> {
            StudioRuns.Handle h = null;
            try {
                copyRuns.savePosition(r.id, workerId, c); // the studio resumes from the saved position
                StreamSpec spec = r.spec.withStudio(c.spec());
                // A copy still being taken over can't save (the owner does): it starts the studio at its own position.
                h = studioRuns.start(r.id, spec, StudioSpec.LIVE, studioListener(r), r.incoming ? c.player().position() : null);
                h.studio().start();
                StudioRuns.Handle built = h;
                boolean started = runner.handOff(h.studio(), HAND_OFF_TIMEOUT, ok -> {
                    if (ok) {
                        r.studio = built;
                        r.copy = null;
                        copyRuns.close(c);
                        if (!r.incoming) {
                            streamService.addLog("[job " + r.id + "] switched to the studio for a live change, without reconnecting");
                        }
                    } else {
                        studioRuns.close(built);
                        if (!r.incoming) {
                            streamService.addLog("[job " + r.id + "] a live change couldn't be applied: the studio didn't start in time");
                        }
                    }
                    applyDeferred(r);
                });
                if (!started) {
                    studioRuns.close(h);
                    applyDeferred(r);
                }
            } catch (Exception e) {
                log.warn("Switching job {} to the studio failed: {}", r.id, e.getMessage());
                if (h != null) {
                    studioRuns.close(h);
                }
                applyDeferred(r);
            }
        });
    }

    private void applyDeferred(Running r) {
        List<com.afklive.streamer.model.StreamCommand> todo;
        synchronized (r.deferred) {
            todo = new java.util.ArrayList<>(r.deferred);
            r.deferred.clear();
        }
        r.switching = false;
        StudioRuns.Handle h = r.studio;
        for (com.afklive.streamer.model.StreamCommand c : todo) {
            if (h == null) {
                log.warn("Command {} for job {} dropped: still playing by copy", c.getType(), r.id);
                continue;
            }
            try {
                studioRuns.apply(h, c);
            } catch (Exception e) {
                log.warn("Command {} for job {} failed: {}", c.getType(), r.id, e.getMessage());
            }
        }
        r.nextCopyCheckNanos = System.nanoTime() + copyCheckEvery.toNanos() * 5; // stay put a while after a change
    }

    /** Every minute: a studio run that qualifies (and whose copies are ready) moves to copy mode. */
    private void maybeMoveToCopy(Running r) {
        StudioRuns.Handle h = r.studio;
        StreamRunner runner = r.runner;
        if (!copyMode() || h == null || runner == null || r.switching || r.handingOff || System.nanoTime() < r.nextCopyCheckNanos) {
            return;
        }
        r.nextCopyCheckNanos = System.nanoTime() + copyCheckEvery.toNanos();
        String scene = studioRuns.scene(h);
        if (!StudioSpec.LIVE.equals(scene) || CopyRuns.ineligible(studioRuns.spec(h), scene) != null) {
            return; // cheap checks first, on the loop thread
        }
        r.switching = true;
        // Copies may need fetching: off the loop thread, so heartbeats never wait for a download.
        Thread.ofVirtual().name("to-copy-" + r.id).start(() -> {
            CopyRuns.Handle c = null;
            try {
                StudioSpec.Position at = studioRuns.position(h);
                c = at == null ? null : copyRuns.prepare(r.id, r.spec.streamId(), studioRuns.spec(h), scene, at);
                if (c == null) {
                    applyDeferred(r);
                    r.nextCopyCheckNanos = System.nanoTime() + copyCheckEvery.toNanos();
                    return;
                }
                CopyRuns.Handle copy = c;
                boolean started = runner.handOff(c.player(), HAND_OFF_TIMEOUT, ok -> {
                    if (ok) {
                        r.copy = copy;
                        r.studio = null;
                        studioRuns.close(h);
                        streamService.addLog("[job " + r.id + "] now playing by copy (no re-encoding), without reconnecting");
                    } else {
                        copyRuns.close(copy);
                    }
                    applyDeferred(r);
                });
                if (!started) {
                    copyRuns.close(c);
                    applyDeferred(r);
                }
            } catch (Exception e) {
                log.warn("Moving job {} to copy mode failed: {}", r.id, e.getMessage());
                if (c != null) {
                    copyRuns.close(c);
                }
                applyDeferred(r);
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
     * A copy being taken over applies them too, so it shows the same thing when it goes on air.
     */
    public void pollCommands() {
        List<Running> ready = running.values().stream().filter(x -> (x.studio != null || x.copy != null || x.switching)
                && x.finishPending == null).toList();
        if (ready.isEmpty()) {
            return;
        }
        long after = ready.stream().mapToLong(x -> x.commandsAfter).min().orElse(0);
        ZonedDateTime since = ZonedDateTime.now(ZoneId.of("UTC")).minus(COMMAND_WINDOW);
        List<Long> ids = ready.stream().map(x -> x.id).toList();
        for (com.afklive.streamer.model.StreamCommand c : commands.findRecent(ids, after, since)) {
            Running r = running.get(c.getJobId());
            if (r == null || (r.studio == null && r.copy == null && !r.switching) || c.getId() <= r.commandsAfter
                    || !r.appliedCommands.add(c.getId())) {
                continue;
            }
            r.maxAppliedCommand = Math.max(r.maxAppliedCommand, c.getId());
            try {
                if (c.getType() == com.afklive.streamer.model.StreamCommand.Type.TARGETS) {
                    synchronized (r) {
                        r.targets = StreamSpec.destinationsFromJson(c.getPayload());
                        if (r.runner != null) {
                            r.runner.setRelays(relaysFor(r));
                        }
                    }
                } else if (c.getType() == com.afklive.streamer.model.StreamCommand.Type.RECONNECT) {
                    StreamRunner runner = r.runner;
                    if (runner != null && !r.incoming) {
                        runner.reconnectAll();
                        streamService.addLog("[job " + r.id + "] reconnecting every destination, as asked");
                    }
                } else if (r.switching) {
                    synchronized (r.deferred) {
                        r.deferred.add(c); // applied once the switch is done
                    }
                } else if (r.copy != null) {
                    switchToStudio(r, c);
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
        return relayCommands(destinations, YouTubeIngest.Ingest.PRIMARY, false, YouTubeIngest.INSTANCE);
    }

    /**
     * A relay command per destination position, published to {@code ingest} where the destination has a
     * backup ingest. With {@code backupOnly} the others get no relay (a copy being taken over, or one
     * being handed over: platforms without a backup ingest take one connection at a time).
     */
    static List<List<String>> relayCommands(List<StreamSpec.Destination> destinations, YouTubeIngest.Ingest ingest,
                                            boolean backupOnly, IngestRouting routing) {
        List<List<String>> out = new ArrayList<>();
        for (StreamSpec.Destination d : destinations) {
            boolean skip = d.off() || (backupOnly && !routing.hasBackup(d.url()));
            out.add(skip ? null : com.afklive.streamer.service.FFmpegCommandBuilder.buildRelayCommand(routing.route(d.url(), ingest)));
        }
        return out;
    }

    private List<List<String>> relaysFor(Running r) {
        return relayCommands(r.targets, r.ingest, r.incoming || r.handingOff, routing);
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
        JobLogs logs = jobLogs;
        if (logs != null) {
            logs.close(r.id, ended ? "ended: " + r.finishPending : "stopped on " + workerId);
        }
        CopyRuns.Handle copy = r.copy;
        if (copy != null) {
            r.copy = null;
            try {
                copyRuns.close(copy);
            } catch (Exception e) {
                log.warn("Copy cleanup for job {} failed", r.id, e);
            }
        }
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
     * A draining worker (being replaced by a new version) instead lets go of the streams it couldn't
     * hand over, once its processes have stopped, so the new worker claims them within seconds.
     * Copies it was taking over are given up.
     */
    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        commandPoller.shutdownNow();
        handoverPoller.shutdownNow();
        overlayUpdater.shutdownNow();
        List<Long> owned = new ArrayList<>();
        List<Long> claims = new ArrayList<>();
        List<StreamRunner> runners = new ArrayList<>();
        running.forEach((id, r) -> {
            (r.incoming ? claims : owned).add(id);
            if (r.runner != null) {
                runners.add(r.runner);
            }
        });
        for (Long id : Set.copyOf(running.keySet())) {
            lose(id, "Worker shutting down");
        }
        for (Long id : claims) {
            try {
                jobs.abandonHandoverClaim(id, workerId);
            } catch (Exception e) {
                log.debug("Couldn't give up the handover of {}: {}", id, e.getMessage());
            }
        }
        if (draining && !owned.isEmpty()) {
            // Let platforms see the disconnect before another worker connects in our place.
            for (StreamRunner runner : runners) {
                try {
                    runner.awaitFinished(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (Long id : owned) {
                try {
                    if (jobs.releaseOnShutdown(id, workerId) == 1) {
                        log.warn("Stream {} released for another worker (not handed over before shutdown)", id);
                    }
                } catch (Exception e) {
                    log.warn("Couldn't release stream {} on shutdown: {}", id, e.getMessage());
                }
            }
        }
    }

    public int runningCount() {
        return running.size();
    }

    /** How many streams this worker takes on at most. */
    public int capacity() {
        return maxStreams;
    }

    // ------------------------------------------------------------------ one running stream

    private record DestinationState(String status, int restarts, String error) {
    }

    private final class Running implements StreamRunner.Listener {
        final long id;
        final StreamSpec spec;
        volatile StreamRunner runner;
        volatile StreamPreparer.Prepared prepared;
        volatile StudioRuns.Handle studio;
        /** Where it goes now (TARGETS commands change this while live). */
        volatile List<StreamSpec.Destination> targets;
        /** OPS-3: the YouTube ingest this copy publishes to. */
        volatile YouTubeIngest.Ingest ingest = YouTubeIngest.Ingest.PRIMARY;
        /** OPS-3: a copy taking over from another worker that still owns the stream. */
        volatile boolean incoming;
        /** OPS-3: we're handing this stream over: destinations without a backup ingest are off. */
        volatile boolean handingOff;
        /** OPS-3: moving YouTube back to the primary ingest after a handover. */
        volatile boolean returning;
        volatile boolean readySent;
        volatile ZonedDateTime handoverBeat;
        volatile long claimedNanos;
        /** OPS-3: where the old worker's copy was when it offered the stream (null: not a handover). */
        volatile Handover.OfferedPosition offered;
        /** Commands up to this id were applied by an earlier worker of this run (from the job row). */
        volatile long commandsAfter;
        /** FO-2: set while the run plays by copy (then studio is null), and while it switches between the two. */
        volatile CopyRuns.Handle copy;
        volatile boolean switching;
        final List<com.afklive.streamer.model.StreamCommand> deferred = new java.util.ArrayList<>();
        long nextCopyCheckNanos;
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
        /** When the encoder last started producing (0: not now). */
        volatile long encoderLiveSince;
        volatile Double bitrate;
        volatile Double fps;
        volatile Double speed;
        volatile Long drops;
        volatile String lastError;
        /** Per destination position: since when it has been live (absent: not live). */
        final Map<Integer, Long> destLiveSince = new ConcurrentHashMap<>();
        final Map<Integer, DestinationState> lastDestination = new ConcurrentHashMap<>();
        /** Set when the stream ended but recording that failed; retried on the next pass. */
        volatile String finishPending;
        volatile boolean finishFailed;

        Running(long id, StreamSpec spec) {
            this.id = id;
            this.spec = spec;
            this.targets = spec.destinations() == null ? List.of() : spec.destinations();
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
            long t = System.nanoTime();
            if (encoderLiveSince == 0) {
                encoderLiveSince = t;
            }
            lastProgressNanos = t;
            everProgressed = true;
            bitrate = p.bitrateKbps();
            CopyRuns.Handle c = copy;
            // Stream copy reports no frame rate: it plays the copies' rate at the reported speed.
            fps = p.fps() > 0 || c == null ? p.fps() : c.spec().fps() * Math.min(1.0, p.speed() > 0 ? p.speed() : 1.0);
            speed = p.speed();
            drops = p.droppedFrames();
            lastError = null;
        }

        @Override
        public void onDestination(int position, String status, int restarts, String error) {
            String safeError = Redact.streamKeys(error);
            if (StreamStates.LIVE.equals(status)) {
                destLiveSince.putIfAbsent(position, System.nanoTime());
            } else {
                destLiveSince.remove(position);
            }
            lastDestination.put(position, new DestinationState(status, restarts, safeError));
            if (lost || incoming || handingOff) {
                return; // not this copy's to report: the owner's copy is what viewers see
            }
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
        public void onLogLine(String source, String line) {
            JobLogs logs = jobLogs;
            // OPS-3: a copy still being taken over isn't the run's yet: the owner's log is the one admins see.
            if (logs != null && !incoming) {
                logs.line(id, spec, source, line);
            }
        }

        @Override
        public void onEncoderFailure(int restarts, String error) {
            lastError = Redact.streamKeys(error);
            lastProgressNanos = 0;
            encoderLiveSince = 0;
            if (!incoming && !lost) {
                streamService.addLog("[job " + id + "] encoder restart " + restarts + ": " + lastError);
            }
        }

        @Override
        public void onFinished(String reason, boolean failed) {
            if (lost) {
                running.remove(id, this);
                cleanup(this, false);
                return;
            }
            if (incoming) {
                // Our copy of someone else's stream ended: give the claim up, never end the stream.
                running.remove(id, this);
                cleanup(this, false);
                failedHandovers.put(id, System.nanoTime());
                try {
                    jobs.abandonHandoverClaim(id, workerId);
                } catch (Exception e) {
                    log.debug("Couldn't give up the handover of {}: {}", id, e.getMessage());
                }
                log.warn("Stream {}: the copy being taken over stopped ({}); the handover is abandoned", id, reason);
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
