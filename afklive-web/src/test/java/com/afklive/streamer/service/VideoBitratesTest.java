package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FO-4: per-plan live video bitrates from configuration, their floors, and what they put on the encoder's command line. */
class VideoBitratesTest {

    private static VideoBitrates of(Map<String, String> props) {
        return VideoBitrates.fromConfig(props::get);
    }

    @Test
    void withNothingSetEveryPlanGetsExactlyTheLadderTheEncoderUsedBefore() {
        VideoBitrates b = of(Map.of());
        assertThat(b.isBuiltIn()).isTrue();
        for (PlanType plan : PlanType.values()) {
            // 720p 4 Mbps, 1080p 6, 1440p 9, 2160p 15; anything below 1080 is the 720 class; 60 fps x1.5.
            assertThat(b.videoKbps(plan, 480, 30)).isEqualTo(4000);
            assertThat(b.videoKbps(plan, 720, 30)).isEqualTo(4000);
            assertThat(b.videoKbps(plan, 720, 60)).isEqualTo(6000);
            assertThat(b.videoKbps(plan, 1080, 30)).isEqualTo(6000);
            assertThat(b.videoKbps(plan, 1080, 60)).isEqualTo(9000);
            assertThat(b.videoKbps(plan, 1440, 30)).isEqualTo(9000);
            assertThat(b.videoKbps(plan, 2160, 30)).isEqualTo(15000);
            assertThat(b.videoKbps(plan, 2160, 60)).isEqualTo(22500);
            for (int side : List.of(180, 360, 720, 1080, 1440, 2160)) {
                for (int fps : List.of(30, 60)) {
                    assertThat(b.videoKbps(plan, side, fps)).isEqualTo(FFmpegCommandBuilder.liveVideoKbps(side, fps));
                }
            }
        }
        // And the encoder's arguments are the same, byte for byte, as without a recorded bitrate.
        assertThat(FFmpegCommandBuilder.liveEncodeArgsAt(b.videoKbps(PlanType.FREE, 720, 30), 30, EncoderSettings.DEFAULT))
                .isEqualTo(FFmpegCommandBuilder.liveEncodeArgs(720, 30, EncoderSettings.DEFAULT));
    }

    @Test
    void aPlansOwnValueWinsThenDefaultThenTheBuiltInLadder() {
        VideoBitrates b = of(Map.of(
                "app.streaming.bitrate.free.720", "3000",
                "app.streaming.bitrate.default.720", "3500",
                "app.streaming.bitrate.pro.1080", "7000k"));
        assertThat(b.isBuiltIn()).isFalse();
        assertThat(b.videoKbps(PlanType.FREE, 720, 30)).isEqualTo(3000);
        assertThat(b.videoKbps(PlanType.ESSENTIALS, 720, 30)).isEqualTo(3500);
        assertThat(b.videoKbps(PlanType.PRO, 720, 60)).isEqualTo(5250);
        assertThat(b.videoKbps(PlanType.PRO, 1080, 30)).isEqualTo(7000);
        assertThat(b.videoKbps(PlanType.AGENCY, 1080, 30)).isEqualTo(6000);
        assertThat(b.videoKbps(PlanType.FREE, 1080, 30)).isEqualTo(6000);
        assertThat(b.describe()).contains("FREE 720=3000 1080=6000").contains("PRO 720=3500 1080=7000");
    }

