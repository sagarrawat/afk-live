package com.afklive.streamer.streaming;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FO-8: pool configuration and where a run belongs. */
class WorkerPoolsTest {

    private static StudioSpec studio(String source, List<StudioSpec.OverlaySetting> overlays, StudioSpec.Phone phone) {
        List<StudioSpec.Media> videos = "PLAYLIST".equals(source)
                ? List.of(new StudioSpec.Media("1", "videos/a.mp4", "A", 60, true))
                : List.of();
        return new StudioSpec(720, false, 30, source, videos, "IN_ORDER", 0, List.of(), "IN_ORDER", "KEEP", 1.0, 0.3,
                overlays, List.of(), StudioSpec.LIVE, "UTC", null, phone, false);
    }

    private static StreamSpec spec(StudioSpec s) {
        return StreamSpec.studio("a@example.com", 1L, s, List.of(new StreamSpec.Destination("YouTube", "key")));
    }

    @Test
    void theDefaultsAreOnePoolThatTakesEverything() {
        WorkerPools d = WorkerPools.defaults();
        assertThat(d.pool()).isEqualTo("main");
        assertThat(d.mayReadIngest()).isTrue();
        assertThat(d.preferredFor(WorkerPools.PLAYOUT)).isEmpty();
        assertThat(WorkerPools.of(null, "main", "")).isEqualTo(d);
        assertThat(WorkerPools.of("", "main", null)).isEqualTo(d);
        WorkerPools.Claim c = d.claim(ZonedDateTime.now(ZoneId.of("UTC")), 0.85);
        assertThat(c.mayIngest()).isTrue();
        assertThat(c.preferPlayout()).isEmpty();
        assertThat(c.preferPlaylist()).isEmpty();
        assertThat(c.preferOther()).isEmpty();
        assertThat(c.maxCpuPercent()).isEqualTo(85.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aRemotePoolNeverReadsTheIngestUnlessListed() {
        WorkerPools remote = WorkerPools.of("Playout", "main", "PLAYOUT=playout");
        assertThat(remote.pool()).isEqualTo("playout");
        assertThat(remote.mayReadIngest()).isFalse();
        assertThat(WorkerPools.of("playout", "main, playout", "").mayReadIngest()).isTrue();
        assertThat(WorkerPools.of("playout", "", "").mayReadIngest()).as("empty list: every pool").isTrue();
    }

    @Test
    void preferencesParseAndNeverPointAtTheWorkersOwnPool() {
        WorkerPools main = WorkerPools.of("main", "main", " playout = playout , PLAYLIST=playout, PHONE=playout, junk, OTHER= ");
        assertThat(main.preference()).isEqualTo(Map.of("PLAYOUT", "playout", "PLAYLIST", "playout"));
        assertThat(main.preferredFor(WorkerPools.PLAYOUT)).isEqualTo("playout");
        assertThat(main.preferredFor(WorkerPools.PHONE)).as("phone is decided by the ingest list").isEmpty();
        assertThat(main.preferredFor(WorkerPools.OTHER)).isEmpty();
        WorkerPools playout = WorkerPools.of("playout", "main", "PLAYOUT=playout");
        assertThat(playout.preferredFor(WorkerPools.PLAYOUT)).as("its own pool: claim at once").isEmpty();
        assertThat(playout.ingestPools()).isEqualTo(Set.of("main"));
    }

    @Test
    void poolNamesAreChecked() {
        assertThatThrownBy(() -> WorkerPools.of("bad pool!", "", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placementFollowsWhatTheRunNeeds() {
        assertThat(WorkerPools.placementOf(spec(studio("PLAYLIST", List.of(), null)))).isEqualTo(WorkerPools.PLAYOUT);
        StudioSpec.OverlaySetting clock = new StudioSpec.OverlaySetting("o1", "CLOCK", true, 0.8, 0.05, 0.2, 1.0, null, null, null, "#ffffff", null, 1.0, "UTC", null, 0, null);
        assertThat(WorkerPools.placementOf(spec(studio("PLAYLIST", List.of(clock), null)))).isEqualTo(WorkerPools.PLAYLIST);
        assertThat(WorkerPools.placementOf(spec(studio("PHONE", List.of(), new StudioSpec.Phone("k", null, 0)))))
                .isEqualTo(WorkerPools.PHONE);
        assertThat(WorkerPools.placementOf(spec(studio("PHONE", List.of(), null)))).isEqualTo(WorkerPools.PHONE);
        StreamSpec classic = new StreamSpec("a@example.com", "videos/a.mp4", null, "1.0", -1, null, false, "original", 720,
                false, null, null, List.of(new StreamSpec.Destination("YouTube", "key")));
        assertThat(WorkerPools.placementOf(classic)).isEqualTo(WorkerPools.OTHER);
        assertThat(WorkerPools.placementOf(spec(studio("PLAYLIST", List.of(), null)).toJson())).isEqualTo(WorkerPools.PLAYOUT);
        assertThat(WorkerPools.placementOf("{not json")).isNull();
        assertThat(WorkerPools.placementOf((String) null)).isNull();
    }
}
