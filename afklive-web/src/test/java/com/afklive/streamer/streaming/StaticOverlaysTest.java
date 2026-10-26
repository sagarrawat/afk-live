package com.afklive.streamer.streaming;

import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.streaming.studio.OverlayCompositor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FO-2: which overlays a profile copy can carry, and the identity that keeps copies with different ones apart. */
class StaticOverlaysTest {

    static final Canvas CANVAS = Canvas.of(720, false, 30);

    /** The Free plan's mark exactly as LiveStreamService adds it. */
    static StudioSpec.OverlaySetting watermark() {
        return new StudioSpec.OverlaySetting(OverlayCompositor.WATERMARK_ID, "TEXT", true, 0.8, 0.92, 0, 0.03, "AFK Live",
                null, null, "#ffffff", "#66000000", 0.8, null, null, 0, null);
    }

    static StudioSpec.OverlaySetting logo(double x, double y) {
        return new StudioSpec.OverlaySetting("logo", "IMAGE", true, x, y, 0.15, 0, null, 42L, "media/logo-42.png", null, null,
                0.9, null, null, 0, null);
    }

    static StudioSpec.OverlaySetting text(String id, String text) {
        return new StudioSpec.OverlaySetting(id, "TEXT", true, 0.05, 0.05, 0, 0.045, text, null, null, "#ffffff", "#99000000",
                1, null, null, 0, null);
    }

    static StudioSpec.OverlaySetting of(String type, String id) {
        return new StudioSpec.OverlaySetting(id, type, true, 0.8, 0.05, 0, 0.04, type.equals("COUNTDOWN") ? "Back in {time}" : "News",
                null, null, "#ffffff", "#99000000", 1, "Asia/Kolkata", "2026-12-01T10:00:00Z", 120, null);
    }

    static StudioSpec spec(StudioSpec.OverlaySetting... overlays) {
        return new StudioSpec(720, false, 30, "PLAYLIST", List.of(new StudioSpec.Media("1", "media/a.mp4", "a", 10, true)),
                "IN_ORDER", 0, List.of(), "IN_ORDER", "KEEP", 1.0, 1.0, List.of(overlays), List.of(), StudioSpec.LIVE, "UTC",
                null, null, false, "FIT", 0);
    }

    private static String id(StudioSpec s) {
        return StaticOverlays.identity(StaticOverlays.staticSet(s), CANVAS);
    }

    @Test
    void eligibility() {
        // what the run shows                                       -> why it stays in the studio (null: copy mode)
        Object[][] table = {
                {spec(), null},
                {spec(watermark()), null},
                {spec(logo(0.85, 0.05)), null},
                {spec(watermark(), logo(0.85, 0.05), text("t", "Lo-fi beats 24/7")), null},
                {spec(of("CLOCK", "clock")), "a clock overlay"},
                {spec(watermark(), of("CLOCK", "clock")), "a clock overlay"},
                {spec(of("COUNTDOWN", "cd")), "a countdown overlay"},
                {spec(of("TICKER", "tick")), "a scrolling ticker"},
                {spec(text("np", "Now playing: {title}")), "overlay text that changes ({title})"},
                {spec(text("next", "Up next: {next}")), "overlay text that changes ({next})"},
                {spec(text("track", "{track}")), "overlay text that changes ({track})"},
        };
        for (Object[] row : table) {
            StudioSpec s = (StudioSpec) row[0];
            assertThat(CopyRuns.ineligible(s, StudioSpec.LIVE)).as(String.valueOf(s.overlays())).isEqualTo(row[1]);
        }
    }

