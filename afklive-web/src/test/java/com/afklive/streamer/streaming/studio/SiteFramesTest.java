package com.afklive.streamer.streaming.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The homepage's example frames are drawn by the real studio (SiteFrames). These checks keep that
 * true; {@code -Dsite.frames.out=DIR} also writes the PNGs and the example QR code (afklive-ui/site/frames.sh).
 */
class SiteFramesTest {

    private static long differing(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        long n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static BufferedImage withoutOverlays(SiteFrames.Example e) {
        SiteFrames.Example bare = new SiteFrames.Example(e.file(), e.template(), e.language(), e.hasMusic(), e.vars(),
                "NONE", e.at(), e.background());
        // No scene matches "NONE", but global layers (the clock) still draw: compare against the background alone.
        BufferedImage bg = new BufferedImage(SiteFrames.CANVAS.width(), SiteFrames.CANVAS.height(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bg.createGraphics();
        bare.background().paint(g, bg.getWidth(), bg.getHeight());
        g.dispose();
        return SiteFrames.toRgb(SiteFrames.toYuv(bg));
    }

    @Test
    void everyTemplateLayerIsDrawnByTheStudioWhereTheTemplatePutsIt() {
        for (SiteFrames.Example e : SiteFrames.ALL) {
            BufferedImage frame = SiteFrames.render(e);
            BufferedImage bare = withoutOverlays(e);
            assertThat(frame.getWidth()).isEqualTo(1280);
            assertThat(frame.getHeight()).isEqualTo(720);
            List<Overlay> layers = SiteFrames.overlays(e);
            assertThat(layers).as(e.file()).isNotEmpty();
            for (Overlay o : layers) {
                int x = (int) (o.x() * 1280);
                int y = (int) (o.y() * 720);
                long changed = differing(frame, bare, x, y, Math.min(1280, x + 60), Math.min(720, y + (int) (o.size() * 720)));
                assertThat(changed).as(e.file() + " " + o.id()).isGreaterThan(50);
            }
        }
    }

    @Test
    void theHindiFrameUsesTheTemplatesHindiLabels() {
        SiteFrames.Example devotional = SiteFrames.ALL.stream().filter(e -> e.file().equals("frame-devotional")).findFirst().orElseThrow();
        assertThat(SiteFrames.overlays(devotional)).extracting(Overlay::text).anyMatch(t -> t != null && t.startsWith("अभी चल रहा है"));
    }

    @Test
    @EnabledIfSystemProperty(named = "site.frames.out", matches = ".+")
    void writeFrames() throws Exception {
        Path out = Path.of(System.getProperty("site.frames.out"));
        Files.createDirectories(out);
        for (SiteFrames.Example e : SiteFrames.ALL) {
            ImageIO.write(SiteFrames.render(e), "png", out.resolve(e.file() + ".png").toFile());
        }
        Files.writeString(out.resolve("qr-example.svg"), SiteFrames.qrSvg("https://afklive.in/help/phone-streaming"));
    }
}
