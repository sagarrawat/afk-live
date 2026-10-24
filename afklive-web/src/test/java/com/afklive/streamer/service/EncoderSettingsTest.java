package com.afklive.streamer.service;

import com.afklive.streamer.config.EncoderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncoderSettingsTest {

    @Test
    void theDefaultIsSuperfastWithThreadsLeftToX264() {
        assertThat(EncoderSettings.fromConfig(null, null)).isEqualTo(new EncoderSettings("superfast", 0));
        assertThat(EncoderSettings.fromConfig("", "")).isEqualTo(EncoderSettings.DEFAULT);
        assertThat(new EncoderConfig().encoderSettings("superfast", "0")).isEqualTo(EncoderSettings.DEFAULT);
    }

    @Test
    void everyAllowedPresetIsTakenWhateverItsCaseOrSpaces() {
        for (String p : List.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium")) {
            assertThat(EncoderSettings.fromConfig(" " + p.toUpperCase() + " ", "3")).isEqualTo(new EncoderSettings(p, 3));
        }
    }

    @Test
    void aTypoOrASlowPresetFallsBackToTheDefaultInsteadOfStoppingTheWorker() {
        assertThat(EncoderSettings.fromConfig("superfst", "0").preset()).isEqualTo("superfast");
        assertThat(EncoderSettings.fromConfig("slow", "0").preset()).isEqualTo("superfast");
        assertThat(EncoderSettings.fromConfig("placebo", "0").preset()).isEqualTo("superfast");
        assertThat(EncoderSettings.fromConfig("veryfast -x264opts evil", "0").preset()).isEqualTo("superfast");
    }

    @Test
    void badThreadCountsMeanAuto() {
        assertThat(EncoderSettings.fromConfig("veryfast", "-1")).isEqualTo(new EncoderSettings("veryfast", 0));
        assertThat(EncoderSettings.fromConfig("veryfast", "99")).isEqualTo(new EncoderSettings("veryfast", 0));
        assertThat(EncoderSettings.fromConfig("veryfast", "two")).isEqualTo(new EncoderSettings("veryfast", 0));
        assertThat(EncoderSettings.fromConfig("veryfast", "16").threads()).isEqualTo(16);
    }

    @Test
    void theConstructorItselfRefusesWhatConfigWouldHaveCorrected() {
        assertThatThrownBy(() -> new EncoderSettings("slow", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncoderSettings(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncoderSettings("superfast", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void superfastAndVeryfastAreOneFamilySoOldProfileCopiesStayValid() {
        assertThat(EncoderSettings.DEFAULT.compatibleWith("veryfast")).isTrue();
        assertThat(EncoderSettings.DEFAULT.compatibleWith(null)).as("a copy made before presets were recorded").isTrue();
        assertThat(EncoderSettings.DEFAULT.compatibleWith("superfast")).isTrue();
        assertThat(EncoderSettings.DEFAULT.compatibleWith("ultrafast")).as("no B-frames, Constrained Baseline").isFalse();
        assertThat(EncoderSettings.DEFAULT.compatibleWith("medium")).as("more reference frames").isFalse();
        assertThat(new EncoderSettings("ultrafast", 0).compatibleWith(null)).isFalse();
    }
}
