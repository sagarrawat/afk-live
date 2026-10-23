package com.afklive.streamer.streaming;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-3: the pure decisions of a planned handover. */
class HandoverTest {

    private static final long S = 1_000_000_000L;

    @Test
    void theNewCopyStartsWhereTheOldOneIsNowNotWhereItWasOffered() {
        Handover.OfferedPosition offered = new Handover.OfferedPosition("v-12", 30.0, 2, 1_000_000);
        StudioSpec.Position at = Handover.advance(offered, 1_000_000 + 7_500);
        assertThat(at.itemId()).isEqualTo("v-12");
        assertThat(at.offsetSeconds()).isEqualTo(37.5);
        assertThat(at.loopsCompleted()).isEqualTo(2);
    }

    @Test
    void noPositionOrABrokenClockIsHandledSafely() {
        assertThat(Handover.advance(null, 5)).isNull();
        assertThat(Handover.advance(new Handover.OfferedPosition(null, 3, 0, 0), 5)).isNull();
        // A clock behind the offer never moves backwards; a stale offer isn't extrapolated by hours.
        assertThat(Handover.advance(new Handover.OfferedPosition("v", 10, 0, 10_000), 5_000).offsetSeconds()).isEqualTo(10);
        assertThat(Handover.advance(new Handover.OfferedPosition("v", 10, 0, 0), 3_600_000).offsetSeconds()).isEqualTo(10);
    }

    @Test
    void readyOnlyOnceEveryPublishingDestinationHasBeenLiveLongEnough() {
        Duration after = Duration.ofSeconds(5);
        long now = 100 * S;
        // Encoder not live: never ready.
        assertThat(Handover.ready(0, List.of(0), Map.of(0, now - 60 * S), now, after)).isFalse();
        // One of two YouTube destinations live for 6 s, the other only 2 s.
        assertThat(Handover.ready(now - 30 * S, List.of(0, 2), Map.of(0, now - 6 * S, 2, now - 2 * S), now, after)).isFalse();
        // One not live at all.
        assertThat(Handover.ready(now - 30 * S, List.of(0, 2), Map.of(0, now - 6 * S), now, after)).isFalse();
        assertThat(Handover.ready(now - 30 * S, List.of(0, 2), Map.of(0, now - 6 * S, 2, now - 5 * S), now, after)).isTrue();
        // Destinations that aren't published to during the handover don't count.
        assertThat(Handover.ready(now - 30 * S, List.of(0), Map.of(0, now - 6 * S), now, after)).isTrue();
    }

    @Test
    void withNothingToPublishTheEncoderAloneMustBeLiveLongEnough() {
        Duration after = Duration.ofSeconds(5);
        long now = 100 * S;
        assertThat(Handover.ready(now - 4 * S, List.of(), Map.of(), now, after)).isFalse();
        assertThat(Handover.ready(now - 5 * S, List.of(), Map.of(), now, after)).isTrue();
    }

    @Test
    void defaultsMatchTheSpec() {
        Handover.Settings s = Handover.Settings.defaults();
        assertThat(s.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(s.readyAfter()).isEqualTo(Duration.ofSeconds(10));
        // A claimer is dropped well before the 45 s takeover timeout.
        assertThat(s.beatStale()).isLessThan(com.afklive.streamer.service.StreamService.HEARTBEAT_TIMEOUT);
    }
}
