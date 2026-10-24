package com.afklive.streamer.streaming;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Supervises one live stream on a worker: one encoder whose MPEG-TS output is copied to a relay per
 * destination.
 * <ul>
 *   <li>A destination that drops is restarted on its own with backoff; the others never notice.</li>
 *   <li>If the encoder crashes it is restarted with backoff (relays restart with it, since the new
 *       encoder starts a fresh timeline). Repeated crashes in a short window end the stream as failed.</li>
 *   <li>A slow destination never slows the encoder: once its buffer (a few seconds of video) is
 *       full, that relay alone restarts.</li>
 *   <li>Every FFmpeg process is waited for and force-killed if it doesn't exit within 5 s of a stop.</li>
 * </ul>
 * Process I/O runs on platform threads: blocking pipe reads/writes would pin virtual-thread carriers.
 */
@Slf4j
public final class StreamRunner {

    /** Callbacks for the worker to persist state. Called from runner threads; must not block for long. */
    public interface Listener {
        default void onEncoderStarted(int restarts) {
        }

        default void onProgress(Progress progress) {
        }

        /**
         * The relay process: CONNECTING, RECONNECTING, LIVE (this attempt has been sending steadily for
         * {@link Settings#confirmAfter()}), STOPPED, or OFF when turned off while live.
         */
        default void onDestination(int position, String status, int restarts, String error) {
        }

        /**
         * RE-8: whether the platform is receiving: {@link #ON} once the relay has been sending steadily for
         * {@link Settings#confirmAfter()}, {@link #NOT_REACHING} after two bad windows (see {@link Reach}),
         * {@link #CONNECTING} before either. {@code reason} is the relay's last error (raw FFmpeg text, or
         * {@link #STALLED}), null when nothing failed. Called on changes only, from the runner's reach thread.
         */
        default void onReach(int position, String reach, String reason) {
        }

        default void onEncoderFailure(int restarts, String error) {
        }

        /**
         * A line FFmpeg wrote to stderr (not progress): {@code source} is "encoder" or "dest N" (1-based).
         * Unredacted: the listener must remove keys before keeping it.
         */
        default void onLogLine(String source, String line) {
        }

        void onFinished(String reason, boolean failed);
    }

    /**
     * Starts the encoder process whose stdout is the MPEG-TS program. A plain command for single-file
     * streams; the studio, which feeds raw frames into the encoder it launches, for everything else.
     */
    public interface EncoderLauncher {
        Process launch() throws IOException;

        /** Called once the process has exited (or been killed), before any relaunch. */
        default void ended(Process process) {
        }

        /**
         * Seamless mode: a normal exit means "launch me again from where I got to" (an endless playlist
         * whose scheduled list ran out), not the end of the stream.
         */
        default boolean endless() {
            return false;
        }

        /** Why the program ended when the encoder exits normally (null: "Playlist finished"). */
        default String finishReason() {
            return null;
        }

        static EncoderLauncher command(Supplier<List<String>> command) {
            return () -> new ProcessBuilder(command.get()).start();
        }
    }

    /** Encoder health, from FFmpeg's -progress output. */
    public record Progress(double fps, double bitrateKbps, double speed, long droppedFrames) {
    }

