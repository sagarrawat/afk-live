package com.afklive.streamer.streaming.studio;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GR-8: overlay text in Indian scripts is drawn with real glyphs, never empty boxes. */
class OverlayFontsTest {

    private static final Font BASE = OverlayCompositor.baseFont();
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    /** Every glyph of the text exists in the font (no "missing glyph" box). */
    private static boolean drawsFully(Font f, String text) {
        GlyphVector gv = f.deriveFont(40f).layoutGlyphVector(FRC, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);
        int missing = f.getMissingGlyphCode();
        for (int i = 0; i < gv.getNumGlyphs(); i++) {
            if (gv.getGlyphCode(i) == missing) {
                return false;
            }
        }
        return true;
    }

    @Test
    void bundlesAFontForEachMajorIndianScript() {
        String[] samples = {"हनुमान", "বাংলা", "ગુજરાતી", "ਪੰਜਾਬੀ", "ಕನ್ನಡ", "മലയാളം", "ଓଡ଼ିଆ", "தமிழ்", "తెలుగు"};
        assertThat(OverlayFonts.fallback()).hasSize(OverlayFonts.INDIC.length);
        for (String s : samples) {
            List<OverlayFonts.Run> runs = OverlayFonts.runs(BASE, s);
            assertThat(runs).as(s).hasSize(1);
            assertThat(drawsFully(runs.getFirst().font(), s)).as(s).isTrue();
        }
    }

    @Test
    void latinStaysInTheBrandFontAndOtherScriptsFallBack() {
        List<OverlayFonts.Run> runs = OverlayFonts.runs(BASE, "Now playing: தமிழ் பாடல் 24/7");
        assertThat(runs).extracting(OverlayFonts.Run::text).containsExactly("Now playing: ", "தமிழ் பாடல் 24/7");
        assertThat(runs.getFirst().font()).isSameAs(BASE);
        assertThat(runs.get(1).font().getFamily()).contains("Tamil");
        // Hindi: Poppins has Devanagari itself, so nothing changes for existing Hindi overlays.
        assertThat(OverlayFonts.coveredBy(BASE, "अभी चल रहा है: {title}")).isTrue();
    }

    @Test
    void charactersNoFontHasAreLeftOutNotDrawnAsBoxes() {
        List<OverlayFonts.Run> runs = OverlayFonts.runs(BASE, "♪ Lofi 🙏");
        assertThat(String.join("", runs.stream().map(OverlayFonts.Run::text).toList())).isEqualTo(" Lofi ");
        assertThat(OverlayFonts.runs(BASE, "🙏")).isEmpty();
    }

    @Test
    void theCompositorDrawsTamilAndSkipsTextItCannotDraw() {
        Canvas canvas = new Canvas(640, 360, 30, 44100);
        byte[] tamil = new byte[canvas.frameBytes()];
        byte[] emoji = new byte[canvas.frameBytes()];
        byte[] blank = new byte[canvas.frameBytes()];
        Frame.fill(tamil, canvas, 0);
        Frame.fill(emoji, canvas, 0);
        Frame.fill(blank, canvas, 0);
        OverlayCompositor a = new OverlayCompositor(canvas);
        a.setOverlays(List.of(Overlay.text("t", "தமிழ் பாடல்", 0.1, 0.4, 0.08, 0xffffff, 0)));
        a.blend(tamil, 0, Instant.now(), Map.of());
        OverlayCompositor b = new OverlayCompositor(canvas);
        b.setOverlays(List.of(Overlay.text("e", "🙏", 0.1, 0.4, 0.08, 0xffffff, 0)));
        b.blend(emoji, 0, Instant.now(), Map.of());
        int changed = 0;
        for (int i = 0; i < canvas.width() * canvas.height(); i++) {
            if (tamil[i] != blank[i]) {
                changed++;
            }
        }
        assertThat(changed).as("Tamil letters drawn").isGreaterThan(300);
        assertThat(emoji).as("no empty box for an emoji").isEqualTo(blank);
    }
}