    @Test
    void hiddenOverlaysAndOtherScenesOverlaysDontKeepARunInTheStudio() {
        StudioSpec.OverlaySetting hiddenClock = new StudioSpec.OverlaySetting("clock", "CLOCK", false, 0.8, 0.05, 0, 0.04, null,
                null, null, "#ffffff", "#99000000", 1, "UTC", null, 0, null);
        StudioSpec.OverlaySetting brbCountdown = new StudioSpec.OverlaySetting("cd", "COUNTDOWN", true, 0.4, 0.5, 0, 0.06,
                "Back in {time}", null, null, "#ffffff", "#99000000", 1, null, null, 0, StudioSpec.BRB);
        StudioSpec s = spec(watermark(), hiddenClock, brbCountdown);
        assertThat(CopyRuns.ineligible(s, StudioSpec.LIVE)).isNull();
        // Only what shows on LIVE goes into the copies.
        assertThat(StaticOverlays.staticSet(s)).extracting(StudioSpec.OverlaySetting::id).containsExactly(OverlayCompositor.WATERMARK_ID);

        // A clock limited to the LIVE scene counts.
        StudioSpec.OverlaySetting liveClock = new StudioSpec.OverlaySetting("clock", "CLOCK", true, 0.8, 0.05, 0, 0.04, null,
                null, null, "#ffffff", "#99000000", 1, "UTC", null, 0, StudioSpec.LIVE);
        assertThat(CopyRuns.ineligible(spec(liveClock), StudioSpec.LIVE)).isEqualTo("a clock overlay");
    }

    @Test
    void theSetIsInTheStudiosDrawingOrder() {
        StudioSpec.OverlaySetting liveText = new StudioSpec.OverlaySetting("lt", "TEXT", true, 0.1, 0.1, 0, 0.04, "On LIVE only",
                null, null, "#ffffff", "#99000000", 1, null, null, 0, StudioSpec.LIVE);
        // Every-scene overlays first, then the LIVE scene's (Studio.effectiveOverlays); empty text draws nothing.
        StudioSpec s = spec(liveText, logo(0.85, 0.05), text("blank", "  "), watermark());
        assertThat(StaticOverlays.staticSet(s)).extracting(StudioSpec.OverlaySetting::id)
                .containsExactly("logo", OverlayCompositor.WATERMARK_ID, "lt");
    }

    @Test
    void identityFollowsEverythingThatChangesThePicture() {
        String none = id(spec());
        String mark = id(spec(watermark()));
        String logo = id(spec(logo(0.85, 0.05)));
        assertThat(none).isEmpty(); // existing copies (overlay_set '') stay valid for runs without overlays
        assertThat(mark).hasSize(64).isNotEqualTo(logo);
        // Stable: the same settings give the same identity, whatever the overlay's id.
        assertThat(id(spec(watermark()))).isEqualTo(mark);
        StudioSpec.OverlaySetting renamed = new StudioSpec.OverlaySetting("logo-2", "IMAGE", true, 0.85, 0.05, 0.15, 0, null, 42L,
                "media/logo-42.png", null, null, 0.9, null, null, 0, null);
        assertThat(id(spec(renamed))).isEqualTo(logo);

        List<String> variants = new ArrayList<>(List.of(
                id(spec(logo(0.80, 0.05))), // moved
                id(spec(new StudioSpec.OverlaySetting("logo", "IMAGE", true, 0.85, 0.05, 0.25, 0, null, 42L, "media/logo-42.png",
                        null, null, 0.9, null, null, 0, null))), // bigger
                id(spec(new StudioSpec.OverlaySetting("logo", "IMAGE", true, 0.85, 0.05, 0.15, 0, null, 42L, "media/logo-42.png",
                        null, null, 0.5, null, null, 0, null))), // fainter
                id(spec(new StudioSpec.OverlaySetting("logo", "IMAGE", true, 0.85, 0.05, 0.15, 0, null, 43L, "media/logo-43.png",
                        null, null, 0.9, null, null, 0, null))), // another image
                id(spec(watermark(), logo(0.85, 0.05))), // logo plus the mark
                id(spec(logo(0.85, 0.05), watermark())), // the other way round (drawn in that order)
                id(spec(text("t", "AFK Live"))), // same words as the mark, but plain text
                StaticOverlays.identity(StaticOverlays.staticSet(spec(watermark())), Canvas.of(720, true, 30)), // vertical canvas
                StaticOverlays.identity(StaticOverlays.staticSet(spec(watermark())), Canvas.of(1080, false, 30)))); // another size
        variants.add(mark);
        variants.add(logo);
        assertThat(variants).doesNotHaveDuplicates();
    }

    @Test
    void anUpgradeThatDropsTheMarkNeedsOtherCopies() {
        // Free: the mark; after an upgrade the same stream's spec has none, so its copies are the plain ones.
        assertThat(id(spec(watermark(), logo(0.85, 0.05)))).isNotEqualTo(id(spec(logo(0.85, 0.05))));
        assertThat(id(spec(watermark()))).isNotEqualTo(id(spec()));
    }
}
