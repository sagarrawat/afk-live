package com.afklive.streamer.service;

import com.afklive.streamer.repository.DestinationTestRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DestinationTestServiceTest {

    private static DestinationTestService with(List<String> cmd) {
        return new DestinationTestService(mock(DestinationTestRepository.class), mock(StreamJobRepository.class), url -> cmd);
    }

    @Test
    void anAcceptedTestIsReportedAsWorking() {
        assertThat(with(List.of("sh", "-c", "exit 0")).execute("abcd-efgh-ijkl-mnop").ok()).isTrue();
    }

    @Test
    void aRefusedKeyIsExplainedInPlainWords() {
        DestinationTestService.Outcome o = with(List.of("sh", "-c", "echo 'av_interleaved_write_frame(): Broken pipe' >&2; exit 1"))
                .execute("abcd-efgh-ijkl-mnop");
        assertThat(o.ok()).isFalse();
        assertThat(o.message()).contains("stream key");
        assertThat(DestinationTestService.explain("Connection refused")).contains("server address");
    }

    @Test
    void aServerThatNeverAnswersTimesOutInsteadOfHanging() {
        DestinationTestService s = with(List.of("sh", "-c", "echo connecting; sleep 60"));
        s.timeoutSeconds = 1;
        long started = System.nanoTime();
        DestinationTestService.Outcome o = s.execute("abcd-efgh-ijkl-mnop");
        assertThat(o.ok()).isFalse();
        assertThat(o.message()).contains("didn't respond");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void aTestStopsAtOnceWhenAStreamGoesLiveOnItsKey() {
        // GL-1: a test must never compete with a live run for the same key.
        DestinationTestService s = with(List.of("sh", "-c", "sleep 30"));
        java.util.concurrent.atomic.AtomicInteger looks = new java.util.concurrent.atomic.AtomicInteger();
        long started = System.nanoTime();
        DestinationTestService.Outcome o = s.execute("abcd-efgh-ijkl-mnop", () -> looks.incrementAndGet() >= 2);
        assertThat(o.ok()).isFalse();
        assertThat(o.message()).isEqualTo(DestinationTestService.WENT_LIVE);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void errorsAreExplainedWithThePlatformsName() {
        String broken = "[rtmp @ 0x55] av_interleaved_write_frame(): Broken pipe";
        assertThat(DestinationTestService.explain(broken, "rtmp://live.twitch.tv/app/live_1"))
                .isEqualTo("Twitch refused the stream key. Copy the key again from Twitch and save it.");
        assertThat(DestinationTestService.explain(broken, "abcd-efgh")).startsWith("YouTube refused the stream key");
        assertThat(DestinationTestService.explain("exit code 1", "rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2"))
                .as("a server address pasted as the key, whatever FFmpeg said").contains("second server address");
        assertThat(DestinationTestService.explain(com.afklive.streamer.streaming.StreamRunner.STALLED, "abcd-efgh"))
                .isEqualTo("YouTube stopped taking the stream. We keep reconnecting.");
        assertThat(DestinationTestService.explain(com.afklive.streamer.streaming.StreamRunner.FELL_BEHIND, "abcd-efgh"))
                .isEqualTo("The connection to YouTube was too slow to keep up, so we reconnected.");
    }

    @Test
    void theTestCommandMeetsYouTubesIngestBasicsAndTimesOut() {
        List<String> cmd = DestinationTestService.ffmpegTest("abcd-efgh-ijkl-mnop");
        assertThat(cmd).containsSequence("-g", "60").containsSequence("-ar", "44100").contains("flv").contains("-rw_timeout")
                .last().asString().startsWith("rtmp://a.rtmp.youtube.com");
    }

    @Test
    void theRealTestCommandRunsWithFFmpeg() throws Exception {
        // Before the fix, -rw_timeout sat before the test-picture input and FFmpeg refused every test
        // ("Option rw_timeout not found"), on production too.
        org.junit.jupiter.api.Assumptions.assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("dest-test").resolve("out.flv");
        List<String> cmd = new java.util.ArrayList<>(DestinationTestService.ffmpegTest("file://" + out));
        cmd.remove("-re"); // as fast as possible here
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        assertThat(java.nio.file.Files.size(out)).isPositive();
    }
}
