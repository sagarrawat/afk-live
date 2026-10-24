package com.afklive.streamer.streaming;

import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.service.PlayoutCopies;
import com.afklive.streamer.streaming.studio.PlaylistFeed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FO-2: playlists of profile copies played by stream copy, through the seamless runner, with real FFmpeg. */
class CopyPlayerTest {

    @TempDir
    static Path dir;
    static CopyPlayer.Entry one;
    static CopyPlayer.Entry two;

    static CopyPlayer.Entry profile(String name, String video) throws Exception {
        Path src = dir.resolve(name + ".mp4");
        Process s = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", video, "-f", "lavfi", "-i",
                "sine=f=440:r=48000", "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                src.toString()).redirectErrorStream(true).start();
        s.getInputStream().readAllBytes();
        assertThat(s.waitFor()).isZero();
        Path out = dir.resolve(name + ".ts");
        List<String> cmd = PlayoutCopies.profileCommand(src.toString(), out, 320, 180, 30, "FIT", -2, true, EncoderSettings.DEFAULT_PRESET);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        return new CopyPlayer.Entry("1|" + name, name, out, 3.0);
    }

    @BeforeAll
    static void files() throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        one = profile("one", "testsrc2=size=640x360:rate=60"); // a 60 fps source, prepared at 30
        two = profile("two", "testsrc=size=360x640:rate=30"); // vertical, fitted with bars
    }

    @Test
    void aPlaylistPlaysByStreamCopyAsOneContinuousStream() throws Exception {
        Path out = dir.resolve("copy.flv");
        StreamRunnerTest.Recorder rec = new StreamRunnerTest.Recorder();
        CopyPlayer player = new CopyPlayer(List.of(one, two), PlaylistFeed.Order.IN_ORDER, 2, null, dir, "ffmpeg", new Random(1));
        StreamRunner runner = new StreamRunner(30, player, List.of(StreamRunnerTest.relayTo(out)), rec, StreamRunnerTest.FAST).seamless();
        runner.start();
        Thread.sleep(1500);
        assertThat(player.now().entry()).isEqualTo(one);
        Thread.sleep(3000);
        assertThat(player.now().entry()).isEqualTo(two);
        assertThat(runner.awaitFinished(Duration.ofSeconds(60))).isTrue();

        assertThat(rec.finishedReason).isEqualTo("Playlist finished");
        assertThat(rec.failed).isFalse();
        assertThat(rec.restarts.getOrDefault(0, 0)).isZero();
        // Two passes of two 3 s items.
        TsStitcherTest.assertContinuous(TsStitcherTest.probe(out), 12);
    }

    @Test
    void itResumesFromAPositionAndKeepsCountingPasses() throws Exception {
        Path out = dir.resolve("resume.flv");
        StreamRunnerTest.Recorder rec = new StreamRunnerTest.Recorder();
        // Second item, 1 s in, first pass done already: what's left is 2 s of it, then a final pass of 6 s.
        CopyPlayer player = new CopyPlayer(List.of(one, two), PlaylistFeed.Order.IN_ORDER, 3,
                new StudioSpec.Position(two.itemId(), 1.0, 1), dir, "ffmpeg", new Random(1));
        StreamRunner runner = new StreamRunner(31, player, List.of(StreamRunnerTest.relayTo(out)), rec, StreamRunnerTest.FAST).seamless();
        runner.start();
        Thread.sleep(500);
        StudioSpec.Position at = player.position();
        assertThat(at.itemId()).isEqualTo(two.itemId());
        assertThat(at.loopsCompleted()).isEqualTo(1);
        assertThat(runner.awaitFinished(Duration.ofSeconds(60))).isTrue();
        // Inpoints snap to a keyframe (here the file's start), so up to 2 s more than asked.
        List<TsStitcherTest.Packet> packets = TsStitcherTest.probe(out);
        double span = packets.stream().filter(p -> p.type().equals("video")).mapToDouble(TsStitcherTest.Packet::dts).max().orElse(0)
                - packets.stream().filter(p -> p.type().equals("video")).mapToDouble(TsStitcherTest.Packet::dts).min().orElse(0);
        assertThat(span).isBetween(7.0, 9.5);
    }

    @Test
    void anEndlessPlaylistIsRelaunchedFromWhereItGotToWithoutReconnecting() throws Exception {
        Path out = dir.resolve("endless.flv");
        StreamRunnerTest.Recorder rec = new StreamRunnerTest.Recorder();
        CopyPlayer player = new CopyPlayer(List.of(one, two), PlaylistFeed.Order.IN_ORDER, 0, null, dir, "ffmpeg", new Random(1));
        StreamRunner runner = new StreamRunner(32, player, List.of(StreamRunnerTest.relayTo(out)), rec, StreamRunnerTest.FAST).seamless();
        runner.start();
        Thread.sleep(2000);
        // Simulate the copy process dying (crash, or the end of its scheduled list): the runner relaunches it.
        ProcessHandle.current().descendants().filter(ph -> ph.info().commandLine().orElse("").contains("ffconcat"))
                .findFirst().ifPresent(ProcessHandle::destroy);
        Thread.sleep(5000);
        runner.stop("test over");
        assertThat(runner.awaitFinished(Duration.ofSeconds(30))).isTrue();
        assertThat(rec.restarts.getOrDefault(0, 0)).as("destination stayed connected").isZero();
        assertThat(rec.statuses.stream().filter(x -> x.equals("0:RECONNECTING")).count()).isZero();
        List<TsStitcherTest.Packet> packets = TsStitcherTest.probe(out);
        double prev = -1;
        for (TsStitcherTest.Packet p : packets.stream().filter(p -> p.type().equals("video")).toList()) {
            assertThat(p.dts()).isGreaterThan(prev);
            prev = p.dts();
        }
    }

    @Test
    void theCopyProcessOnlyCopies() {
        List<String> cmd = CopyPlayer.command("ffmpeg", Path.of("x.ffconcat"));
        assertThat(cmd).containsSequence("-c", "copy").contains("-re").doesNotContain("libx264");
        assertThat(FFmpegCommandBuilder.liveEncodeArgs(180, 30, EncoderSettings.DEFAULT)).contains("libx264"); // what the profile copy used
    }
}