    /**
     * Timing and limits; defaults suit production, tests shorten them.
     *
     * @param relayBufferBytes per-destination buffer; about 5 s at 6 Mbps by default
     * @param confirmAfter     RE-8: a destination is "on" once its relay has been sending steadily this long
     * @param reachWindow      RE-8: two of these in a row without a steady stretch make a destination "not reaching"
     */
    public record Settings(Duration initialBackoff, Duration maxBackoff, Duration stableAfter,
                           int maxEncoderFailures, Duration failureWindow, long relayBufferBytes,
                           Duration confirmAfter, Duration reachWindow) {
        public Settings(Duration initialBackoff, Duration maxBackoff, Duration stableAfter,
                        int maxEncoderFailures, Duration failureWindow, long relayBufferBytes) {
            this(initialBackoff, maxBackoff, stableAfter, maxEncoderFailures, failureWindow, relayBufferBytes,
                    CONFIRM_AFTER, REACH_WINDOW);
        }

        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(60),
                    10, Duration.ofMinutes(10), 4L * 1024 * 1024);
        }

        /** The same, with other RE-8 timings (tests). */
        public Settings withReach(Duration confirm, Duration window) {
            return new Settings(initialBackoff, maxBackoff, stableAfter, maxEncoderFailures, failureWindow,
                    relayBufferBytes, confirm, window);
        }
    }

    /** RE-8 states of a destination, as {@link Listener#onReach} reports them. */
    public static final String ON = "ON";
    public static final String CONNECTING = "CONNECTING";
    public static final String NOT_REACHING = "NOT_REACHING";
    /** RE-8: "On YouTube" only after the relay has been sending steadily for this long. */
    public static final Duration CONFIRM_AFTER = Duration.ofSeconds(10);
    /** RE-8: one bad window; two in a row (20 s without a steady stretch) make a destination "not reaching". */
    public static final Duration REACH_WINDOW = Duration.ofSeconds(10);
    /** The longest pause in sending that still counts as steady (pipes and the encoder deliver in bursts). */
    static final Duration STEADY_GAP = Duration.ofSeconds(2);
    /** The reason when a relay runs but the platform stopped taking data (its connection hangs). */
    public static final String STALLED = "The platform stopped taking the stream";
    /** The reason when a relay was restarted because it fell behind. */
    public static final String FELL_BEHIND = "Destination couldn't keep up; reconnecting";

    private static final int CHUNK = 64 * 1024;
    private static final Duration KILL_AFTER = Duration.ofSeconds(5);

    private final long jobId;
    private volatile EncoderLauncher launcher;
    /** One relay command per destination position; null where the destination is turned off (DS-5). */
    private volatile List<List<String>> relayCommands;
    private final Listener listener;
    private final Settings settings;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    /** Wakes the supervisor from a backoff pause when a stop arrives. */
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private volatile String stopReason;
    private volatile Process encoder;
    private volatile List<Relay> relays = List.of();
    private Thread supervisor;
    /** RE-8 per destination position; survives relay (and encoder) restarts, not turning it off. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Reach> reaches = new java.util.concurrent.ConcurrentHashMap<>();
    /** OPS-3: initial reach per position for a run handed over from another worker (see {@link #seedReach}). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> seeds = new java.util.concurrent.ConcurrentHashMap<>();
    /** FO-3: bytes each destination's relays took from us (and so sent on), for the whole run. Never reset. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, AtomicLong> sent = new java.util.concurrent.ConcurrentHashMap<>();

    // ------------------------------------------------ seamless mode (FO-2)
    /**
     * Seamless mode: producers (encoder, copy-mode player) can be replaced while live, and a crashed
     * one restarted, without the relays noticing: their output goes through a {@link TsStitcher} that
     * keeps one continuous timeline, and relays run for the whole stream.
     */
    private volatile boolean seamless;
    private TsStitcher stitcher;
    private final java.util.concurrent.atomic.AtomicInteger generations = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Producer current;
    private volatile Producer pending;

    /** One producer process and the threads reading it. */
    private final class Producer {
        final int generation;
        final EncoderLauncher launcher;
        final Process process;
        final Deque<String> errors = new ArrayDeque<>();
        final CountDownLatch outputDone = new CountDownLatch(1);
        Thread stderr;

        Producer(int generation, EncoderLauncher launcher, Process process) {
            this.generation = generation;
            this.launcher = launcher;
            this.process = process;
        }
    }

    public StreamRunner(long jobId, Supplier<List<String>> encoderCommand, List<List<String>> relayCommands,
                        Listener listener, Settings settings) {
        this(jobId, EncoderLauncher.command(encoderCommand), relayCommands, listener, settings);
    }

    public StreamRunner(long jobId, EncoderLauncher launcher, List<List<String>> relayCommands,
                        Listener listener, Settings settings) {
        this.jobId = jobId;
        this.launcher = launcher;
        this.relayCommands = java.util.Collections.unmodifiableList(new ArrayList<>(relayCommands));
        this.listener = listener;
        this.settings = settings;
    }

    public synchronized void start() {
        if (supervisor != null) {
            return;
        }
        supervisor = platformThread("stream-" + jobId, seamless ? this::superviseSeamless : this::superviseEncoder);
        platformThread("reach-" + jobId, this::watchReach);
    }

    /**
     * FO-3: bytes sent per destination position since the run started on this worker (every attempt and
     * reconnect). Measured where the relay takes them from us, so what a platform didn't take isn't counted.
     */
    public java.util.Map<Integer, Long> bytesSent() {
        java.util.Map<Integer, Long> out = new java.util.HashMap<>();
        sent.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /**
     * OPS-3: before {@link #start}, the reach each destination had on the worker this run is handed over
     * from. A destination that was "on" starts "on" here (and has two windows to be steady), so a planned
     * move never shows "Connecting" or "Not reaching" for a destination the platform kept receiving.
     */
    public StreamRunner seedReach(java.util.Map<Integer, String> reach) {
        reach.forEach((k, v) -> {
            if (v != null) {
                seeds.put(k, v);
            }
        });
        return this;
    }

    /** RE-8: each destination's current reach ({@link #ON}, {@link #CONNECTING} or {@link #NOT_REACHING}). */
    public java.util.Map<Integer, String> reach() {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        reaches.forEach((k, v) -> out.put(k, v.state));
        return out;
    }

    /** Turns on seamless mode (before {@link #start}): producers can be swapped live; see {@link #handOff}. */
    public synchronized StreamRunner seamless() {
        if (supervisor == null) {
            seamless = true;
        }
        return this;
    }

    /** Which producer is on air: for copy mode vs studio, and tests. */
    public EncoderLauncher currentLauncher() {
        Producer c = current;
        return c != null ? c.launcher : launcher;
    }

    /**
     * Seamless mode only: starts {@code next} next to the current producer and switches to it at its
     * first keyframe; the old one is then stopped. Destinations stay connected and the timeline carries
     * on. If {@code next} fails or produces nothing within {@code timeout}, it is dropped and the
     * current producer simply continues; {@code onDone} learns which happened.
     */
    public boolean handOff(EncoderLauncher next, Duration timeout, java.util.function.Consumer<Boolean> onDone) {
        Producer p;
        synchronized (this) {
            if (!seamless || stopping.get() || finished.get() || current == null || pending != null) {
                return false;
            }
            try {
                p = launchProducer(next, true);
            } catch (IOException e) {
                log.warn("Hand-off for job {} could not start: {}", jobId, e.getMessage());
                return false;
            }
        }
        Producer started = p;
        platformThread("handoff-" + jobId, () -> {
            boolean switched = false;
            try {
                long deadline = System.nanoTime() + timeout.toNanos();
                while (System.nanoTime() < deadline && !stopping.get()) {
                    if (current == started) {
                        switched = true;
                        break;
                    }
                    if (!started.process.isAlive() && pending == started) {
                        break;
                    }
                    Thread.sleep(50);
                }
                switched = switched || current == started;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!switched) {
                synchronized (StreamRunner.this) {
                    if (pending == started) {
                        pending = null;
                    }
                }
                stitcher.cancel(started.generation);
                terminate(started.process);
                log.warn("Hand-off for job {} didn't take over in time; staying on the current producer", jobId);
            }
            if (onDone != null) {
                onDone.accept(switched);
            }
        });
        return true;
    }

    /** Stops the stream; the listener then gets onFinished(reason, false). Safe to call repeatedly. */
    public void stop(String reason) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopReason = reason;
        stopSignal.countDown();
        terminate(encoder);
        Producer c = current;
        Producer n = pending;
        if (c != null) {
            terminate(c.process);
        }
        if (n != null) {
            terminate(n.process);
        }
        relays.forEach(Relay::shutdown);
    }

    /**
     * Changes where the stream goes while it runs (DS-5): position i gets {@code commands.get(i)}, or
     * nothing when that entry is null. Destinations whose command didn't change keep their connection;
     * only the ones turned on start and only the ones turned off stop. The encoder never restarts.
     */
    public synchronized void setRelays(List<List<String>> commands) {
        relayCommands = java.util.Collections.unmodifiableList(new ArrayList<>(commands));
        if (stopping.get() || finished.get()) {
            return;
        }
        List<Relay> current = relays;
        List<Relay> next = new ArrayList<>();
        for (Relay r : current) {
            List<String> wanted = r.position < commands.size() ? commands.get(r.position) : null;
            if (wanted != null && wanted.equals(r.command)) {
                next.add(r);
            } else {
                r.removed = true;
                r.shutdown();
                reaches.remove(r.position, r.reach); // turned off or changed: a new key starts over
            }
        }
        for (int i = 0; i < commands.size(); i++) {
            List<String> cmd = commands.get(i);
            int pos = i;
            if (cmd != null && next.stream().noneMatch(r -> r.position == pos)) {
                Relay r = new Relay(i, cmd);
                next.add(r);
                r.start();
            }
        }
        relays = List.copyOf(next);
    }

    /**
     * GL-5: drop and redial every destination (a platform got stuck), without touching the encoder,
     * so the playlist carries on where it is. Each relay restarts straight away, not after a backoff.
     */
    public void reconnectAll() {
        for (Relay r : relays) {
            r.reconnect();
        }
    }

    /**
     * OPS-3: stops the relays at these positions and waits (up to {@code wait}) until their processes
     * have exited, so the platform has seen the disconnect before another worker connects in their place.
     * The encoder and the other destinations keep running. Nothing is reported for the dropped ones.
     */
    public void dropRelays(java.util.Collection<Integer> positions, Duration wait) {
        List<Relay> dropped;
        synchronized (this) {
            List<List<String>> commands = new ArrayList<>(relayCommands);
            for (int p : positions) {
                if (p >= 0 && p < commands.size()) {
                    commands.set(p, null);
                }
            }
            dropped = relays.stream().filter(r -> positions.contains(r.position)).toList();
            dropped.forEach(r -> r.silent = true);
            setRelays(commands);
        }
        long deadline = System.nanoTime() + wait.toNanos();
        for (Relay r : dropped) {
            r.awaitDone(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        }
    }

    /**
     * OPS-3: moves destinations to new addresses make-before-break: for each position in {@code next} a
     * second relay connects alongside the current one, fed the same encoder output; once every new relay
     * has stayed up for {@code upFor}, the old ones are stopped and the new ones take their place. If any
     * new relay doesn't get there within {@code timeout} (or the destinations change meanwhile), all new
     * ones are stopped and nothing changes. Used to move YouTube from its backup ingest back to the
     * primary: both are connected for a few seconds with identical content. Blocks; call off the loop.
     *
     * @return true when every position moved
     */
    public boolean replaceRelays(java.util.Map<Integer, List<String>> next, Duration upFor, Duration timeout)
            throws InterruptedException {
        List<Relay> added = new ArrayList<>();
        synchronized (this) {
            if (stopping.get() || finished.get() || next.isEmpty()) {
                return false;
            }
            List<Relay> now = new ArrayList<>(relays);
            for (var e : next.entrySet()) {
                if (now.stream().noneMatch(r -> r.position == e.getKey())) {
                    return false; // nothing there to replace
                }
                Relay r = new Relay(e.getKey(), e.getValue(), true);
                r.silent = true; // not this destination's state until it takes over
                added.add(r);
            }
            now.addAll(added);
            relays = List.copyOf(now);
            added.forEach(Relay::start);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean up = false;
        while (System.nanoTime() < deadline && !stopping.get()) {
            if (added.stream().anyMatch(r -> r.down.get() || !relays.contains(r))) {
                break; // stopped, or removed by a change of destinations
            }
            if (added.stream().allMatch(r -> r.upFor(upFor))) {
                up = true;
                break;
            }
            Thread.sleep(100);
        }
        synchronized (this) {
            if (up && !stopping.get() && relays.containsAll(added)) {
                List<Relay> now = new ArrayList<>();
                List<List<String>> commands = new ArrayList<>(relayCommands);
                for (Relay r : relays) {
                    boolean replaced = !added.contains(r) && next.containsKey(r.position);
                    if (replaced) {
                        r.silent = true;
                        r.removed = true;
                        r.shutdown();
                    } else {
                        now.add(r);
                    }
                }
                for (Relay r : added) {
                    r.silent = false;
                    // RE-8: the new connection carries on the destination's state ("on" stays on, no blip).
                    Reach old = reaches.get(r.position);
                    r.reach.carryOn(old);
                    reaches.put(r.position, r.reach);
                    while (commands.size() <= r.position) {
                        commands.add(null);
                    }
                    commands.set(r.position, r.command);
                    listener.onDestination(r.position, "LIVE", 0, null);
                }
                relays = List.copyOf(now);
                relayCommands = java.util.Collections.unmodifiableList(commands);
                return true;
            }
            List<Relay> now = new ArrayList<>(relays);
            now.removeAll(added);
            relays = List.copyOf(now);
        }
        added.forEach(Relay::shutdown);
        return false;
    }

    public boolean isRunning() {
        return supervisor != null && !finished.get();
    }

    /** Waits until the runner has finished, including every relay. */
    public boolean awaitFinished(Duration timeout) throws InterruptedException {
        Thread t = supervisor;
        return t == null || t.join(timeout);
    }

    // ---------------------------------------------------------------- encoder

    private void superviseEncoder() {
        Deque<Long> failureTimes = new ArrayDeque<>();
        Duration backoff = settings.initialBackoff();
        int restarts = 0;
        try {
            while (!stopping.get()) {
                long startedAt = System.nanoTime();
                RunResult result = runEncoderOnce(restarts);
                if (stopping.get()) {
                    break;
                }
                if (result.exitCode == 0) {
                    String why = launcher.finishReason();
                    finish(why != null ? why : "Playlist finished", false);
                    return;
                }

                // Unexpected exit: count it, give up if it keeps happening, else restart after a pause.
                long now = System.nanoTime();
                failureTimes.addLast(now);
                while (!failureTimes.isEmpty() && now - failureTimes.peekFirst() > settings.failureWindow().toNanos()) {
                    failureTimes.removeFirst();
                }
                restarts++;
                listener.onEncoderFailure(restarts, result.error);
                if (failureTimes.size() >= settings.maxEncoderFailures()) {
                    finish("Stream kept failing: " + result.error, true);
                    return;
                }
                if (Duration.ofNanos(now - startedAt).compareTo(settings.stableAfter()) >= 0) {
                    backoff = settings.initialBackoff(); // it ran fine for a while: retry quickly
                }
                log.warn("Encoder for job {} exited with {}; restart {} in {} ms", jobId, result.exitCode, restarts, backoff.toMillis());
                if (stopSignal.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
                backoff = min(backoff.multipliedBy(2), settings.maxBackoff());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Stream supervisor for job {} crashed", jobId, e);
            finish("Stream supervisor error: " + e.getMessage(), true);
            return;
        }
        finish(stopReason != null ? stopReason : "Stopped", false);
    }

    private record RunResult(int exitCode, String error) {
    }

    /** Starts a producer and its reader threads; its output goes to the stitcher under a new generation. */
    private Producer launchProducer(EncoderLauncher l, boolean asPending) throws IOException {
        Process proc = l.launch();
        int gen = generations.incrementAndGet();
        Producer p = new Producer(gen, l, proc);
        stitcher.expect(gen);
        if (asPending) {
            pending = p;
        } else {
            current = p;
            encoder = proc;
        }
        p.stderr = platformThread("encoder-err-" + jobId + "-" + gen,
                () -> readEncoderStderr(proc.getErrorStream(), p.errors, () -> current == p));
        platformThread("encoder-out-" + jobId + "-" + gen, () -> {
            try (InputStream out = proc.getInputStream()) {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = out.read(buf)) > 0) {
                    stitcher.feed(gen, buf, n);
                }
            } catch (IOException e) {
                if (!stopping.get() && current == p) {
                    log.warn("Reading producer output for job {} failed: {}", jobId, e.getMessage());
                }
            } finally {
                p.outputDone.countDown();
            }
        });
        return p;
    }

    /** The stitcher switched to {@code generation}: make it current and stop the one it replaced. */
    private void onStitched(int generation) {
        Producer next = pending;
        if (next == null || next.generation != generation) {
            return; // a restart after a crash: already current
        }
        Producer old = current;
        current = next;
        encoder = next.process;
        launcher = next.launcher;
        pending = null;
        if (old != null) {
            Thread.ofPlatform().daemon(true).start(() -> terminate(old.process));
        }
        // Called under the stitcher's lock, so reading its last switch is consistent.
        log.info("Job {} switched producer without reconnecting (media time {} ms off the clock)", jobId,
                Math.round(stitcher.lastSwitchErrorMillis()));
    }

    private void startRelays() {
        synchronized (this) {
            List<Relay> generation = new ArrayList<>();
            List<List<String>> commands = relayCommands;
            for (int i = 0; i < commands.size(); i++) {
                if (commands.get(i) != null) {
                    generation.add(new Relay(i, commands.get(i)));
                }
            }
            relays = List.copyOf(generation);
            generation.forEach(Relay::start);
        }
    }

    private void superviseSeamless() {
        stitcher = new TsStitcher(data -> {
            for (Relay r : relays) {
                r.offer(data);
            }
        }, this::onStitched);
        Deque<Long> failureTimes = new ArrayDeque<>();
        Duration backoff = settings.initialBackoff();
        int restarts = 0;
        startRelays();
        Producer lastExited = null;
        try {
            while (!stopping.get()) {
                long startedAt = System.nanoTime();
                if (current == null || current == lastExited) {
                    synchronized (this) {
                        if (stopping.get()) {
                            break;
                        }
                        launchProducer(launcher, false);
                    }
                    listener.onEncoderStarted(restarts);
                }
                // Wait for whichever producer is on air to exit; a hand-off moves "current" along.
                Producer exited;
                int code;
                while (true) {
                    Producer c = current;
                    code = c.process.waitFor(); // runs as long as the stream; a hand-off ends it on purpose
                    c.outputDone.await(5, TimeUnit.SECONDS);
                    joinQuietly(c.stderr, Duration.ofSeconds(2));
                    try {
                        c.launcher.ended(c.process);
                    } catch (Exception e) {
                        log.warn("Producer cleanup for job {} failed", jobId, e);
                    }
                    if (c == current || stopping.get()) {
                        exited = c;
                        break;
                    }
                }
                if (stopping.get()) {
                    break;
                }
                if (code == 0 && exited.launcher.endless()) {
                    lastExited = exited;
                    stitcher.ended(exited.generation);
                    continue; // relaunched straight away; the stitcher joins the two seamlessly
                }
                if (code == 0) {
                    String why = exited.launcher.finishReason();
                    relays.forEach(Relay::drainAndClose);
                    for (Relay r : relays) {
                        r.awaitDone(Duration.ofSeconds(15));
                    }
                    finish(why != null ? why : "Playlist finished", false);
                    return;
                }
                lastExited = exited;
                stitcher.ended(exited.generation);
                Producer waiting = pending;
                if (waiting != null) {
                    // A hand-off was under way: let the new producer take over instead of restarting the old one.
                    long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    while (current == exited && pending == waiting && System.nanoTime() < until && !stopping.get()) {
                        Thread.sleep(50);
                    }
                    if (current != exited) {
                        continue;
                    }
                }
                String error;
                synchronized (exited.errors) {
                    error = exited.errors.isEmpty() ? "exit code " + code : String.join(" | ", exited.errors);
                }
                long now = System.nanoTime();
                failureTimes.addLast(now);
                while (!failureTimes.isEmpty() && now - failureTimes.peekFirst() > settings.failureWindow().toNanos()) {
                    failureTimes.removeFirst();
                }
                restarts++;
                listener.onEncoderFailure(restarts, error);
                if (failureTimes.size() >= settings.maxEncoderFailures()) {
                    finish("Stream kept failing: " + error, true);
                    return;
                }
                if (Duration.ofNanos(now - startedAt).compareTo(settings.stableAfter()) >= 0) {
                    backoff = settings.initialBackoff();
                }
                log.warn("Producer for job {} exited with {}; restart {} in {} ms (destinations stay connected)",
                        jobId, code, restarts, backoff.toMillis());
                if (stopSignal.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
                backoff = min(backoff.multipliedBy(2), settings.maxBackoff());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Stream supervisor for job {} crashed", jobId, e);
            finish("Stream supervisor error: " + e.getMessage(), true);
            return;
        }
        finish(stopReason != null ? stopReason : "Stopped", false);
    }

    private RunResult runEncoderOnce(int restarts) throws IOException {
        Process p = launcher.launch();
        encoder = p;
        if (stopping.get()) {
            terminate(p);
        }
        Deque<String> errorLines = new ArrayDeque<>();
        Thread stderr = platformThread("encoder-err-" + jobId, () -> readEncoderStderr(p.getErrorStream(), errorLines));

        synchronized (this) {
            relays.forEach(Relay::shutdown); // any started by setRelays between two encoder runs
            List<Relay> generation = new ArrayList<>();
            List<List<String>> commands = relayCommands;
            for (int i = 0; i < commands.size(); i++) {
                if (commands.get(i) != null) {
                    generation.add(new Relay(i, commands.get(i)));
                }
            }
            relays = List.copyOf(generation);
            if (stopping.get()) {
                generation.forEach(Relay::shutdown);
            }
            generation.forEach(Relay::start);
        }
        listener.onEncoderStarted(restarts);

        int code = -1;
        try {
            // Pump encoder output to every relay without ever blocking on a slow one.
            try (InputStream out = p.getInputStream()) {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = out.read(buf)) > 0) {
                    byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    for (Relay r : relays) { // read each time: destinations can change while live
                        r.offer(chunk);
                    }
                }
            } catch (IOException e) {
                if (!stopping.get()) {
                    log.warn("Reading encoder output for job {} failed: {}", jobId, e.getMessage());
                }
            }
            code = waitOrKill(p);
        } finally {
            // Always: let relays finish (drain on a normal end), then make sure no process outlives this run.
            List<Relay> generation = relays;
            if (code == 0 && !stopping.get()) {
                generation.forEach(Relay::drainAndClose);
            } else {
                generation.forEach(Relay::shutdown);
            }
            for (Relay r : generation) {
                r.awaitDone(Duration.ofSeconds(15));
            }
            if (p.isAlive()) {
                Processes.kill(p);
            }
            try {
                launcher.ended(p);
            } catch (Exception e) {
                log.warn("Encoder cleanup for job {} failed", jobId, e);
            }
            joinQuietly(stderr, Duration.ofSeconds(2));
        }
        String error;
        synchronized (errorLines) {
            error = errorLines.isEmpty() ? "exit code " + code : String.join(" | ", errorLines);
        }
        return new RunResult(code, error);
    }

    private void readEncoderStderr(InputStream err, Deque<String> errorLines) {
        readEncoderStderr(err, errorLines, () -> true);
    }

    private void readEncoderStderr(InputStream err, Deque<String> errorLines, java.util.function.BooleanSupplier report) {
        double fps = 0;
        double bitrate = 0;
        double speed = 0;
        long drops = 0;
        long frames = 0;
        long outTimeUs = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                String key = eq > 0 ? line.substring(0, eq).trim() : "";
                String value = eq > 0 ? line.substring(eq + 1).trim() : "";
                switch (key) {
                    case "fps" -> fps = parseLeadingNumber(value);
                    case "bitrate" -> bitrate = parseLeadingNumber(value);
                    case "speed" -> speed = parseLeadingNumber(value);
                    case "drop_frames" -> drops = (long) parseLeadingNumber(value);
                    case "frame" -> frames = (long) parseLeadingNumber(value);
                    case "out_time_us" -> outTimeUs = (long) parseLeadingNumber(value);
                    case "progress" -> {
                        // Stream copy (FO-2 copy mode) reports no fps: frames over time give it.
                        double rate = fps > 0 || frames == 0 || outTimeUs <= 0 ? fps : frames / (outTimeUs / 1e6);
                        if (report.getAsBoolean()) {
                            listener.onProgress(new Progress(rate, bitrate, speed, drops));
                        }
                    }
                    case "stream_0_0_q", "total_size", "out_time_ms", "out_time", "dup_frames" -> {
                        // progress fields we don't store
                    }
                    default -> {
                        if (!line.isBlank() && !isProgressField(line)) {
                            try {
                                listener.onLogLine("encoder", line);
                            } catch (RuntimeException ignored) {
                                // a listener problem must never stop us draining the pipe
                            }
                        }
                        if (!line.isBlank()) {
                            synchronized (errorLines) {
                                errorLines.addLast(line.length() > 300 ? line.substring(0, 300) : line);
                                while (errorLines.size() > 5) {
                                    errorLines.removeFirst();
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    /** "stream_0_1_q=28.0" and the like: progress output, not a message. */
    static boolean isProgressField(String line) {
        return line.matches("[A-Za-z0-9_]+=\\S*");
    }

    static double parseLeadingNumber(String value) {
        int end = 0;
        while (end < value.length() && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.' || value.charAt(end) == '-')) {
            end++;
        }
        try {
            return end == 0 ? 0 : Double.parseDouble(value.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void finish(String reason, boolean failed) {
        if (finished.compareAndSet(false, true)) {
            relays.forEach(Relay::shutdown);
            for (Relay r : relays) {
                r.awaitDone(Duration.ofSeconds(15));
            }
            try {
                listener.onFinished(reason, failed);
            } catch (Exception e) {
                log.error("onFinished failed for job {}", jobId, e);
            }
        }
    }

    // ---------------------------------------------------------------- relays

    /** One destination: a copy-only FFmpeg fed from a byte-bounded queue, restarted on its own when it drops. */
    private final class Relay {
        private final int position;
        private final List<String> command;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final AtomicLong queuedBytes = new AtomicLong();
        private final AtomicBoolean down = new AtomicBoolean();
        private final CountDownLatch downSignal = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        /** Set when the input has ended: write what's queued, then send EOF and don't restart. */
        private volatile boolean draining;
        private volatile Process process;
        private volatile boolean restartRequested;
        private volatile boolean reconnectRequested;
        /**
         * True only while a relay process is running and reading. Between attempts (backoff) nothing is
         * queued: that video would be stale anyway, and queueing it used to overflow the buffer during the
         * wait, which flagged the next, healthy attempt as "couldn't keep up", killed it within a second and
         * hid the real error, so a destination that dropped once never came back.
         */
        private volatile boolean accepting = true; // the first attempt takes the stream from its very start
        /** Turned off while live: report OFF, not STOPPED. */
        volatile boolean removed;
        /** OPS-3: reports nothing (a relay connecting alongside, or one being retired). */
        volatile boolean silent;
        /** When the running relay process started (0: none running). */
        volatile long runningSinceNanos;
        /**
         * RE-8: shared by every relay of this position until it is turned off. A relay connecting alongside
         * (OPS-3 make-before-break) has its own until it takes over (see {@link #replaceRelays}).
         */
        final Reach reach;
        /** FO-3: every byte this position's relay processes took from us (all of them: it is all traffic). */
        private final AtomicLong sentBytes;

        Relay(int position, List<String> command) {
            this(position, command, false);
        }

        Relay(int position, List<String> command, boolean alongside) {
            this.position = position;
            this.command = command;
            this.sentBytes = sent.computeIfAbsent(position, p -> new AtomicLong());
            this.reach = alongside ? new Reach(position, System.nanoTime(), null)
                    : reaches.computeIfAbsent(position, p -> new Reach(p, System.nanoTime(), seeds.remove(p)));
        }

        void start() {
            platformThread("relay-" + jobId + "-" + position, this::supervise);
        }

        /** The current relay process has been running for at least {@code d}. */
        boolean upFor(Duration d) {
            long since = runningSinceNanos;
            return since != 0 && System.nanoTime() - since >= d.toNanos();
        }

        private void report(String status, int restarts, String error) {
            if (!silent) {
                listener.onDestination(position, status, restarts, error);
            }
        }

        void offer(byte[] chunk) {
            reach.fed();
            if (!accepting) {
                return; // no relay running right now: drop, don't build a backlog
            }
            if (queuedBytes.get() + chunk.length > settings.relayBufferBytes()) {
                // This destination can't keep up: restart it rather than slow everyone down or run out of memory.
                accepting = false;
                restartRequested = true;
                clearQueue();
                terminate(process);
                return;
            }
            queuedBytes.addAndGet(chunk.length);
            queue.offer(chunk);
        }

        private void clearQueue() {
            queue.clear();
            queuedBytes.set(0);
        }

        void reconnect() {
            reconnectRequested = true;
            clearQueue();
            terminate(process);
        }

        void drainAndClose() {
            draining = true;
        }

        void shutdown() {
            if (down.compareAndSet(false, true)) {
                downSignal.countDown();
                Process p = process;
                if (p != null) {
                    // Signal first: closing stdin waits for its lock, which a writer blocked on a full pipe holds.
                    terminate(p);
                    Thread.ofPlatform().daemon(true).name("relay-close-" + jobId + "-" + position).start(() -> {
                        try {
                            p.getOutputStream().close(); // EOF, once the writer lets go
                        } catch (IOException ignored) {
                            // already closed
                        }
                    });
                }
            }
        }

        void awaitDone(Duration timeout) {
            try {
                if (!done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    Process p = process;
                    if (p != null) {
                        Processes.kill(p);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void supervise() {
            Duration backoff = settings.initialBackoff();
            int restarts = 0;
            try {
                while (!down.get()) {
                    long startedAt = System.nanoTime();
                    String error = null;
                    try {
                        Process p = new ProcessBuilder(command).start();
                        process = p;
                        if (down.get()) {
                            terminate(p);
                        }
                        accepting = true;
                        runningSinceNanos = System.nanoTime();
                        report(restarts == 0 ? "CONNECTING" : "RECONNECTING", restarts, null);
                        Deque<String> errLines = new ArrayDeque<>();
                        Thread errReader = platformThread("relay-err-" + jobId + "-" + position, () -> readLines(p.getErrorStream(), errLines,
                                line -> listener.onLogLine("dest " + (position + 1), line)));
                        Thread writer = platformThread("relay-in-" + jobId + "-" + position, () -> write(p));
                        int attempt = reach.attemptStarted();
                        boolean reportedLive = false;
                        while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                            if (!reportedLive && reach.confirmed(attempt)) {
                                // RE-8: LIVE means this attempt has been sending steadily, not just that FFmpeg runs.
                                report("LIVE", restarts, null);
                                reportedLive = true;
                            }
                        }
                        reach.attemptEnded();
                        accepting = false;
                        runningSinceNanos = 0;
                        joinQuietly(writer, Duration.ofSeconds(1));
                        joinQuietly(errReader, Duration.ofSeconds(1));
                        synchronized (errLines) {
                            error = errLines.isEmpty() ? "exit code " + p.exitValue() : String.join(" | ", errLines);
                        }
                    } catch (IOException e) {
                        error = e.getMessage();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } finally {
                        accepting = false;
                        reach.attemptEnded();
                        runningSinceNanos = 0;
                    }
                    if (down.get() || draining) {
                        break;
                    }
                    if (restartRequested) {
                        restartRequested = false;
                        error = FELL_BEHIND;
                    }
                    if (!reconnectRequested) {
                        reach.failed(error);
                    }
                    if (reconnectRequested) {
                        // Asked for (GL-5): not a failure, so no backoff and no restart counted.
                        reconnectRequested = false;
                        report("RECONNECTING", restarts, null);
                        clearQueue();
                        continue;
                    }
                    restarts++;
                    report("RECONNECTING", restarts, error);
                    if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(settings.stableAfter()) >= 0) {
                        backoff = settings.initialBackoff();
                    }
                    clearQueue(); // stale video is useless after a reconnect
                    try {
                        if (downSignal.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff = min(backoff.multipliedBy(2), settings.maxBackoff());
                }
            } finally {
                Process p = process;
                if (p != null && p.isAlive()) {
                    waitOrKill(p);
                }
                report(removed ? "OFF" : "STOPPED", restarts, null);
                done.countDown();
            }
        }

        private void write(Process p) {
            try (OutputStream in = p.getOutputStream()) {
                while (p.isAlive() && !down.get()) {
                    byte[] chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        queuedBytes.addAndGet(-chunk.length);
                        in.write(chunk);
                        sentBytes.addAndGet(chunk.length); // FO-3: the relay took it
                        reach.took(chunk.length); // RE-8
                    } else if (draining) {
                        break; // input ended and everything queued has been written: EOF
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // relay process ended or we're shutting down
            }
        }
    }

    // ---------------------------------------------------------------- RE-8: is the platform receiving?

    /**
     * Whether one destination's platform is receiving, judged from what its relay takes from us: a relay
     * that can't open its output exits, and one whose connection hangs stops reading (its pipe fills up),
     * so either way the bytes stop. Steady means bytes flowing with no pause over {@link #STEADY_GAP}.
     * <ul>
     *   <li>{@link #ON} after {@link Settings#confirmAfter()} steady;</li>
     *   <li>{@link #NOT_REACHING} once two windows ({@link Settings#reachWindow()} each) pass without a
     *       confirmed steady stretch: from the start, or from when an "on" destination lost it. One
     *       reconnect (a few seconds' gap, then steady again) never flips it; a reconnect loop does.</li>
     * </ul>
     */
    final class Reach {
        final int position;
        /** Bytes this destination's relays took (this connection's, for one connecting alongside). */
        private final AtomicLong taken = new AtomicLong();
        /** Written by relay threads. */
        private volatile boolean attemptUp;
        private final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        private volatile String lastError;
        /** When the stream last had data for this destination (0: not yet). */
        private volatile long lastFed;
        /** The attempt whose steady stretch reached {@link Settings#confirmAfter()} (0: none yet). */
        private volatile int confirmedAttempt;
        volatile String state = CONNECTING;
        volatile String reportedReason;
        /** Took over from another connection of the same destination (OPS-3): nothing new to say. */
        volatile boolean carried;
        // Evaluation state: the reach thread only.
        private long lastSent;
        private long lastGrowth;
        private long steadySince;
        private int steadyAttempt;
        private long since;
        private long troubleSince;

        /** {@code seed}: the state to start in (OPS-3: a run handed over from another worker keeps "on"). */
        Reach(int position, long now, String seed) {
            this.position = position;
            this.since = now;
            if (ON.equals(seed)) {
                this.state = ON;
            }
        }

        void took(int bytes) {
            taken.addAndGet(bytes);
        }

        /**
         * OPS-3: this connection replaces {@code old} (make-before-break): keep its state, and give it two
         * windows to be steady here (it is already sending, so it normally is at once).
         */
        void carryOn(Reach old) {
            carried = true;
            if (old != null) {
                state = old.state;
                reportedReason = old.reportedReason;
                lastError = old.lastError;
            }
            since = System.nanoTime();
            troubleSince = 0;
        }

        int attemptStarted() {
            int a = attempts.incrementAndGet();
            attemptUp = true;
            return a;
        }

        void attemptEnded() {
            attemptUp = false;
        }

        /** The stream had data for this destination (whether or not its relay could take it). */
        void fed() {
            lastFed = System.nanoTime();
        }

        void failed(String error) {
            if (error != null) {
                // FFmpeg prefixes lines with object addresses ("[rtmp @ 0x55d0...]") that differ per process.
                lastError = error.replaceAll("@ 0x[0-9a-fA-F]+", "@");
            }
        }

        boolean confirmed(int attempt) {
            return confirmedAttempt == attempt;
        }

        /** One look; returns true when what should be reported changed. */
        boolean evaluate(long now) {
            long s = taken.get();
            int attempt = attempts.get();
            boolean up = attemptUp;
            if (s > lastSent) {
                lastSent = s;
                lastGrowth = now;
                if (steadySince == 0 && up) {
                    steadySince = now;
                    steadyAttempt = attempt;
                }
            }
            boolean steady = up && steadySince != 0 && steadyAttempt == attempt
                    && now - lastGrowth <= STEADY_GAP.toNanos();
            if (!steady) {
                steadySince = 0;
            }
            boolean confirmedNow = steady && now - steadySince >= settings.confirmAfter().toNanos();
            if (confirmedNow) {
                confirmedAttempt = attempt;
            }
            long twoWindows = settings.reachWindow().multipliedBy(2).toNanos();
            long fedAt = lastFed;
            if (fedAt == 0 || now - fedAt > STEADY_GAP.toNanos()) {
                // Nothing to send (the encoder is starting or restarting): our side, not the platform's.
                // The windows only count while there is something to deliver.
                since = now;
                troubleSince = 0;
            }
            String next = state;
            switch (state) {
                case ON -> {
                    if (confirmedNow) {
                        troubleSince = 0;
                    } else if (!steady && troubleSince == 0) {
                        troubleSince = now; // first bad moment; it has two windows to be steady again
                    }
                    if (troubleSince != 0 && now - troubleSince >= twoWindows) {
                        next = NOT_REACHING;
                    }
                }
                case CONNECTING -> {
                    if (confirmedNow) {
                        next = ON;
                    } else if (now - since >= twoWindows) {
                        next = NOT_REACHING;
                    }
                }
                default -> {
                    if (confirmedNow) {
                        next = ON;
                    }
                }
            }
            if (!next.equals(state)) {
                since = now;
                troubleSince = 0;
                if (ON.equals(next)) {
                    lastError = null;
                }
            }
            String reason = ON.equals(next) ? null : reason(up);
            boolean changed = !next.equals(state) || NOT_REACHING.equals(next) && !java.util.Objects.equals(reason, reportedReason);
            state = next;
            reportedReason = reason;
            return changed;
        }

        /** Why it isn't receiving: the relay's last error, or that a running relay stopped sending. */
        private String reason(boolean up) {
            String e = lastError;
            if (e != null) {
                return e;
            }
            return up && lastGrowth != 0 ? STALLED : null;
        }
    }

    /** The reach thread: looks at every destination twice a second until the stream ends. */
    private void watchReach() {
        // What was last said per position: a connection that replaces another (OPS-3) says nothing new
        // unless its state differs; a destination turned off and on again starts over.
        java.util.Map<Integer, Reach> announced = new java.util.HashMap<>();
        java.util.Map<Integer, String> said = new java.util.HashMap<>();
        while (!finished.get() && !stopping.get()) {
            long now = System.nanoTime();
            for (Reach r : reaches.values()) {
                try {
                    boolean fresh = announced.get(r.position) != r;
                    if (r.evaluate(now) || fresh) {
                        announced.put(r.position, r);
                        String what = r.state + "|" + r.reportedReason;
                        String before = said.put(r.position, what);
                        if (!what.equals(before) || (fresh && !r.carried)) {
                            listener.onReach(r.position, r.state, r.reportedReason);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Reach check for job {} failed", jobId, e);
                }
            }
            announced.values().removeIf(r -> reaches.get(r.position) == null);
            said.keySet().removeIf(pos -> !reaches.containsKey(pos));
            try {
                if (stopSignal.await(500, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** SIGTERM now; SIGKILL if still alive after {@link #KILL_AFTER}. */
    static void terminate(Process p) {
        if (p == null || !p.isAlive()) {
            return;
        }
        Processes.terminate(p);
        p.onExit().orTimeout(KILL_AFTER.toMillis(), TimeUnit.MILLISECONDS).exceptionally(t -> {
            Processes.kill(p);
            return p;
        });
    }

    /** Waits for exit; a process that won't exit is killed. Never throws. */
    private static int waitOrKill(Process p) {
        try {
            if (p.waitFor(KILL_AFTER.toMillis() * 6, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Processes.kill(p);
        try {
            p.waitFor(KILL_AFTER.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return p.isAlive() ? -1 : p.exitValue();
    }

    private static Thread platformThread(String name, Runnable r) {
        return Thread.ofPlatform().name(name).daemon(true).start(r);
    }

    private static void joinQuietly(Thread t, Duration timeout) {
        try {
            t.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readLines(InputStream in, Deque<String> lines, java.util.function.Consumer<String> each) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    try {
                        each.accept(line);
                    } catch (RuntimeException ignored) {
                        // a listener problem must never stop us draining the pipe
                    }
                    synchronized (lines) {
                        lines.addLast(line.length() > 300 ? line.substring(0, 300) : line);
                        while (lines.size() > 3) {
                            lines.removeFirst();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