    @Test
    void valuesBelowYouTubesGuidanceAreRaisedToTheFloorAndTyposIgnored() {
        Map<String, String> p = new HashMap<>();
        p.put("app.streaming.bitrate.free.720", "1000");       // below 1.5 Mbps
        p.put("app.streaming.bitrate.essentials.720", "1500"); // exactly the floor: kept
        p.put("app.streaming.bitrate.free.1080", "4500");      // below 5 Mbps (repo rule for 1080p30)
        p.put("app.streaming.bitrate.free.1440", "3000");
        p.put("app.streaming.bitrate.free.2160", "9000");
        p.put("app.streaming.bitrate.pro.720", "lots");        // not a number: ignored
        p.put("app.streaming.bitrate.agency.720", "400000");   // an extra zero or two: capped
        p.put("app.streaming.bitrate.default.1080", " ");      // blank: unset
        VideoBitrates b = of(p);
        assertThat(b.baseKbps(PlanType.FREE, 720)).isEqualTo(1500);
        assertThat(b.baseKbps(PlanType.ESSENTIALS, 720)).isEqualTo(1500);
        assertThat(b.baseKbps(PlanType.FREE, 1080)).isEqualTo(5000);
        assertThat(b.baseKbps(PlanType.FREE, 1440)).isEqualTo(6000);
        assertThat(b.baseKbps(PlanType.FREE, 2160)).isEqualTo(13000);
        assertThat(b.baseKbps(PlanType.PRO, 720)).isEqualTo(4000);
        assertThat(b.baseKbps(PlanType.AGENCY, 720)).isEqualTo(VideoBitrates.MAX_KBPS);
        assertThat(b.baseKbps(PlanType.PRO, 1080)).isEqualTo(6000);
        // Whatever is configured, nothing goes out below the floors, at 30 or 60 fps.
        for (PlanType plan : PlanType.values()) {
            assertThat(b.videoKbps(plan, 720, 30)).isGreaterThanOrEqualTo(1500);
            assertThat(b.videoKbps(plan, 720, 60)).isGreaterThanOrEqualTo(2250);
            assertThat(b.videoKbps(plan, 1080, 30)).isGreaterThanOrEqualTo(5000);
        }
    }

    @Test
    void environmentVariablesSetThemLikeEveryOtherSetting() {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("test-env", Map.of(
                "APP_STREAMING_BITRATE_FREE_720", "2500",
                "APP_STREAMING_BITRATE_DEFAULT_1080", "5500")));
        VideoBitrates b = VideoBitrates.fromConfig(env::getProperty);
        assertThat(b.videoKbps(PlanType.FREE, 720, 30)).isEqualTo(2500);
        assertThat(b.videoKbps(PlanType.ESSENTIALS, 720, 30)).isEqualTo(4000);
        assertThat(b.videoKbps(PlanType.PRO, 1080, 30)).isEqualTo(5500);
    }

    @Test
    void theEncoderSendsThePlansBitrateAsConstantBitrate() {
        VideoBitrates b = of(Map.of("app.streaming.bitrate.free.720", "2500", "app.streaming.bitrate.pro.720", "3500"));
        assertThat(FFmpegCommandBuilder.liveEncodeArgsAt(b.videoKbps(PlanType.FREE, 720, 30), 30, EncoderSettings.DEFAULT))
                .containsSequence("-b:v", "2500k", "-minrate", "2500k", "-maxrate", "2500k", "-bufsize", "5000k")
                .containsSequence("-x264-params", "nal-hrd=cbr:force-cfr=1")
                .containsSequence("-g", "60", "-keyint_min", "60", "-sc_threshold", "0")
                .containsSequence("-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2");
        assertThat(FFmpegCommandBuilder.liveEncodeArgsAt(b.videoKbps(PlanType.PRO, 720, 60), 60, EncoderSettings.DEFAULT))
                .containsSequence("-b:v", "5250k", "-minrate", "5250k", "-maxrate", "5250k", "-bufsize", "10500k")
                .containsSequence("-g", "120", "-keyint_min", "120");
        assertThat(FFmpegCommandBuilder.liveEncodeArgsAt(b.videoKbps(PlanType.AGENCY, 720, 30), 30, EncoderSettings.DEFAULT))
                .containsSequence("-b:v", "4000k");
    }

    @Test
    void aRunWithoutARecordedBitrateEncodesAtTheBuiltInLadder() {
        StudioSpec legacy = StudioSpec.parse("{\"height\":720,\"vertical\":true,\"fps\":30,\"source\":\"PLAYLIST\"}", StudioSpec.class);
        assertThat(legacy.videoKbps()).isNull();
        assertThat(legacy.effectiveVideoKbps()).isEqualTo(4000);
        StudioSpec recorded = legacy.withVideoKbps(2500);
        assertThat(recorded.effectiveVideoKbps()).isEqualTo(2500);
        // It survives the JSON round trip the run's spec goes through, and other rebuilds keep it.
        StudioSpec back = StudioSpec.parse(StudioSpec.json(recorded), StudioSpec.class);
        assertThat(back.videoKbps()).isEqualTo(2500);
        assertThat(back.withFinishAfterPass().videoKbps()).isEqualTo(2500);
        assertThat(StudioSpec.parse("{\"height\":1080,\"fps\":60,\"videoKbps\":0}", StudioSpec.class).effectiveVideoKbps()).isEqualTo(9000);
    }
}
