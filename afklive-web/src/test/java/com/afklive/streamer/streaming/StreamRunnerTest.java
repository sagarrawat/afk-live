package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the supervisor with real FFmpeg processes, writing to files instead of platforms. */
class StreamRunnerTest {

    @TempDir
    static Path dir;
    static Path source;

    /** Short backoffs, and a destination counts as LIVE after 2 s of steady sending (production: 10 s). */
    static final StreamRunner.Settings FAST = new StreamRunner.Settings(
            Duration.ofMillis(100), Duration.ofMillis(400), Duration.ofSeconds(60), 3, Duration.ofMinutes(1), 4L * 1024 * 1024,
            Duration.ofSeconds(2), StreamRunner.STALL_AFTER);

    @BeforeAll
    static void makeSource() throws Exception {
        assumeTrue(exit(List.of("ffmpeg", "-version")) == 0, "ffmpeg not installed");
        source = dir.resolve("src.mp4");
        assertThat(exit(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", source.toString()))).isZero();
    }

    /** The real encoder command, minus real-time pacing so tests run fast. */
    static List<String> encoder(int loops) {
        List<String> c = realtimeEncoder(loops);
        c.remove("-re");
        return c;
    }

    /** Paced like production (-re): a 4 s source takes 4 s, so destination failures have time to happen. */
    static List<String> realtimeEncoder(int loops) {
        return new ArrayList<>(FFmpegCommandBuilder.buildEncoderToPipeCommand(source, null, "1.0", loops, null,
                false, "original", 180, false, null, true));
    }

    static List<String> relayTo(Path file) {
        // -y: a reconnect reopens the same file (a platform URL never asks to overwrite).
        List<String> c = new ArrayList<>(FFmpegCommandBuilder.buildRelayCommand("file://" + file));
        c.add(1, "-y");
        return c;
    }

    static class Recorder implements StreamRunner.Listener {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final Map<Integer, String> lastStatus = new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<String> statuses = new CopyOnWriteArrayList<>();
        final Map<Integer, Integer> restarts = new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger progress = new AtomicInteger();
        final Map<Integer, Long> bytes = new ConcurrentHashMap<>();
        /** Milliseconds after the recorder was made when each position was first reported LIVE. */
        final Map<Integer, Long> firstLiveAt = new ConcurrentHashMap<>();
        final long createdAt = System.nanoTime();
        final AtomicInteger encoderFailures = new AtomicInteger();
        volatile String finishedReason;
        volatile boolean failed;

        @Override public void onEncoderStarted(int r) { events.add("encoder-started-" + r); }
        @Override public void onProgress(StreamRunner.Progress p) { progress.incrementAndGet(); }
        @Override public void onDestination(int pos, String status, int r, String error) {
            lastStatus.put(pos, status);
            if ("LIVE".equals(status)) {
                firstLiveAt.putIfAbsent(pos, (System.nanoTime() - createdAt) / 1_000_000);
            }
            statuses.add(pos + ":" + status);
            restarts.merge(pos, r, Math::max);
            if (error != null) {
                errors.add(pos + ":" + error);
            }
        }
        @Override public void onDestinationBytes(int pos, long total) { bytes.merge(pos, total, Math::max); }
        @Override public void onEncoderFailure(int r, String error) { encoderFailures.incrementAndGet(); }
        @Override public void onFinished(String reason, boolean f) { finishedReason = reason; failed = f; }
    }

    @Test
    void encodesOnceAndDeliversToEveryDestination() throws Exception {
        Path a = dir.resolve("a.flv");
        Path b = dir.resolve("b.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(1, () -> encoder(0), List.of(relayTo(a), relayTo(b)), rec, FAST);
        runner.start();
        assertThat(runner.awaitFinished(Duration.ofSeconds(60))).isTrue();

        assertThat(rec.finishedReason).isEqualTo("Playlist finished");
        assertThat(rec.failed).isFalse();
        assertThat(rec.progress.get()).isPositive();
        for (Path out : List.of(a, b)) {
            assertThat(Files.size(out)).isPositive();
            assertThat(probe(out)).contains("h264").contains("aac");
        }
    }

    @Test
    void aFailingDestinationReconnectsWithoutAffectingTheOthers() throws Exception {
        Path good = dir.resolve("good.flv");
        Path bad = dir.resolve("no-such-dir").resolve("bad.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(2, () -> realtimeEncoder(1), List.of(relayTo(good), relayTo(bad)), rec, FAST);
        runner.start();
        assertThat(runner.awaitFinished(Duration.ofSeconds(60))).isTrue();

        assertThat(rec.finishedReason).isEqualTo("Playlist finished");
        assertThat(probe(good)).contains("h264");
        assertThat(rec.restarts.getOrDefault(1, 0)).as("bad destination retried").isPositive();
        assertThat(rec.restarts.getOrDefault(0, 0)).as("good destination untouched").isZero();
    }

    @Test
    void aDestinationThatDropsKeepsItsRealErrorAndIsNotKilledByTheWaitBetweenAttempts() throws Exception {
        // Production, Oct 2026: a YouTube destination dropped once and never came back. While the relay waited
        // to retry, the stream kept queueing for it, overflowed the buffer, and every later attempt was killed
        // within a second as "couldn't keep up", hiding the real reason. The wait here (1.5 s) is long enough
        // for a 100 KB buffer to overflow many times over.
        StreamRunner.Settings slowRetry = new StreamRunner.Settings(Duration.ofMillis(1500), Duration.ofMillis(1500),
                Duration.ofSeconds(60), 3, Duration.ofMinutes(1), 100_000);
        List<String> refused = List.of("sh", "-c", "cat > /dev/null & sleep 0.3; echo 'Server refused the stream' >&2; exit 1");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(9, () -> realtimeEncoder(1), List.of(refused), rec, slowRetry);
        runner.start();
        assertThat(runner.awaitFinished(Duration.ofSeconds(60))).isTrue();

        assertThat(rec.errors).as("errors reported").isNotEmpty()
                .allMatch(e -> e.contains("refused"), "the destination's own error, not a buffer overflow");
    }

    @Test
    void aCrashedEncoderIsRestarted() throws Exception {
        Path out = dir.resolve("restart.flv");
        AtomicInteger attempts = new AtomicInteger();
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(3, () -> attempts.getAndIncrement() == 0
                ? List.of("ffmpeg", "-v", "error", "-i", dir.resolve("missing.mp4").toString(), "-f", "mpegts", "pipe:1")
                : encoder(0), List.of(relayTo(out)), rec, FAST);
        runner.start();
        assertThat(runner.awaitFinished(Duration.ofSeconds(60))).isTrue();

        assertThat(rec.encoderFailures.get()).isEqualTo(1);
        assertThat(rec.finishedReason).isEqualTo("Playlist finished");
        assertThat(rec.failed).isFalse();
        assertThat(probe(out)).contains("h264");
    }

    @Test
    void anEncoderThatKeepsFailingEndsTheStreamAsFailed() throws Exception {
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(4,
                () -> List.of("ffmpeg", "-v", "error", "-i", dir.resolve("missing.mp4").toString(), "-f", "mpegts", "pipe:1"),
                List.of(relayTo(dir.resolve("never.flv"))), rec, FAST);
        runner.start();
        assertThat(runner.awaitFinished(Duration.ofSeconds(30))).isTrue();

        assertThat(rec.failed).isTrue();
        assertThat(rec.finishedReason).startsWith("Stream kept failing");
        assertThat(rec.encoderFailures.get()).isEqualTo(3);
    }

    @Test
    void stopEndsAnEndlessStreamPromptly() throws Exception {
        Path out = dir.resolve("endless.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(5, () -> encoder(-1), List.of(relayTo(out)), rec, FAST);
        runner.start();
        TimeUnit.SECONDS.sleep(3);
        runner.stop("Stopped by user");
        assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();

        assertThat(rec.finishedReason).isEqualTo("Stopped by user");
        assertThat(rec.failed).isFalse();
        assertThat(rec.lastStatus.get(0)).isEqualTo("STOPPED");
        assertThat(probe(out)).contains("h264");
    }

    @Test
    void destinationsCanBeAddedAndRemovedWhileLiveWithoutTouchingTheOthers() throws Exception {
        // DS-5: one encoder throughout; the first destination keeps its connection while a second joins and leaves.
        Path a = dir.resolve("live-a.flv");
        Path b = dir.resolve("live-b.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(6, () -> realtimeEncoder(-1), List.of(relayTo(a)), rec, FAST);
        runner.start();
        TimeUnit.SECONDS.sleep(2);
        runner.setRelays(java.util.Arrays.asList(relayTo(a), relayTo(b)));
        TimeUnit.SECONDS.sleep(4);
        runner.setRelays(java.util.Arrays.asList(relayTo(a), null));
        TimeUnit.SECONDS.sleep(2);
        assertThat(rec.lastStatus.get(1)).isEqualTo("OFF");
        assertThat(rec.lastStatus.get(0)).isEqualTo("LIVE");
        runner.stop("Stopped by user");
        assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();

        assertThat(rec.events).containsExactly("encoder-started-0");
        assertThat(rec.restarts.getOrDefault(0, 0)).as("first destination never reconnected").isZero();
        assertThat(probe(a)).contains("h264");
        assertThat(probe(b)).contains("h264");
    }

    @Test
    void reconnectRedialsEveryDestinationAndLeavesTheEncoderAlone() throws Exception {
        // GL-5: a creator's "reconnect" after a platform glitch.
        Path out = dir.resolve("reconnect.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(7, () -> realtimeEncoder(-1), List.of(relayTo(out)), rec, FAST);
        runner.start();
        TimeUnit.SECONDS.sleep(8); // LIVE: the relay probes ~3 s, then sends steadily for 2 s (FAST)
        runner.reconnectAll();
        TimeUnit.SECONDS.sleep(9);
        assertThat(rec.lastStatus.get(0)).isEqualTo("LIVE");
        runner.stop("Stopped by user");
        assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();

        assertThat(rec.events).containsExactly("encoder-started-0");
        assertThat(rec.statuses).containsSubsequence("0:LIVE", "0:RECONNECTING", "0:LIVE");
        assertThat(rec.restarts.getOrDefault(0, 0)).as("not counted as a failure").isZero();
    }

    // ---------------------------------------------------------------- RE-8: live means the platform is receiving

    @Test
    void aDestinationIsLiveOnlyAfterTenSecondsOfSteadySending() throws Exception {
        Path out = dir.resolve("confirm.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(10, () -> realtimeEncoder(-1), List.of(relayTo(out)), rec, StreamRunner.Settings.defaults());
        runner.start();
        TimeUnit.SECONDS.sleep(9);
        assertThat(rec.lastStatus.get(0)).as("not LIVE before 10 s of sending").isEqualTo("CONNECTING");
        assertThat(rec.bytes.getOrDefault(0, 0L)).as("but already sending").isPositive();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!"LIVE".equals(rec.lastStatus.get(0)) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(200);
        }
        long sentAtLive = rec.bytes.getOrDefault(0, 0L);
        TimeUnit.SECONDS.sleep(2);
        runner.stop("Stopped by user");
        assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();

        assertThat(rec.firstLiveAt.get(0)).as("LIVE once the platform took 10 s of stream").isNotNull().isGreaterThanOrEqualTo(10_000);
        assertThat(rec.errors).isEmpty();
        // FO-3: the byte count keeps growing and matches what reached the output.
        assertThat(rec.bytes.get(0)).isGreaterThan(sentAtLive);
        assertThat(rec.bytes.get(0)).isLessThanOrEqualTo(Files.size(out));
    }

    @Test
    void aRelayThatCannotOpenItsOutputIsNeverLiveAndExplainsWhy() throws Exception {
        // Production: the server URL pasted as the stream key, so every connect failed with
        // "Error opening output ... Input/output error" while the app said Live.
        String pasted = "rtmp://127.0.0.1:1/live2/rtmp://a.rtmp.youtube.com/live2";
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(11, () -> realtimeEncoder(-1),
                List.of(FFmpegCommandBuilder.buildRelayCommand(pasted)), rec, FAST);
        runner.start();
        TimeUnit.SECONDS.sleep(8);
        runner.stop("Stopped by user");
        assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();

        assertThat(rec.statuses).doesNotContain("0:LIVE");
        assertThat(rec.bytes).doesNotContainKey(0);
        assertThat(rec.errors).isNotEmpty().allMatch(e -> e.contains("Error opening output"), "FFmpeg's own reason, not a progress line");
        String reason = rec.errors.getFirst().substring(2);
        assertThat(com.afklive.streamer.service.PlatformErrors.explain(reason, pasted))
                .contains("looks like a server address");
        // Every attempt after the first carries the last reason, so the app never shows a bare "reconnecting".
        assertThat(rec.statuses).contains("0:RECONNECTING");
    }

    @Test
    void aRelayStuckConnectingIsNeverReportedLive() throws Exception {
        // A server that accepts the connection and never answers: the relay process stays up, which the old
        // check ("alive for 3 s") took for LIVE. Nothing is written, so it never is.
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            List<java.net.Socket> held = new CopyOnWriteArrayList<>();
            Thread acceptor = Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    while (!silent.isClosed()) {
                        held.add(silent.accept());
                    }
                } catch (java.io.IOException ignored) {
                    // closed
                }
            });
            Recorder rec = new Recorder();
            StreamRunner runner = new StreamRunner(12, () -> realtimeEncoder(-1),
                    List.of(FFmpegCommandBuilder.buildRelayCommand("rtmp://127.0.0.1:" + silent.getLocalPort() + "/live2/abcd-efgh")),
                    rec, FAST);
            runner.start();
            TimeUnit.SECONDS.sleep(9);
            runner.stop("Stopped by user");
            assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();
            for (java.net.Socket s : held) {
                s.close();
            }
            acceptor.interrupt();

            assertThat(held).as("the relay did connect").isNotEmpty();
            assertThat(rec.statuses).doesNotContain("0:LIVE");
        }
    }

    private static String probe(Path file) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0",
                file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(30, TimeUnit.SECONDS);
        return out;
    }

    private static int exit(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return p.waitFor(60, TimeUnit.SECONDS) ? p.exitValue() : -1;
    }
}
