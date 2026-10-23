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

    static final StreamRunner.Settings FAST = new StreamRunner.Settings(
            Duration.ofMillis(100), Duration.ofMillis(400), Duration.ofSeconds(60), 3, Duration.ofMinutes(1), 4L * 1024 * 1024);

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
        return FFmpegCommandBuilder.buildRelayCommand("file://" + file);
    }

    static class Recorder implements StreamRunner.Listener {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final Map<Integer, String> lastStatus = new ConcurrentHashMap<>();
        final Map<Integer, Integer> restarts = new ConcurrentHashMap<>();
        final AtomicInteger progress = new AtomicInteger();
        final AtomicInteger encoderFailures = new AtomicInteger();
        volatile String finishedReason;
        volatile boolean failed;

        @Override public void onEncoderStarted(int r) { events.add("encoder-started-" + r); }
        @Override public void onProgress(StreamRunner.Progress p) { progress.incrementAndGet(); }
        @Override public void onDestination(int pos, String status, int r, String error) {
            lastStatus.put(pos, status);
            restarts.merge(pos, r, Math::max);
        }
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
