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

        default void onDestination(int position, String status, int restarts, String error) {
        }

        default void onEncoderFailure(int restarts, String error) {
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
     */
    public record Settings(Duration initialBackoff, Duration maxBackoff, Duration stableAfter,
                           int maxEncoderFailures, Duration failureWindow, long relayBufferBytes) {
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(60),
                    10, Duration.ofMinutes(10), 4L * 1024 * 1024);
        }
    }

    private static final int CHUNK = 64 * 1024;
    private static final Duration KILL_AFTER = Duration.ofSeconds(5);

    private final long jobId;
    private final EncoderLauncher launcher;
    private final List<List<String>> relayCommands;
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

    public StreamRunner(long jobId, Supplier<List<String>> encoderCommand, List<List<String>> relayCommands,
                        Listener listener, Settings settings) {
        this(jobId, EncoderLauncher.command(encoderCommand), relayCommands, listener, settings);
    }

    public StreamRunner(long jobId, EncoderLauncher launcher, List<List<String>> relayCommands,
                        Listener listener, Settings settings) {
        this.jobId = jobId;
        this.launcher = launcher;
        this.relayCommands = List.copyOf(relayCommands);
        this.listener = listener;
        this.settings = settings;
    }

    public synchronized void start() {
        if (supervisor != null) {
            return;
        }
        supervisor = platformThread("stream-" + jobId, this::superviseEncoder);
    }

    /** Stops the stream; the listener then gets onFinished(reason, false). Safe to call repeatedly. */
    public void stop(String reason) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopReason = reason;
        stopSignal.countDown();
        terminate(encoder);
        relays.forEach(Relay::shutdown);
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

    private RunResult runEncoderOnce(int restarts) throws IOException {
        Process p = launcher.launch();
        encoder = p;
        if (stopping.get()) {
            terminate(p);
        }
        Deque<String> errorLines = new ArrayDeque<>();
        Thread stderr = platformThread("encoder-err-" + jobId, () -> readEncoderStderr(p.getErrorStream(), errorLines));

        List<Relay> generation = new ArrayList<>();
        for (int i = 0; i < relayCommands.size(); i++) {
            generation.add(new Relay(i, relayCommands.get(i)));
        }
        relays = generation;
        if (stopping.get()) {
            generation.forEach(Relay::shutdown);
        }
        generation.forEach(Relay::start);
        listener.onEncoderStarted(restarts);

        int code = -1;
        try {
            // Pump encoder output to every relay without ever blocking on a slow one.
            try (InputStream out = p.getInputStream()) {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = out.read(buf)) > 0) {
                    byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    for (Relay r : generation) {
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
            if (code == 0 && !stopping.get()) {
                generation.forEach(Relay::drainAndClose);
            } else {
                generation.forEach(Relay::shutdown);
            }
            for (Relay r : generation) {
                r.awaitDone(Duration.ofSeconds(15));
            }
            if (p.isAlive()) {
                p.destroyForcibly();
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
        double fps = 0;
        double bitrate = 0;
        double speed = 0;
        long drops = 0;
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
                    case "progress" -> listener.onProgress(new Progress(fps, bitrate, speed, drops));
                    case "frame", "stream_0_0_q", "total_size", "out_time_us", "out_time_ms", "out_time",
                         "dup_frames" -> {
                        // progress fields we don't store
                    }
                    default -> {
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

        Relay(int position, List<String> command) {
            this.position = position;
            this.command = command;
        }

        void start() {
            platformThread("relay-" + jobId + "-" + position, this::supervise);
        }

        void offer(byte[] chunk) {
            if (queuedBytes.get() + chunk.length > settings.relayBufferBytes()) {
                // This destination can't keep up: restart it rather than slow everyone down or run out of memory.
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

        void drainAndClose() {
            draining = true;
        }

        void shutdown() {
            if (down.compareAndSet(false, true)) {
                downSignal.countDown();
                Process p = process;
                if (p != null) {
                    try {
                        p.getOutputStream().close(); // EOF lets FFmpeg finish cleanly
                    } catch (IOException ignored) {
                        // already closed
                    }
                    terminate(p);
                }
            }
        }

        void awaitDone(Duration timeout) {
            try {
                if (!done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    Process p = process;
                    if (p != null) {
                        p.destroyForcibly();
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
                        listener.onDestination(position, restarts == 0 ? "CONNECTING" : "RECONNECTING", restarts, null);
                        Deque<String> errLines = new ArrayDeque<>();
                        Thread errReader = platformThread("relay-err-" + jobId + "-" + position, () -> readLines(p.getErrorStream(), errLines));
                        Thread writer = platformThread("relay-in-" + jobId + "-" + position, () -> write(p));
                        boolean reportedLive = false;
                        while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                            if (!reportedLive && Duration.ofNanos(System.nanoTime() - startedAt).toSeconds() >= 3) {
                                listener.onDestination(position, "LIVE", restarts, null);
                                reportedLive = true;
                            }
                        }
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
                    }
                    if (down.get() || draining) {
                        break;
                    }
                    if (restartRequested) {
                        restartRequested = false;
                        error = "Destination couldn't keep up; reconnecting";
                    }
                    restarts++;
                    listener.onDestination(position, "RECONNECTING", restarts, error);
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
                listener.onDestination(position, "STOPPED", restarts, null);
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
                    } else if (draining) {
                        break; // input ended and everything queued has been written: EOF
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // relay process ended or we're shutting down
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** SIGTERM now; SIGKILL if still alive after {@link #KILL_AFTER}. */
    private static void terminate(Process p) {
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        p.onExit().orTimeout(KILL_AFTER.toMillis(), TimeUnit.MILLISECONDS).exceptionally(t -> {
            p.destroyForcibly();
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
        p.destroyForcibly();
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

    private static void readLines(InputStream in, Deque<String> lines) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
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
