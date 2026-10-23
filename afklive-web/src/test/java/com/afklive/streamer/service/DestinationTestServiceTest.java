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
    void theTestCommandMeetsYouTubesIngestBasicsAndTimesOut() {
        List<String> cmd = DestinationTestService.ffmpegTest("abcd-efgh-ijkl-mnop");
        assertThat(cmd).containsSequence("-g", "60").containsSequence("-ar", "44100").contains("flv").contains("-rw_timeout")
                .last().asString().startsWith("rtmp://a.rtmp.youtube.com");
    }
}
