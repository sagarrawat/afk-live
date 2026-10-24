package com.afklive.streamer.streaming.studio;

import lombok.extern.slf4j.Slf4j;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * GR-8: fonts for overlay text in Indian scripts. Poppins draws Latin text (and its own Devanagari);
 * any other character is drawn with the first bundled Noto Sans font that has it: Devanagari,
 * Bengali, Gujarati, Gurmukhi, Kannada, Malayalam, Odia, Tamil and Telugu (SIL Open Font License,
 * bundled in /fonts, no font service). Java's text layout shapes each script (conjuncts, vowel signs)
 * with HarfBuzz. Characters no bundled font has (emoji, for example) are left out rather than drawn as
 * empty boxes.
 */
@Slf4j
public final class OverlayFonts {

    private OverlayFonts() {
    }

    static final String[] INDIC = {
            "NotoSansDevanagari-Medium.ttf", "NotoSansBengali-Medium.ttf", "NotoSansGujarati-Medium.ttf",
            "NotoSansGurmukhi-Medium.ttf", "NotoSansKannada-Medium.ttf", "NotoSansMalayalam-Medium.ttf",
            "NotoSansOriya-Regular.ttf", "NotoSansTamil-Medium.ttf", "NotoSansTelugu-Medium.ttf"};

    private static final class Holder {
        static final List<Font> FALLBACK = load();
    }

    private static List<Font> load() {
        List<Font> fonts = new ArrayList<>();
        for (String name : INDIC) {
            try (InputStream in = OverlayFonts.class.getResourceAsStream("/fonts/" + name)) {
                if (in != null) {
                    fonts.add(Font.createFont(Font.TRUETYPE_FONT, in));
                }
            } catch (Exception e) {
                log.warn("Overlay font {} unavailable: {}", name, e.getMessage());
            }
        }
        return List.copyOf(fonts);
    }

    /** The bundled fallback fonts (loaded once, on first use). */
    public static List<Font> fallback() {
        return Holder.FALLBACK;
    }

    /** True when {@code base} draws every character itself (the common case: nothing changes for it). */
    public static boolean coveredBy(Font base, String text) {
        return text == null || base.canDisplayUpTo(text) == -1;
    }

    /** One stretch of text and the font that draws it. */
    public record Run(String text, Font font) {
    }

    /**
     * Splits text into runs, each drawn by one font: the base font where it can, otherwise the first
     * fallback that can. Marks, joiners and spaces stay with the run they follow so a syllable is
     * never split between fonts. Characters nothing can draw are dropped.
     */
    public static List<Run> runs(Font base, String text) {
        List<Run> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return runs;
        }
        StringBuilder current = new StringBuilder();
        Font currentFont = null;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Font f;
            if (currentFont != null && sticks(cp) && currentFont.canDisplay(cp)) {
                f = currentFont;
            } else if (base.canDisplay(cp)) {
                f = base;
            } else {
                f = null;
                for (Font fb : fallback()) {
                    if (fb.canDisplay(cp)) {
                        f = fb;
                        break;
                    }
                }
                if (f == null) {
                    continue; // nothing draws it: leave it out instead of an empty box
                }
            }
            if (currentFont != null && f != currentFont) {
                runs.add(new Run(current.toString(), currentFont));
                current.setLength(0);
            }
            currentFont = f;
            current.appendCodePoint(cp);
        }
        if (currentFont != null && !current.isEmpty()) {
            runs.add(new Run(current.toString(), currentFont));
        }
        return runs;
    }

    /** Characters that belong to the syllable or word before them. */
    private static boolean sticks(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                || cp == 0x200C || cp == 0x200D || Character.isWhitespace(cp) || type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.OTHER_PUNCTUATION || type == Character.DASH_PUNCTUATION;
    }

    /**
     * Laid-out text in mixed fonts at {@code px}, or null when nothing is left to draw. Ascent and
     * descent are the tallest of the fonts used, so boxes fit Indian scripts' marks above and below.
     */
    public static TextLayout layout(Font base, String text, float px, FontRenderContext frc) {
        List<Run> runs = runs(base, text);
        StringBuilder all = new StringBuilder();
        for (Run r : runs) {
            all.append(r.text());
        }
        if (all.isEmpty()) {
            return null;
        }
        AttributedString styled = new AttributedString(all.toString());
        int at = 0;
        for (Run r : runs) {
            styled.addAttribute(TextAttribute.FONT, r.font().deriveFont(px), at, at + r.text().length());
            at += r.text().length();
        }
        return new TextLayout(styled.getIterator(), frc);
    }
}
