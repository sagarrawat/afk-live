package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlayoutCopiesRulesTest {

    @Test
    void aVertical1080p60ClipOnA720pVerticalStreamIsWorthCopying() {
        assertThat(PlayoutCopies.worthCopying(1080, 1920, 60.0, 600, 720, 1280, 30))
                .isEqualTo(new PlayoutCopies.Target(720, 1280, 30));
    }

    @Test
    void aClipAlreadyAtTheStreamsSizeAndRateIsNot() {
        assertThat(PlayoutCopies.worthCopying(720, 1280, 30.0, 600, 720, 1280, 30)).isNull();
        assertThat(PlayoutCopies.worthCopying(1280, 720, 29.97, 600, 1280, 720, 30)).isNull();
    }

    @Test
    void onlyTheFrameRateCanBeReason() {
        // Same size, twice the frames.
        assertThat(PlayoutCopies.worthCopying(1280, 720, 60.0, 600, 1280, 720, 30)).isNotNull();
    }

    @Test
    void aCopyIsNeverSmallerThanAFillNeeds() {
        // A vertical 1080x1920 clip filling a landscape 1280x720 stream needs its full width: no smaller copy helps.
        assertThat(PlayoutCopies.worthCopying(1080, 1920, 30.0, 600, 1280, 720, 30)).isNull();
        // The everyday case: a 1080p landscape video on a 720p landscape stream.
        assertThat(PlayoutCopies.worthCopying(1920, 1080, 30.0, 600, 1280, 720, 30)).isNotNull();
    }

    @Test
    void unknownSizesAndVeryLongVideosStreamFromTheOriginal() {
        assertThat(PlayoutCopies.worthCopying(null, null, 60.0, 600, 720, 1280, 30)).isNull();
        assertThat(PlayoutCopies.worthCopying(3840, 2160, 60.0, 5 * 3600, 1280, 720, 30)).isNull();
    }

    @Test
    void theCopyKeepsTheStreamRateAndScalesWithoutEnlarging() {
        String cmd = String.join(" ", PlayoutCopies.command("in.mp4", Path.of("out.mp4"), 720, 1280, 30, 60.0));
        assertThat(cmd).contains("fps=30,").contains("min(1\\,max(720/iw\\,1280/ih))").contains("-g 60").contains("-threads 1")
                .contains("-c:a aac");
        // A 25 fps source isn't padded up to 30.
        assertThat(String.join(" ", PlayoutCopies.command("in.mp4", Path.of("out.mp4"), 1280, 720, 30, 25.0))).doesNotContain("fps=");
    }
}
