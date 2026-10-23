package com.afklive.streamer.streaming.studio;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverlayCompositorTest {

    private static final Canvas CANVAS = new Canvas(640, 360, 30, 44100);

    private static byte[] black() {
        byte[] f = new byte[CANVAS.frameBytes()];
        Frame.fill(f, CANVAS, 0x000000);
        return f;
    }

    private static int luma(byte[] f, int x, int y) {
        return f[y * CANVAS.width() + x] & 0xff;
    }

    private static long changedLuma(byte[] a, byte[] b, int x0, int y0, int x1, int y1) {
        long n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (luma(a, x, y) != luma(b, x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void textIsDrawnWhereItIsPlacedAndNowhereElse() {
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(List.of(Overlay.text("t", "Hello AFK", 0.5, 0.5, 0.08, 0xffffff, 0xff2050c0)));
        byte[] before = black();
        byte[] after = black();
        c.blend(after, 0, Instant.now(), Map.of());
        assertThat(changedLuma(before, after, 320, 180, 640, 360)).isGreaterThan(500);
        assertThat(changedLuma(before, after, 0, 0, 316, 360)).isZero();
        assertThat(changedLuma(before, after, 0, 0, 640, 176)).isZero();
    }

    @Test
    void hiddenOverlaysDrawNothing() {
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(List.of(Overlay.text("t", "Hidden", 0.1, 0.1, 0.08, 0xffffff, 0xff000000).withVisible(false)));
        byte[] f = black();
        c.blend(f, 0, Instant.now(), Map.of());
        assertThat(f).isEqualTo(black());
    }

    @Test
    void opacityBlendsWithThePictureUnderneath() {
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        Overlay opaque = new Overlay("o", Overlay.Type.TEXT, true, 0, 0, 0, 0.1, "MM", null, 0xffffff, 0xffffffff, 1.0, null, null, 0);
        Overlay half = new Overlay("o", Overlay.Type.TEXT, true, 0, 0, 0, 0.1, "MM", null, 0xffffff, 0xffffffff, 0.5, null, null, 0);
        byte[] full = black();
        c.setOverlays(List.of(opaque));
        c.blend(full, 0, Instant.now(), Map.of());
        byte[] faded = black();
        c.setOverlays(List.of(half));
        c.blend(faded, 0, Instant.now(), Map.of());
        int fullY = luma(full, 10, 10);
        int fadedY = luma(faded, 10, 10);
        assertThat(fullY).isGreaterThan(200);
        assertThat(fadedY).isBetween(110, 145); // about halfway between black (16) and white (235)
    }

    @Test
    void tickerScrollsWithTime() {
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(List.of(Overlay.ticker("k", "Subscribe for daily lofi beats", 0.85, 0.06, 200)));
        byte[] f0 = black();
        byte[] f30 = black();
        c.blend(f0, 0, Instant.now(), Map.of());
        c.blend(f30, 30, Instant.now(), Map.of());
        int band = (int) (0.85 * 360) + 4;
        assertThat(changedLuma(f0, f30, 0, band, 640, 356)).isGreaterThan(50);
        assertThat(changedLuma(f0, black(), 0, 0, 640, 300)).isZero();
    }

    @Test
    void textTemplatesAndCountdownsResolve() {
        assertThat(OverlayCompositor.resolve("Now playing: {title}", Map.of("title", "Rain"))).isEqualTo("Now playing: Rain");
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        Overlay cd = Overlay.countdown("c", "Starting in", now.plusSeconds(125), 0, 0, 0.05);
        assertThat(OverlayCompositor.countdown(cd, now)).isEqualTo("Starting in 02:05");
        assertThat(OverlayCompositor.countdown(cd, now.plusSeconds(200))).isEqualTo("Starting in");
        Overlay plain = Overlay.countdown("c", null, now.plusSeconds(5), 0, 0, 0.05);
        assertThat(OverlayCompositor.countdown(plain, now.plusSeconds(10))).isEqualTo("Starting soon");
    }

    @Test
    void clockOnlyRerendersWhenTheMinuteChanges() {
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(List.of(Overlay.clock("clk", "Asia/Kolkata", 0.8, 0.05, 0.06)));
        byte[] a = black();
        byte[] b = black();
        Instant t = Instant.parse("2026-10-23T10:00:05Z");
        c.blend(a, 0, t, Map.of());
        c.blend(b, 1, t.plusSeconds(20), Map.of());
        assertThat(a).isEqualTo(b);
        byte[] later = black();
        c.blend(later, 2, t.plusSeconds(120), Map.of());
        assertThat(later).isNotEqualTo(a);
    }

    @Test
    void audioMixingClipsInsteadOfWrapping() {
        byte[] dst = new byte[]{(byte) 0x00, (byte) 0x70}; // 28672
        byte[] src = new byte[]{(byte) 0x00, (byte) 0x70};
        Yuv.mix(dst, 1.0, src, 1.0);
        short s = (short) ((dst[0] & 0xff) | (dst[1] << 8));
        assertThat(s).isEqualTo(Short.MAX_VALUE);
    }

    @Test
    void canvasSizesForLandscapeAndVertical() {
        assertThat(Canvas.of(1080, false, 30)).isEqualTo(new Canvas(1920, 1080, 30, 44100));
        assertThat(Canvas.of(720, true, 30)).isEqualTo(new Canvas(720, 1280, 30, 44100));
        assertThat(Canvas.of(1080, false, 60).samplesPerFrame()).isEqualTo(735);
    }
}
