package com.afklive.streamer.streaming.studio;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.streaming.StreamRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.afklive.streamer.streaming.studio.StudioFixtures.CANVAS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path with real FFmpeg: sources → studio clock → one encoder → relay → file.
 * The point of each test: the encoder starts exactly once while the content changes underneath it,
 * and the output has no holes.
 */
class StudioTest {

    static final StreamRunner.Settings FAST = new StreamRunner.Settings(
            Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofSeconds(60), 3, Duration.ofMinutes(1), 4L * 1024 * 1024);

    @TempDir
    static Path dir;
    static PlaylistFeed.Item a;
    static PlaylistFeed.Item b;
    static PlaylistFeed.Item c;

    @BeforeAll
    static void media() throws Exception {
        StudioFixtures.requireFfmpeg();
        a = new PlaylistFeed.Item("a", "Clip A", StudioFixtures.clip(dir, "sa", 320, 240, 2, 440), true, 2);
        b = new PlaylistFeed.Item("b", "Clip B", StudioFixtures.clip(dir, "sb", 640, 360, 2, 880), true, 2);
        c = new PlaylistFeed.Item("c", "Clip C", StudioFixtures.clip(dir, "sc", 320, 180, 2, 0), false, 2);
    }

    /** Records what the runner reports. */
    static final class Recorder implements StreamRunner.Listener {
        final AtomicInteger encoderStarts = new AtomicInteger();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile String reason;

        @Override
        public void onEncoderStarted(int restarts) {
            encoderStarts.incrementAndGet();
        }

        @Override
        public void onFinished(String why, boolean failed) {
            reason = why;
            finished.countDown();
        }
    }

    private static List<List<String>> relayTo(Path file) {
        return List.of(FFmpegCommandBuilder.buildRelayCommand("file://" + file));
    }

    @Test
    void aPlaylistPlaysThroughOneEncoderWithNoGapsAndOverlayChangesDontRestartIt() throws Exception {
        PlaylistFeed playlist = new PlaylistFeed(CANVAS, List.of(a, b, c), PlaylistFeed.Order.IN_ORDER, 1, null,
                StudioFixtures.factory(dir), false, new Random(1));
        Overlay title = Overlay.text("np", "Now playing: {title}", 0.05, 0.05, 0.08, 0xffffff, 0x99000000);
        Studio studio = new Studio(CANVAS, dir.resolve("w1"), "ffmpeg",
                List.of(new Studio.Scene("main", playlist, null, 1.0, 0, List.of(title), null)),
                "main", List.of(Overlay.clock("clock", "Asia/Kolkata", 0.8, 0.05, 0.06)), 0, null);
        Path out = dir.resolve("playlist.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(1, studio, relayTo(out), rec, FAST);
        try {
            studio.start();
            runner.start();
            Thread.sleep(2500);
            studio.setOverlayVisible("clock", false);
            studio.setOverlayVisible("np", false);
            Thread.sleep(500);
            studio.setOverlayVisible("np", true);

            assertThat(rec.finished.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(rec.reason).isEqualTo("Playlist finished");
            assertThat(rec.encoderStarts.get()).isEqualTo(1);
            assertThat(runner.awaitFinished(Duration.ofSeconds(20))).isTrue();
        } finally {
            runner.stop("test");
            studio.close();
        }
        // Three 2-second clips back to back: about 6 s of continuous video and audio.
        assertThat(StudioFixtures.duration(out, "v:0")).isBetween(5.5, 6.6);
        assertThat(StudioFixtures.duration(out, "a:0")).isBetween(5.5, 6.6);
        assertThat(StudioFixtures.largestVideoGap(out)).isLessThan(0.1);
    }

    @Test
    void aLiveInputThatDropsFallsBackToBrbAndReturnsWithoutTheOutputStopping() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        DecoderSource input = new DecoderSource(CANVAS, new DecoderSource.Options(
                List.of("-f", "mpegts", "-i", "tcp://127.0.0.1:" + port + "?listen=1"), true, true, true, 0),
                dir, "ffmpeg", "phone");
        StillFeed brbPicture = StillFeed.color(CANVAS, 0x2050c0);
        PlaylistFeed brbMusic = new PlaylistFeed(CANVAS,
                List.of(new PlaylistFeed.Item("m", "BRB music", StudioFixtures.audio(dir, "brb", 3, 220), true, 3)),
                PlaylistFeed.Order.IN_ORDER, 0, null, StudioFixtures.factory(dir), true, new Random(1));
        List<Boolean> switches = new CopyOnWriteArrayList<>();
        List<Long> switchTimes = new CopyOnWriteArrayList<>();
        Studio studio = new Studio(CANVAS, dir.resolve("w2"), "ffmpeg", List.of(
                new Studio.Scene("live", new LiveFeed(CANVAS, input.start(), brbPicture, LiveFeed.Settings.defaults(), null),
                        null, 1.0, 0, List.of(), "brb"),
                new Studio.Scene("brb", brbPicture, brbMusic, 0, 0.8,
                        List.of(Overlay.text("brb", "Be right back", 0.3, 0.4, 0.1, 0xffffff, 0)), null)),
                "live", List.of(), 0, new Studio.Listener() {
                    @Override
                    public void onInputSwitch(boolean live) {
                        switches.add(live);
                        switchTimes.add(System.nanoTime());
                    }
                });
        Path out = dir.resolve("live.flv");
        Recorder rec = new Recorder();
        StreamRunner runner = new StreamRunner(2, studio, relayTo(out), rec, FAST);
        Process phone = null;
        long started;
        long stopped;
        try {
            studio.start();
            runner.start();
            started = System.nanoTime();
            phone = phone(port);
            waitFor(() -> switches.contains(true), 15_000);
            assertThat(studio.status().scene()).isEqualTo("live");

            // Signal lost: the phone stops sending.
            long cut = System.nanoTime();
            phone.destroyForcibly();
            waitFor(() -> switches.getLast() == Boolean.FALSE, 5_000);
            assertThat((switchTimes.getLast() - cut) / 1_000_000).isLessThan(2_500);
            assertThat(studio.status().scene()).isEqualTo("brb");
            Thread.sleep(1500);

            // Back: the decoder is listening again within a second; once the feed is steady, live again.
            phone = phone(port);
            long back = System.nanoTime();
            waitFor(() -> switches.getLast() == Boolean.TRUE, 15_000);
            assertThat((switchTimes.getLast() - back) / 1_000_000).isLessThan(6_000);
            Thread.sleep(1000);
            stopped = System.nanoTime();
            runner.stop("Stopped by user");
            assertThat(rec.finished.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (phone != null) {
                phone.destroyForcibly();
            }
            runner.stop("test");
            studio.close();
        }
        assertThat(rec.encoderStarts.get()).isEqualTo(1);
        double wall = (stopped - started) / 1e9;
        // The output ran the whole time, through the drop: no holes, length close to wall time.
        assertThat(StudioFixtures.duration(out, "v:0")).isBetween(wall - 2.0, wall + 0.5);
        assertThat(StudioFixtures.largestVideoGap(out)).isLessThan(0.1);
    }

    /** A "phone": a real-time test pattern with sound, sent as MPEG-TS over TCP. */
    private static Process phone(int port) throws Exception {
        Process p = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-f", "lavfi", "-i", "sine=f=660:r=44100",
                    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "30", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-f", "mpegts", "tcp://127.0.0.1:" + port)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(700, TimeUnit.MILLISECONDS)) {
                return p; // connected and sending
            }
            Thread.sleep(300); // the studio's decoder isn't listening yet
        }
        return p;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition not met within " + millis + " ms");
    }
}
