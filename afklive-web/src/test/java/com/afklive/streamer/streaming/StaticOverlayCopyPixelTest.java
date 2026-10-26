package com.afklive.streamer.streaming;

import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.PlayoutCopies;
import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.streaming.studio.DecoderSource;
import com.afklive.streamer.streaming.studio.Overlay;
import com.afklive.streamer.streaming.studio.OverlayCompositor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FO-2: a copy with static overlays looks like the studio, so a switch between them shows no jump. The
 * studio's frame (decoded with its own chain, overlays blended by {@link OverlayCompositor#blend}) is
 * compared pixel by pixel with the profile copy's (the same chain, then FFmpeg's overlay filter with the
 * layer the compositor drew), before encoding and after.
 */
class StaticOverlayCopyPixelTest {

    @TempDir
    static Path dir;
    static Path source;
    static Path logo;
    static final Canvas CANVAS = new Canvas(640, 360, 30, 44100);
    static final int FRAME = 20;

    @BeforeAll
    static void media() throws Exception {
        Process v = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0, "ffmpeg not installed");
        source = dir.resolve("src.mp4");
        run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi", "-i",
                "sine=f=440:r=44100", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", source.toString()));
        // A logo with soft edges and partial transparency: the hard case for matching alpha and colour.
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 182, 39, 255));
        g.fillOval(4, 4, 72, 72);
        g.setColor(new Color(40, 120, 255, 160));
        g.fillRoundRect(50, 20, 66, 50, 16, 16);
        g.dispose();
        logo = dir.resolve("logo.png");
        ImageIO.write(img, "png", logo.toFile());
    }

    static List<StudioSpec.OverlaySetting> overlays() {
        return List.of(
                new StudioSpec.OverlaySetting("logo", "IMAGE", true, 0.04, 0.05, 0.2, 0, null, 7L, "logo-key", null, null, 0.85,
                        null, null, 0, null),
                new StudioSpec.OverlaySetting("title", "TEXT", true, 0.3, 0.06, 0, 0.05, "Lo-fi beats 24/7", null, null, "#ffffff",
                        "#99000000", 1, null, null, 0, null),
                // overlapping the title: layers stack in the studio's order
                new StudioSpec.OverlaySetting("badge", "TEXT", true, 0.45, 0.09, 0, 0.04, "हिंदी गाने", null, null, "#ffd27a",
                        "#cc202020", 0.9, null, null, 0, null),
                StaticOverlaysTest.watermark());
    }

    @Test
    void theCopysOverlayFilterGivesTheStudiosPictureBeforeEncoding() throws Exception {
        byte[] base = decodeFrame(List.of("-i", source.toString()), PlayoutCopies.profileVideoGraph(CANVAS, DecoderSource.Fit.FIT, false));
        byte[] studio = studioFrame(base);

        Path layer = dir.resolve("layer.yuva");
        StaticOverlays.writeLayer(overlays(), k -> logo, CANVAS, layer);
        List<String> inputs = new ArrayList<>(List.of("-i", source.toString()));
        inputs.addAll(StaticOverlays.layerInput(layer, CANVAS));
        byte[] copy = decodeFrame(inputs, PlayoutCopies.profileVideoGraph(CANVAS, DecoderSource.Fit.FIT, true));

        // The overlays are really there (the studio's frame differs from the bare picture where they are)...
        int[] logoBox = box(0.04, 0.05, 0.2, 0.2);
        int[] markBox = box(0.8, 0.92, 0.2, 0.08);
        assertThat(meanDiff(base, studio, logoBox)).isGreaterThan(20);
        assertThat(meanDiff(base, studio, markBox)).isGreaterThan(10);
        // ...and the copy matches the studio to a rounding step, everywhere (planes Y, U and V). Measured: at most 1.
        assertThat(maxDiff(studio, copy)).as("largest difference, any plane").isLessThanOrEqualTo(2);
        assertThat(meanDiff(studio, copy, logoBox)).isLessThan(0.3);
        assertThat(meanDiff(studio, copy, markBox)).isLessThan(0.3);
        assertThat(meanDiff(studio, copy, box(0.3, 0.06, 0.5, 0.12))).isLessThan(0.3);
    }

    @Test
    void anEncodedCopyMatchesTheStudioAtTheOverlays() throws Exception {
        byte[] base = decodeFrame(List.of("-i", source.toString()), PlayoutCopies.profileVideoGraph(CANVAS, DecoderSource.Fit.FIT, false));
        byte[] studio = studioFrame(base);

        Path layer = dir.resolve("layer-enc.yuva");
        StaticOverlays.writeLayer(overlays(), k -> logo, CANVAS, layer);
        Path withOverlays = dir.resolve("copy-overlays.ts");
        run(PlayoutCopies.profileCommand(source.toString(), withOverlays, CANVAS.width(), CANVAS.height(), CANVAS.fps(), "FIT", 0,
                true, EncoderSettings.DEFAULT_PRESET, layer));
        Path plain = dir.resolve("copy-plain.ts");
        run(PlayoutCopies.profileCommand(source.toString(), plain, CANVAS.width(), CANVAS.height(), CANVAS.fps(), "FIT", 0,
                true, EncoderSettings.DEFAULT_PRESET));

        byte[] copy = decodeFrame(List.of("-i", withOverlays.toString()), "[0:v:0]format=yuv420p[v]");
        byte[] bare = decodeFrame(List.of("-i", plain.toString()), "[0:v:0]format=yuv420p[v]");
        for (int[] b : List.of(box(0.04, 0.05, 0.2, 0.2), box(0.8, 0.92, 0.2, 0.08))) {
            double withThem = meanDiff(studio, copy, b);
            double without = meanDiff(studio, bare, b);
            // Within encoding noise of the studio's picture (measured ~0.12), far from a copy without them (15-40).
            assertThat(withThem).as("copy vs studio at %s", List.of(b[0], b[1], b[2], b[3])).isLessThan(2);
            assertThat(without).isGreaterThan(withThem * 5);
        }
        // Same tracks and format as any profile copy, so copy mode can stitch it.
        String streams = runOut(List.of("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height,sample_rate",
                "-of", "csv=p=0", withOverlays.toString()));
        assertThat(streams).contains("h264,640,360").contains("aac,44100");
    }

    @Test
    void aSingleOverlaysLayerIsExactlyItsStudioPatch() throws Exception {
        // One overlay over nothing: the layer is the patch itself, so the only difference left is FFmpeg's rounding.
        byte[] base = decodeFrame(List.of("-i", source.toString()), PlayoutCopies.profileVideoGraph(CANVAS, DecoderSource.Fit.FIT, false));
        List<StudioSpec.OverlaySetting> only = List.of(StaticOverlaysTest.watermark());
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(List.of(StudioBuilder.overlay(only.getFirst(), null)));
        byte[] studio = base.clone();
        c.blend(studio, FRAME, Instant.now(), Map.of());
        Path layer = dir.resolve("mark.yuva");
        StaticOverlays.writeLayer(only, k -> null, CANVAS, layer);
        List<String> inputs = new ArrayList<>(List.of("-i", source.toString()));
        inputs.addAll(StaticOverlays.layerInput(layer, CANVAS));
        byte[] copy = decodeFrame(inputs, PlayoutCopies.profileVideoGraph(CANVAS, DecoderSource.Fit.FIT, true));
        assertThat(maxDiff(studio, copy)).isLessThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] studioFrame(byte[] base) {
        List<Overlay> list = new ArrayList<>();
        for (StudioSpec.OverlaySetting s : overlays()) {
            list.add(StudioBuilder.overlay(s, s.imageKey() == null ? null : logo));
        }
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(list);
        byte[] frame = base.clone();
        c.blend(frame, FRAME, Instant.now(), Map.of());
        return frame;
    }

    /** Frame {@link #FRAME} of a filter graph ending in [v], as raw yuv420p. */
    private static byte[] decodeFrame(List<String> inputs, String graph) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-nostdin"));
        cmd.addAll(inputs);
        cmd.addAll(List.of("-filter_complex", graph, "-map", "[v]", "-frames:v", String.valueOf(FRAME + 1),
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1"));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] all;
        try (InputStream in = p.getInputStream()) {
            all = in.readAllBytes();
        }
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        int size = CANVAS.frameBytes();
        assertThat(all.length).as("frames from " + cmd).isEqualTo(size * (FRAME + 1));
        byte[] f = new byte[size];
        System.arraycopy(all, size * FRAME, f, 0, size);
        return f;
    }

    /** A region in canvas fractions, as luma pixels {x, y, w, h}. */
    private static int[] box(double x, double y, double w, double h) {
        int bx = (int) (x * CANVAS.width()) & ~1;
        int by = (int) (y * CANVAS.height()) & ~1;
        int bw = Math.min(CANVAS.width() - bx, (int) (w * CANVAS.width())) & ~1;
        int bh = Math.min(CANVAS.height() - by, (int) (h * CANVAS.height())) & ~1;
        return new int[] {bx, by, bw, bh};
    }

    /** Mean absolute difference over a region, luma and both chroma planes. */
    private static double meanDiff(byte[] a, byte[] b, int[] box) {
        int w = CANVAS.width();
        int luma = CANVAS.lumaBytes();
        int chroma = CANVAS.chromaBytes();
        long sum = 0;
        long n = 0;
        for (int y = box[1]; y < box[1] + box[3]; y++) {
            for (int x = box[0]; x < box[0] + box[2]; x++) {
                sum += Math.abs((a[y * w + x] & 0xff) - (b[y * w + x] & 0xff));
                n++;
            }
        }
        for (int y = box[1] / 2; y < (box[1] + box[3]) / 2; y++) {
            for (int x = box[0] / 2; x < (box[0] + box[2]) / 2; x++) {
                int i = y * (w / 2) + x;
                sum += Math.abs((a[luma + i] & 0xff) - (b[luma + i] & 0xff));
                sum += Math.abs((a[luma + chroma + i] & 0xff) - (b[luma + chroma + i] & 0xff));
                n += 2;
            }
        }
        return (double) sum / n;
    }

    private static int maxDiff(byte[] a, byte[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs((a[i] & 0xff) - (b[i] & 0xff)));
        }
        return max;
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(log).isZero();
    }

    private static String runOut(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return out.toString();
    }
}
