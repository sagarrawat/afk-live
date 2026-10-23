package com.afklive.streamer.streaming.studio;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws overlays onto yuv420p frames in the cloud, so the creator's phone pays nothing for them.
 * Each overlay is rendered once with Java2D into a small YUVA patch and cached; only text that
 * changes (the clock each second, a countdown, now-playing) is re-rendered. Per frame the cost is a
 * blend over the overlay's own pixels, not the whole picture. Changes apply on the next frame.
 */
@Slf4j
public final class OverlayCompositor {

    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true"); // workers have no display
        }
    }

    private static final Font BASE_FONT = loadFont();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final Canvas canvas;
    private volatile List<Overlay> overlays = List.of();
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<java.nio.file.Path, BufferedImage> images = new HashMap<>();
    private List<Overlay> lastList;

    private record Cached(Overlay overlay, String text, Patch patch) {
    }

    /** A rendered layer: luma and chroma planes with alpha, at an even position. */
    record Patch(int x, int y, int w, int h, byte[] luma, byte[] lumaAlpha, byte[] u, byte[] v, byte[] chromaAlpha) {
    }

    public OverlayCompositor(Canvas canvas) {
        this.canvas = canvas;
    }

    public void setOverlays(List<Overlay> list) {
        if (!list.equals(overlays)) {
            this.overlays = List.copyOf(list);
        }
    }

    public List<Overlay> overlays() {
        return overlays;
    }

    /** Blends every visible overlay into {@code yuv}. Called on the clock thread only. */
    public void blend(byte[] yuv, long frameIndex, Instant now, Map<String, String> vars) {
        List<Overlay> list = overlays;
        if (list.isEmpty()) {
            cache.clear();
            images.clear();
            lastList = list;
            return;
        }
        for (Overlay o : list) {
            if (!o.visible() || o.opacity() <= 0) {
                continue;
            }
            try {
                if (o.type() == Overlay.Type.TICKER) {
                    Patch strip = patchFor(o, resolve(o.text(), vars));
                    if (strip != null) {
                        blendTicker(yuv, strip, o, frameIndex);
                    }
                } else {
                    String text = switch (o.type()) {
                        case CLOCK -> now.atZone(zone(o.timeZone())).format(CLOCK);
                        case COUNTDOWN -> countdown(o, now);
                        case TEXT -> resolve(o.text(), vars);
                        default -> "";
                    };
                    Patch p = patchFor(o, text);
                    if (p != null) {
                        blendPatch(yuv, p, 0);
                    }
                }
            } catch (Exception e) {
                log.debug("Overlay {} failed: {}", o.id(), e.getMessage());
            }
        }
        if (list != lastList) {
            // The overlay set changed: forget renders and images it no longer uses (logos replaced at the same path too).
            lastList = list;
            java.util.Set<String> keys = new java.util.HashSet<>();
            java.util.Set<java.nio.file.Path> paths = new java.util.HashSet<>();
            for (Overlay o : list) {
                keys.add(cacheKey(o));
                if (o.image() != null) {
                    paths.add(o.image());
                }
            }
            cache.keySet().retainAll(keys);
            images.keySet().retainAll(paths);
        }
    }

    private Patch patchFor(Overlay o, String text) {
        String key = cacheKey(o);
        Cached c = cache.get(key);
        if (c != null && c.overlay().equals(o) && java.util.Objects.equals(c.text(), text)) {
            return c.patch();
        }
        BufferedImage img = render(o, text);
        Patch p = img == null ? null : toPatch(img, px(o.x(), canvas.width()), px(o.y(), canvas.height()), o.opacity());
        cache.put(key, new Cached(o, text, p));
        return p;
    }

    /** Overlays are cached per id and placement, so a global and a scene overlay sharing an id don't evict each other. */
    private static String cacheKey(Overlay o) {
        return o.id() + "@" + o.type() + "@" + o.x() + "," + o.y();
    }

    // ---------------------------------------------------------------- rendering (Java2D)

    private BufferedImage render(Overlay o, String text) {
        return switch (o.type()) {
            case IMAGE -> renderImage(o);
            case TICKER -> renderTickerStrip(o, text);
            default -> text == null || text.isBlank() ? null
                    : WATERMARK_ID.equals(o.id()) ? renderWatermark(text, o) : renderText(text, o);
        };
    }

    /** The id of the Free plan's mark (LiveStreamService adds it; BI-1). */
    public static final String WATERMARK_ID = "afk-watermark";

    /**
     * The Free plan's mark: the AFK Live moon-and-dot (docs/brand.md) followed by the name, in a box.
     * Same geometry as afklive-ui/brand/build.py, on its 64 grid.
     */
    private BufferedImage renderWatermark(String text, Overlay o) {
        Font f = font(o);
        FontMetrics fm = metrics(f);
        float em = f.getSize2D();
        int padX = Math.round(em * 0.45f);
        int padY = Math.round(em * 0.22f);
        int textH = fm.getAscent() + fm.getDescent();
        double markSize = textH * 0.95;
        int gap = Math.round(em * 0.3f);
        int w = Math.min(canvas.width(), (int) Math.ceil(markSize) + gap + fm.stringWidth(text) + 2 * padX);
        int h = textH + 2 * padY;
        BufferedImage img = new BufferedImage(Math.max(2, w), Math.max(2, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img, f);
        if ((o.background() >>> 24) != 0) {
            g.setColor(new Color(o.background(), true));
            int arc = Math.round(em * 0.5f);
            g.fillRoundRect(0, 0, w, h, arc, arc);
        }
        double s = markSize / 64.0;
        double ox = padX, oy = (h - markSize) / 2.0;
        java.awt.geom.Area moon = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(ox + (29 - 24) * s, oy + (35 - 24) * s, 48 * s, 48 * s));
        moon.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(ox + (41 - 17) * s, oy + (23 - 17) * s, 34 * s, 34 * s)));
        g.setColor(new Color(0xFFB627));
        g.fill(moon);
        g.setColor(new Color(0xFF4438));
        g.fill(new java.awt.geom.Ellipse2D.Double(ox + (41 - 8) * s, oy + (23 - 8) * s, 16 * s, 16 * s));
        g.setColor(new Color(o.color()));
        g.drawString(text, (int) Math.round(padX + markSize + gap), padY + fm.getAscent());
        g.dispose();
        return img;
    }

    private BufferedImage renderImage(Overlay o) {
        if (o.image() == null) {
            return null;
        }
        BufferedImage src = images.computeIfAbsent(o.image(), p -> {
            try {
                return ImageIO.read(p.toFile());
            } catch (Exception e) {
                log.warn("Overlay image {} unreadable: {}", p.getFileName(), e.getMessage());
                return null;
            }
        });
        if (src == null) {
            return null;
        }
        int w = Math.max(2, (int) Math.round(o.width() * canvas.width()));
        int h = Math.max(2, (int) Math.round((double) src.getHeight() * w / src.getWidth()));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private Font font(Overlay o) {
        float px = (float) Math.max(10, o.size() * canvas.height());
        return BASE_FONT.deriveFont(px);
    }

    private BufferedImage renderText(String text, Overlay o) {
        Font f = font(o);
        FontMetrics fm = metrics(f);
        int padX = Math.round(f.getSize2D() * 0.45f);
        int padY = Math.round(f.getSize2D() * 0.22f);
        int w = Math.min(canvas.width(), fm.stringWidth(text) + 2 * padX);
        int h = fm.getAscent() + fm.getDescent() + 2 * padY;
        BufferedImage img = new BufferedImage(Math.max(2, w), Math.max(2, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img, f);
        if ((o.background() >>> 24) != 0) {
            g.setColor(new Color(o.background(), true));
            int arc = Math.round(f.getSize2D() * 0.5f);
            g.fillRoundRect(0, 0, w, h, arc, arc);
        }
        g.setColor(new Color(o.color()));
        g.drawString(text, padX, padY + fm.getAscent());
        g.dispose();
        return img;
    }

    /** The ticker's text, rendered once as a strip that scrolls across a full-width band. */
    private BufferedImage renderTickerStrip(Overlay o, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Font f = font(o);
        FontMetrics fm = metrics(f);
        String unit = text + "     •     ";
        int w = Math.max(2, fm.stringWidth(unit));
        int h = Math.round((fm.getAscent() + fm.getDescent()) * 1.5f);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img, f);
        g.setColor(new Color(o.background(), true));
        g.setComposite(AlphaComposite.Src);
        g.fillRect(0, 0, w, h);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(o.color()));
        g.drawString(unit, 0, (h - fm.getAscent() - fm.getDescent()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    private static Graphics2D graphics(BufferedImage img, Font f) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(f);
        return g;
    }

    private static FontMetrics metrics(Font f) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(probe, f);
        FontMetrics fm = g.getFontMetrics();
        g.dispose();
        return fm;
    }

    // ---------------------------------------------------------------- YUVA conversion and blending

    private Patch toPatch(BufferedImage img, int x, int y, double opacity) {
        int w = img.getWidth() & ~1;
        int h = img.getHeight() & ~1;
        if (w < 2 || h < 2) {
            return null;
        }
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] luma = new byte[w * h];
        byte[] lumaAlpha = new byte[w * h];
        int cw = w / 2;
        int ch = h / 2;
        byte[] u = new byte[cw * ch];
        byte[] v = new byte[cw * ch];
        byte[] chromaAlpha = new byte[cw * ch];
        double op = Math.max(0, Math.min(1, opacity));
        for (int i = 0; i < w * h; i++) {
            int p = argb[i];
            int a = (int) Math.round(((p >>> 24) & 0xff) * op);
            int[] yuv = Yuv.fromRgb(p & 0xffffff);
            luma[i] = (byte) yuv[0];
            lumaAlpha[i] = (byte) a;
        }
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                long sumA = 0;
                long sumU = 0;
                long sumV = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int idx = (cy * 2 + dy) * w + cx * 2 + dx;
                        int a = lumaAlpha[idx] & 0xff;
                        int[] yuv = Yuv.fromRgb(argb[idx] & 0xffffff);
                        sumA += a;
                        sumU += (long) yuv[1] * a;
                        sumV += (long) yuv[2] * a;
                    }
                }
                int ci = cy * cw + cx;
                chromaAlpha[ci] = (byte) (sumA / 4);
                u[ci] = (byte) (sumA == 0 ? 128 : sumU / sumA);
                v[ci] = (byte) (sumA == 0 ? 128 : sumV / sumA);
            }
        }
        return new Patch(x & ~1, y & ~1, w, h, luma, lumaAlpha, u, v, chromaAlpha);
    }

    /** Blends a patch at its position; {@code srcOffsetX} lets tickers reuse this for wrapped strips. */
    void blendPatch(byte[] yuv, Patch p, int srcOffsetX) {
        int cw = canvas.width();
        int chh = canvas.height();
        for (int r = 0; r < p.h(); r++) {
            int dy = p.y() + r;
            if (dy < 0 || dy >= chh) {
                continue;
            }
            int rowDst = dy * cw;
            int rowSrc = r * p.w();
            for (int c = 0; c < p.w(); c++) {
                int dx = p.x() + c;
                if (dx < 0 || dx >= cw) {
                    continue;
                }
                int a = p.lumaAlpha()[rowSrc + c] & 0xff;
                if (a == 0) {
                    continue;
                }
                int d = rowDst + dx;
                yuv[d] = blendByte(p.luma()[rowSrc + c], yuv[d], a);
            }
        }
        int lumaSize = canvas.lumaBytes();
        int chromaW = cw / 2;
        int chromaH = chh / 2;
        int pcw = p.w() / 2;
        for (int r = 0; r < p.h() / 2; r++) {
            int dy = p.y() / 2 + r;
            if (dy < 0 || dy >= chromaH) {
                continue;
            }
            for (int c = 0; c < pcw; c++) {
                int dx = p.x() / 2 + c;
                if (dx < 0 || dx >= chromaW) {
                    continue;
                }
                int si = r * pcw + c;
                int a = p.chromaAlpha()[si] & 0xff;
                if (a == 0) {
                    continue;
                }
                int di = dy * chromaW + dx;
                yuv[lumaSize + di] = blendByte(p.u()[si], yuv[lumaSize + di], a);
                yuv[lumaSize + canvas.chromaBytes() + di] = blendByte(p.v()[si], yuv[lumaSize + canvas.chromaBytes() + di], a);
            }
        }
    }

    private void blendTicker(byte[] yuv, Patch strip, Overlay o, long frameIndex) {
        double pxPerFrame = Math.max(0.5, o.speed() <= 0 ? 120 : o.speed()) * canvas.height() / 1080.0 / canvas.fps();
        int offset = (int) (((long) Math.floor(frameIndex * pxPerFrame)) % strip.w()) & ~1;
        int y = px(o.y(), canvas.height()) & ~1;
        int cw = canvas.width();
        int lumaSize = canvas.lumaBytes();
        int chromaW = cw / 2;
        for (int r = 0; r < strip.h(); r++) {
            int dy = y + r;
            if (dy < 0 || dy >= canvas.height()) {
                continue;
            }
            for (int x = 0; x < cw; x++) {
                int sx = (x + offset) % strip.w();
                int si = r * strip.w() + sx;
                int a = strip.lumaAlpha()[si] & 0xff;
                if (a != 0) {
                    int d = dy * cw + x;
                    yuv[d] = blendByte(strip.luma()[si], yuv[d], a);
                }
            }
        }
        int sw2 = strip.w() / 2;
        for (int r = 0; r < strip.h() / 2; r++) {
            int dy = y / 2 + r;
            if (dy < 0 || dy >= canvas.height() / 2) {
                continue;
            }
            for (int x = 0; x < chromaW; x++) {
                int sx = (x + offset / 2) % sw2;
                int si = r * sw2 + sx;
                int a = strip.chromaAlpha()[si] & 0xff;
                if (a != 0) {
                    int di = dy * chromaW + x;
                    yuv[lumaSize + di] = blendByte(strip.u()[si], yuv[lumaSize + di], a);
                    yuv[lumaSize + canvas.chromaBytes() + di] = blendByte(strip.v()[si], yuv[lumaSize + canvas.chromaBytes() + di], a);
                }
            }
        }
    }

    private static byte blendByte(byte src, byte dst, int alpha) {
        int s = src & 0xff;
        int d = dst & 0xff;
        return (byte) ((s * alpha + d * (255 - alpha) + 127) / 255);
    }

    // ---------------------------------------------------------------- text helpers

    static String resolve(String template, Map<String, String> vars) {
        if (template == null) {
            return null;
        }
        String out = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }

    static String countdown(Overlay o, Instant now) {
        String label = o.text() == null ? "Starting in" : o.text();
        if (o.target() == null || !now.isBefore(o.target())) {
            return o.text() == null ? "Starting soon" : label.replace("{time}", "00:00");
        }
        Duration left = Duration.between(now, o.target());
        long s = left.getSeconds() + (left.getNano() > 0 ? 1 : 0);
        String t = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
        return label.contains("{time}") ? label.replace("{time}", t) : label + " " + t;
    }

    private static ZoneId zone(String id) {
        try {
            return id == null ? ZoneId.of("UTC") : ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    private static int px(double fraction, int size) {
        return (int) Math.round(Math.max(0, Math.min(1, fraction)) * size);
    }

    private static Font loadFont() {
        try (InputStream in = OverlayCompositor.class.getResourceAsStream("/fonts/Poppins-Medium.ttf")) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            }
        } catch (Exception e) {
            log.warn("Bundled overlay font unavailable, using the system sans-serif: {}", e.getMessage());
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }
}
