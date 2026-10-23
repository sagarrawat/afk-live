package com.afklive.streamer.service;

import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.repository.StreamJobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DestinationTestServiceTest {

    private static StreamDestination dest(String key) {
        StreamDestination d = new StreamDestination();
        d.setStreamKey(key);
        return d;
    }

    @Test
    void anAcceptedTestIsReportedAsWorking() {
        StreamJobRepository jobs = mock(StreamJobRepository.class);
        when(jobs.findAllByUserEmailAndIsLiveTrue("a@b.c")).thenReturn(List.of());
        DestinationTestService s = new DestinationTestService(jobs, url -> List.of("sh", "-c", "exit 0"));
        assertThat(s.test("a@b.c", dest("abcd-efgh-ijkl-mnop")).ok()).isTrue();
    }

    @Test
    void aRefusedKeyIsExplainedInPlainWords() {
        StreamJobRepository jobs = mock(StreamJobRepository.class);
        DestinationTestService s = new DestinationTestService(jobs,
                url -> List.of("sh", "-c", "echo 'av_interleaved_write_frame(): Broken pipe' >&2; exit 1"));
        DestinationTestService.Result r = s.test("a@b.c", dest("abcd-efgh-ijkl-mnop"));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("stream key");
        assertThat(DestinationTestService.explain("Connection refused")).contains("server address");
    }

    @Test
    void privateAddressesAreNeverTested() {
        DestinationTestService s = new DestinationTestService(mock(StreamJobRepository.class), url -> List.of("true"));
        assertThatThrownBy(() -> s.test("a@b.c", dest("rtmp://127.0.0.1/live/x"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void theTestCommandMeetsYouTubesIngestBasics() {
        List<String> cmd = DestinationTestService.ffmpegTest("abcd-efgh-ijkl-mnop");
        assertThat(cmd).containsSequence("-g", "60").containsSequence("-ar", "44100").contains("flv")
                .last().asString().startsWith("rtmp://a.rtmp.youtube.com");
    }
}
