package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AU-5: every file is brought to -14 LUFS, within 12 dB either way. */
class LoudnessGainTest {

    @Test
    void gainBringsFilesToTheTarget() {
        assertThat(LiveStreamService.gainFor(-20.0)).isEqualTo(6.0);
        assertThat(LiveStreamService.gainFor(-9.5)).isEqualTo(-4.5);
        assertThat(LiveStreamService.gainFor(-40.0)).isEqualTo(12.0);
        assertThat(LiveStreamService.gainFor(0.0)).isEqualTo(-12.0);
        assertThat(LiveStreamService.gainFor(null)).isZero(); // not measured: play as is
    }
}
