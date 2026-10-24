package com.afklive.streamer.streaming.studio;

import com.afklive.streamer.service.NicheTemplates;
import com.afklive.streamer.streaming.StudioBuilder;
import com.afklive.streamer.streaming.StudioSpec;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The homepage's example frames (afklive-ui/site): what viewers see on YouTube with a niche template.
 * The backgrounds are spot illustrations in the brand's style (docs/brand.md: flat shapes on Night,
 * Marigold for the subject, Mist and #3A3F72 for lines, no people, no photos). Everything on top of
 * them (now playing, up next, the clock, Be right back) is the real studio: the template's layers from
 * {@link NicheTemplates}, converted by {@link StudioBuilder#overlay} and drawn by {@link OverlayCompositor}
 * onto a yuv420p frame, exactly as a stream gets them. Regenerate with afklive-ui/site/frames.sh.
 */
public final class SiteFrames {

    private SiteFrames() {
    }

    static final int NIGHT = 0x10122A;
    static final int DUSK = 0x1A1D3B;
    static final int LINE = 0x3A3F72;
    static final int MIST = 0xEEF0FA;
    static final int MARIGOLD = 0xFFB627;
    static final int MARIGOLD_SOFT = 0xFFD98A;

    public static final Canvas CANVAS = new Canvas(1280, 720, 30, 44100);
    static final String TZ = "Asia/Kolkata";

    /**
     * One example frame. {@code template}: whose layers; {@code language}: their labels; {@code scene}: which
     * scene is on (LIVE, or BRB for the never-drop screen); {@code at}: the moment the clock shows.
     */
    public record Example(String file, String template, String language, boolean hasMusic, Map<String, String> vars,
                          String scene, Instant at, Background background) {
    }

    @FunctionalInterface
    public interface Background {
        void paint(Graphics2D g, int w, int h);
    }

    public static final List<Example> ALL = List.of(
            new Example("frame-lofi", "lofi", "en", true, Map.of("track", "Rain on the window, lofi mix"),
                    StudioSpec.LIVE, Instant.parse("2026-10-24T20:44:00Z"), SiteFrames::lofi),
            new Example("frame-devotional", "devotional", "hi", false, Map.of("title", "संध्या आरती", "next", "हनुमान चालीसा"),
                    StudioSpec.LIVE, Instant.parse("2026-10-24T13:12:00Z"), SiteFrames::temple),
            new Example("frame-gaming", "phone-gaming", "en", false, Map.of(),
                    StudioSpec.LIVE, Instant.parse("2026-10-24T16:07:00Z"), SiteFrames::game),
            // Podcasts have no template of their own: these are the devotional template's layers in English
            // (now playing, up next, clock), which a podcaster gets by applying it.
            new Example("frame-podcast", "devotional", "en", false,
                    Map.of("title", "Episode 41: Budget mics that sound good", "next", "Episode 42: Your first 100 listeners"),
                    StudioSpec.LIVE, Instant.parse("2026-10-24T17:35:00Z"), SiteFrames::podcast),
            new Example("frame-study", "study", "en", true, Map.of("track", "Soft piano for focus"),
                    StudioSpec.LIVE, Instant.parse("2026-10-24T00:18:00Z"), SiteFrames::study),
            // The never-drop screen: the phone's signal is gone, viewers see the template's Be right back scene
            // (its colour, as LiveFeed shows it without a background image) and the clock keeps going.
            new Example("frame-brb", "phone-gaming", "en", false, Map.of(),
                    StudioSpec.BRB, Instant.parse("2026-10-24T16:09:00Z"), SiteFrames::brb));

    /** The frame as a stream carries it: background to yuv420p, overlays blended, back to RGB. */
    public static BufferedImage render(Example e) {
        BufferedImage bg = new BufferedImage(CANVAS.width(), CANVAS.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        e.background().paint(g, CANVAS.width(), CANVAS.height());
        g.dispose();
        byte[] yuv = toYuv(bg);
        OverlayCompositor c = new OverlayCompositor(CANVAS);
        c.setOverlays(overlays(e));
        c.blend(yuv, 0, e.at(), e.vars());
        return toRgb(yuv);
    }

    /** The template's layers for the frame's scene: global ones plus that scene's, as the studio picks them. */
    static List<Overlay> overlays(Example e) {
        NicheTemplates.Template t = NicheTemplates.find(e.template()).orElseThrow();
        List<Overlay> list = new ArrayList<>();
        for (StudioSpec.OverlaySetting s : NicheTemplates.overlays(t, e.language(), TZ, e.hasMusic())) {
            if (s.scene() == null || s.scene().isBlank() || s.scene().equals(e.scene())) {
                list.add(StudioBuilder.overlay(s, null));
            }
        }
        return list;
    }

    static byte[] toYuv(BufferedImage img) {
        int w = CANVAS.width();
        int h = CANVAS.height();
        byte[] yuv = new byte[CANVAS.frameBytes()];
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < w * h; i++) {
            yuv[i] = (byte) Yuv.fromRgb(px[i] & 0xffffff)[0];
        }
        int cw = w / 2;
        for (int cy = 0; cy < h / 2; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int u = 0;
                int v = 0;
                for (int d = 0; d < 4; d++) {
                    int[] c = Yuv.fromRgb(px[(cy * 2 + d / 2) * w + cx * 2 + d % 2] & 0xffffff);
                    u += c[1];
                    v += c[2];
                }
                yuv[CANVAS.lumaBytes() + cy * cw + cx] = (byte) (u / 4);
                yuv[CANVAS.lumaBytes() + CANVAS.chromaBytes() + cy * cw + cx] = (byte) (v / 4);
            }
        }
        return yuv;
    }

    /** Limited-range BT.601 back to RGB, the way a player shows the stream. */
    static BufferedImage toRgb(byte[] yuv) {
        int w = CANVAS.width();
        int h = CANVAS.height();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int cw = w / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = (yuv[y * w + x] & 0xff) - 16;
                int ci = (y / 2) * cw + x / 2;
                int d = (yuv[CANVAS.lumaBytes() + ci] & 0xff) - 128;
                int e = (yuv[CANVAS.lumaBytes() + CANVAS.chromaBytes() + ci] & 0xff) - 128;
                int r = Yuv.clamp((298 * c + 409 * e + 128) >> 8);
                int g = Yuv.clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = Yuv.clamp((298 * c + 516 * d + 128) >> 8);
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ backgrounds

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    private static Color c(int rgb, int alpha) {
        return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, alpha);
    }

    private static void fill(Graphics2D g, int rgb, Shape s) {
        g.setColor(c(rgb));
        g.fill(s);
    }

    private static void stroke(Graphics2D g, int rgb, float width, Shape s) {
        g.setColor(c(rgb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
    }

    private static void stars(Graphics2D g, int seed, int n, int x0, int y0, int x1, int y1) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            double x = x0 + r.nextDouble() * (x1 - x0);
            double y = y0 + r.nextDouble() * (y1 - y0);
            double s = 1.5 + r.nextDouble() * 2;
            g.setColor(c(MIST, 90 + r.nextInt(120)));
            g.fill(new Ellipse2D.Double(x, y, s, s));
        }
    }

    /** The brand's crescent: a disc with a smaller disc taken out, as in the mark. */
    private static Shape crescent(double cx, double cy, double r) {
        Area a = new Area(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        double r2 = r * 0.72;
        a.subtract(new Area(new Ellipse2D.Double(cx + r * 0.25 - r2, cy - r * 0.5 - r2, 2 * r2, 2 * r2)));
        return a;
    }

    private static Shape flame(double cx, double baseY, double w, double h) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, baseY - h);
        p.curveTo(cx + w * 0.9, baseY - h * 0.45, cx + w * 0.6, baseY, cx, baseY);
        p.curveTo(cx - w * 0.6, baseY, cx - w * 0.9, baseY - h * 0.45, cx, baseY - h);
        p.closePath();
        return p;
    }

    static void lofi(Graphics2D g, int w, int h) {
        fill(g, NIGHT, new java.awt.Rectangle(0, 0, w, h));
        // Window with rain and the moon.
        RoundRectangle2D win = new RoundRectangle2D.Double(690, 90, 400, 380, 18, 18);
        fill(g, DUSK, win);
        java.awt.Shape oldClip = g.getClip();
        g.setClip(win);
        stars(g, 7, 40, 690, 90, 1090, 470);
        fill(g, MIST, crescent(990, 175, 40));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Random r = new Random(3);
        for (int i = 0; i < 70; i++) {
            double x = 690 + r.nextDouble() * 430;
            double y = 90 + r.nextDouble() * 380;
            g.setColor(c(MIST, 40 + r.nextInt(50)));
            g.draw(new java.awt.geom.Line2D.Double(x, y, x - 8, y + 26));
        }
        g.setClip(oldClip);
        stroke(g, LINE, 12, win);
        stroke(g, LINE, 8, new java.awt.geom.Line2D.Double(890, 90, 890, 470));
        stroke(g, LINE, 8, new java.awt.geom.Line2D.Double(690, 280, 1090, 280));
        // Sill and desk.
        fill(g, LINE, new RoundRectangle2D.Double(670, 466, 440, 18, 8, 8));
        fill(g, DUSK, new java.awt.Rectangle(0, 560, w, h - 560));
        fill(g, LINE, new java.awt.Rectangle(0, 552, w, 14));
        // Light from the lamp onto the desk.
        Path2D cone = new Path2D.Double();
        cone.moveTo(300, 330);
        cone.lineTo(390, 330);
        cone.lineTo(560, 556);
        cone.lineTo(130, 556);
        cone.closePath();
        g.setColor(c(MIST, 16));
        g.fill(cone);
        // The lamp (the subject).
        fill(g, LINE, new RoundRectangle2D.Double(250, 540, 120, 16, 10, 10));
        stroke(g, LINE, 10, new java.awt.geom.Line2D.Double(310, 545, 250, 400));
        stroke(g, LINE, 10, new java.awt.geom.Line2D.Double(250, 400, 335, 280));
        Path2D shade = new Path2D.Double();
        shade.moveTo(315, 262);
        shade.lineTo(375, 262);
        shade.lineTo(400, 332);
        shade.lineTo(290, 332);
        shade.closePath();
        fill(g, MARIGOLD, shade);
        fill(g, MARIGOLD_SOFT, new Ellipse2D.Double(332, 322, 26, 18));
        fill(g, LINE, new Ellipse2D.Double(240, 390, 20, 20));
        // Mug with steam.
        fill(g, MARIGOLD_SOFT, new RoundRectangle2D.Double(500, 480, 64, 76, 14, 14));
        stroke(g, MARIGOLD_SOFT, 8, new Arc2D.Double(548, 498, 34, 36, -90, 180, Arc2D.OPEN));
        g.setColor(c(MIST, 120));
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new QuadCurve2D.Double(520, 470, 505, 440, 525, 415));
        g.draw(new QuadCurve2D.Double(544, 470, 529, 440, 549, 415));
        // Headphones resting on the desk.
        stroke(g, LINE, 12, new Arc2D.Double(640, 470, 120, 120, 0, 180, Arc2D.OPEN));
        fill(g, LINE, new RoundRectangle2D.Double(628, 515, 30, 44, 12, 12));
        fill(g, LINE, new RoundRectangle2D.Double(742, 515, 30, 44, 12, 12));
        // Plant.
        fill(g, LINE, new RoundRectangle2D.Double(1120, 490, 80, 66, 10, 10));
        g.setColor(c(LINE));
        for (int i = 0; i < 5; i++) {
            AffineRotate.fill(g, new Ellipse2D.Double(1150, 380, 22, 110), Math.toRadians(-40 + i * 20), 1161, 490, LINE);
        }
        // A record sleeve on the wall.
        fill(g, DUSK, new RoundRectangle2D.Double(150, 110, 170, 170, 10, 10));
        stroke(g, LINE, 6, new RoundRectangle2D.Double(150, 110, 170, 170, 10, 10));
        fill(g, LINE, new Ellipse2D.Double(175, 135, 120, 120));
        fill(g, MARIGOLD, new Ellipse2D.Double(219, 179, 32, 32));
    }

    static void temple(Graphics2D g, int w, int h) {
        fill(g, NIGHT, new java.awt.Rectangle(0, 0, w, h));
        stars(g, 11, 60, 0, 120, w, 380);
        fill(g, MIST, crescent(180, 200, 34));
        // Shikhara and mandapa.
        Path2D tower = new Path2D.Double();
        tower.moveTo(560, 470);
        tower.curveTo(560, 330, 600, 230, 640, 170);
        tower.curveTo(680, 230, 720, 330, 720, 470);
        tower.closePath();
        fill(g, DUSK, tower);
        stroke(g, LINE, 6, tower);
        for (int i = 0; i < 6; i++) {
            double y = 230 + i * 40;
            double half = 26 + i * 12;
            stroke(g, LINE, 4, new java.awt.geom.Line2D.Double(640 - half, y, 640 + half, y));
        }
        fill(g, MARIGOLD, new Ellipse2D.Double(630, 150, 20, 20));
        stroke(g, LINE, 4, new java.awt.geom.Line2D.Double(640, 150, 640, 100));
        Path2D flag = new Path2D.Double();
        flag.moveTo(642, 100);
        flag.lineTo(690, 114);
        flag.lineTo(642, 128);
        flag.closePath();
        fill(g, MARIGOLD, flag);
        RoundRectangle2D hall = new RoundRectangle2D.Double(440, 450, 400, 150, 8, 8);
        fill(g, DUSK, hall);
        stroke(g, LINE, 6, hall);
        for (int i = 0; i < 3; i++) {
            fill(g, LINE, new java.awt.Rectangle(400 - i * 30, 596 + i * 18, 480 + i * 60, 14));
        }
        // Doorway with the lamp inside.
        Path2D door = new Path2D.Double();
        door.moveTo(600, 600);
        door.lineTo(600, 520);
        door.quadTo(640, 470, 680, 520);
        door.lineTo(680, 600);
        door.closePath();
        fill(g, NIGHT, door);
        fill(g, MARIGOLD, flame(640, 575, 14, 34));
        // Toran: a garland of marigolds across the top.
        for (int i = 0; i <= 26; i++) {
            double x = i * 50;
            double y = 22 + 26 * Math.sin(Math.PI * ((i % 6) / 6.0));
            fill(g, i % 2 == 0 ? MARIGOLD : MARIGOLD_SOFT, new Ellipse2D.Double(x - 14, y - 14, 28, 28));
        }
        for (int i = 3; i < 26; i += 6) {
            double x = i * 50;
            stroke(g, LINE, 3, new java.awt.geom.Line2D.Double(x, 50, x, 110));
            fill(g, MARIGOLD, new Ellipse2D.Double(x - 9, 104, 18, 18));
            fill(g, MARIGOLD_SOFT, new Ellipse2D.Double(x - 7, 124, 14, 14));
        }
        // Bells.
        for (int bx : new int[]{470, 810}) {
            stroke(g, LINE, 3, new java.awt.geom.Line2D.Double(bx, 380, bx, 430));
            Path2D bell = new Path2D.Double();
            bell.moveTo(bx - 18, 470);
            bell.quadTo(bx - 18, 430, bx, 428);
            bell.quadTo(bx + 18, 430, bx + 18, 470);
            bell.closePath();
            fill(g, MARIGOLD_SOFT, bell);
        }
        // The aarti thali in the foreground (the subject).
        fill(g, MARIGOLD, new Ellipse2D.Double(880, 600, 280, 64));
        fill(g, MARIGOLD_SOFT, new Ellipse2D.Double(900, 604, 240, 46));
        for (int i = 0; i < 5; i++) {
            double x = 940 + i * 40;
            fill(g, MARIGOLD, new Arc2D.Double(x - 16, 600, 32, 26, 180, 180, Arc2D.CHORD));
            fill(g, MARIGOLD, flame(x, 612, 12, 40));
            fill(g, MIST, flame(x, 610, 5, 16));
        }
    }

    static void game(Graphics2D g, int w, int h) {
        fill(g, NIGHT, new java.awt.Rectangle(0, 0, w, h));
        stars(g, 5, 50, 0, 0, w, 300);
        fill(g, MIST, crescent(300, 120, 30));
        // Hills.
        Path2D far = new Path2D.Double();
        far.moveTo(0, 360);
        far.curveTo(200, 250, 380, 320, 560, 300);
        far.curveTo(760, 280, 900, 230, 1280, 330);
        far.lineTo(1280, 720);
        far.lineTo(0, 720);
        far.closePath();
        fill(g, LINE, far);
        Path2D near = new Path2D.Double();
        near.moveTo(0, 420);
        near.curveTo(260, 360, 420, 400, 640, 380);
        near.curveTo(900, 360, 1060, 400, 1280, 390);
        near.lineTo(1280, 720);
        near.lineTo(0, 720);
        near.closePath();
        fill(g, DUSK, near);
        // Road to the horizon.
        Path2D road = new Path2D.Double();
        road.moveTo(600, 382);
        road.lineTo(680, 382);
        road.lineTo(1180, 720);
        road.lineTo(100, 720);
        road.closePath();
        fill(g, NIGHT, road);
        g.setColor(c(MIST, 160));
        for (int i = 0; i < 6; i++) {
            double t0 = Math.pow(i / 6.0, 1.8);
            double t1 = Math.pow((i + 0.45) / 6.0, 1.8);
            double y0 = 382 + t0 * 338;
            double y1 = 382 + t1 * 338;
            double w0 = 2 + t0 * 14;
            double w1 = 2 + t1 * 14;
            Path2D dash = new Path2D.Double();
            dash.moveTo(640 - w0 / 2, y0);
            dash.lineTo(640 + w0 / 2, y0);
            dash.lineTo(640 + w1 / 2, y1);
            dash.lineTo(640 - w1 / 2, y1);
            dash.closePath();
            g.fill(dash);
        }
        // Trees along the road.
        for (int i = 0; i < 5; i++) {
            double t = i / 5.0;
            double s = 0.4 + t * 1.4;
            for (int side : new int[]{-1, 1}) {
                double x = 640 + side * (90 + t * 520);
                double y = 382 + t * 250;
                Path2D tree = new Path2D.Double();
                tree.moveTo(x, y - 90 * s);
                tree.lineTo(x + 26 * s, y);
                tree.lineTo(x - 26 * s, y);
                tree.closePath();
                fill(g, LINE, tree);
            }
        }
        // The car (the subject), seen from behind.
        fill(g, NIGHT, new Ellipse2D.Double(470, 640, 340, 40));
        RoundRectangle2D body = new RoundRectangle2D.Double(490, 540, 300, 110, 30, 30);
        fill(g, MARIGOLD, body);
        Path2D cabin = new Path2D.Double();
        cabin.moveTo(540, 545);
        cabin.lineTo(570, 480);
        cabin.lineTo(710, 480);
        cabin.lineTo(740, 545);
        cabin.closePath();
        fill(g, MARIGOLD, cabin);
        Path2D glass = new Path2D.Double();
        glass.moveTo(560, 540);
        glass.lineTo(582, 494);
        glass.lineTo(698, 494);
        glass.lineTo(720, 540);
        glass.closePath();
        fill(g, DUSK, glass);
        fill(g, MARIGOLD_SOFT, new RoundRectangle2D.Double(505, 570, 70, 20, 10, 10));
        fill(g, MARIGOLD_SOFT, new RoundRectangle2D.Double(705, 570, 70, 20, 10, 10));
        fill(g, DUSK, new RoundRectangle2D.Double(600, 600, 80, 24, 6, 6));
        fill(g, NIGHT, new RoundRectangle2D.Double(500, 640, 60, 34, 10, 10));
        fill(g, NIGHT, new RoundRectangle2D.Double(720, 640, 60, 34, 10, 10));
        // Game screen furniture: a minimap and a boost meter.
        stroke(g, LINE, 5, new Ellipse2D.Double(40, 40, 140, 140));
        g.setColor(c(DUSK, 220));
        g.fill(new Ellipse2D.Double(42, 42, 136, 136));
        stroke(g, LINE, 3, new QuadCurve2D.Double(70, 160, 110, 60, 150, 70));
        fill(g, MARIGOLD, new Ellipse2D.Double(102, 102, 16, 16));
        for (int i = 0; i < 8; i++) {
            g.setColor(i < 5 ? c(MARIGOLD) : c(LINE));
            g.fill(new RoundRectangle2D.Double(1080 + i * 22, 640 - i * 6, 14, 30 + i * 6, 4, 4));
        }
    }

    static void podcast(Graphics2D g, int w, int h) {
        fill(g, NIGHT, new java.awt.Rectangle(0, 0, w, h));
        // Acoustic panels on the wall.
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 2; j++) {
                RoundRectangle2D p = new RoundRectangle2D.Double(110 + i * 120, 90 + j * 120, 100, 100, 10, 10);
                fill(g, DUSK, p);
                stroke(g, LINE, 4, p);
            }
        }
        // Sound waves.
        int[] bars = {40, 90, 150, 110, 200, 130, 80, 170, 120, 60, 100, 50};
        for (int i = 0; i < bars.length; i++) {
            g.setColor(i % 3 == 1 ? c(MARIGOLD_SOFT) : c(LINE));
            g.fill(new RoundRectangle2D.Double(130 + i * 30, 430 - bars[i] / 2.5, 14, bars[i] / 1.25, 8, 8));
        }
        // Desk.
        fill(g, DUSK, new java.awt.Rectangle(0, 560, w, h - 560));
        fill(g, LINE, new java.awt.Rectangle(0, 552, w, 14));
        // Boom arm and the microphone (the subject).
        stroke(g, LINE, 12, new java.awt.geom.Line2D.Double(1180, 552, 1120, 330));
        stroke(g, LINE, 12, new java.awt.geom.Line2D.Double(1120, 330, 750, 300));
        fill(g, LINE, new RoundRectangle2D.Double(1150, 540, 70, 18, 8, 8));
        RoundRectangle2D mic = new RoundRectangle2D.Double(640, 230, 110, 220, 110, 110);
        fill(g, MARIGOLD, mic);
        g.setColor(c(NIGHT, 120));
        g.setStroke(new BasicStroke(4));
        for (int i = 0; i < 6; i++) {
            g.draw(new java.awt.geom.Line2D.Double(660, 270 + i * 22, 730, 270 + i * 22));
        }
        stroke(g, LINE, 10, new Arc2D.Double(615, 300, 160, 190, 180, 180, Arc2D.OPEN));
        stroke(g, LINE, 10, new java.awt.geom.Line2D.Double(695, 490, 695, 520));
        // Pop filter.
        stroke(g, LINE, 6, new Ellipse2D.Double(520, 250, 90, 130));
        g.setColor(c(DUSK, 160));
        g.fill(new Ellipse2D.Double(523, 253, 84, 124));
        stroke(g, LINE, 4, new QuadCurve2D.Double(565, 380, 600, 450, 650, 440));
        // Mug and a pair of headphones.
        fill(g, MARIGOLD_SOFT, new RoundRectangle2D.Double(960, 480, 60, 76, 14, 14));
        stroke(g, MARIGOLD_SOFT, 8, new Arc2D.Double(1004, 498, 32, 36, -90, 180, Arc2D.OPEN));
        stroke(g, LINE, 12, new Arc2D.Double(820, 480, 110, 110, 0, 180, Arc2D.OPEN));
        fill(g, LINE, new RoundRectangle2D.Double(808, 520, 28, 40, 12, 12));
        fill(g, LINE, new RoundRectangle2D.Double(914, 520, 28, 40, 12, 12));
    }

    static void study(Graphics2D g, int w, int h) {
        fill(g, NIGHT, new java.awt.Rectangle(0, 0, w, h));
        // Window, before dawn.
        RoundRectangle2D win = new RoundRectangle2D.Double(860, 150, 300, 300, 16, 16);
        fill(g, DUSK, win);
        java.awt.Shape oldClip = g.getClip();
        g.setClip(win);
        stars(g, 21, 26, 860, 150, 1160, 450);
        Path2D roofs = new Path2D.Double();
        roofs.moveTo(860, 450);
        roofs.lineTo(860, 380);
        roofs.lineTo(920, 380);
        roofs.lineTo(920, 350);
        roofs.lineTo(990, 350);
        roofs.lineTo(990, 400);
        roofs.lineTo(1060, 400);
        roofs.lineTo(1060, 330);
        roofs.lineTo(1120, 330);
        roofs.lineTo(1120, 390);
        roofs.lineTo(1160, 390);
        roofs.lineTo(1160, 450);
        roofs.closePath();
        fill(g, LINE, roofs);
        g.setColor(c(MARIGOLD_SOFT, 200));
        for (int[] wl : new int[][]{{935, 365}, {1003, 412}, {1075, 345}, {1090, 370}}) {
            g.fill(new java.awt.Rectangle(wl[0], wl[1], 10, 12));
        }
        g.setClip(oldClip);
        stroke(g, LINE, 12, win);
        stroke(g, LINE, 8, new java.awt.geom.Line2D.Double(1010, 150, 1010, 450));
        // Desk.
        fill(g, DUSK, new java.awt.Rectangle(0, 560, w, h - 560));
        fill(g, LINE, new java.awt.Rectangle(0, 552, w, 14));
        // Lamp light and lamp (the subject).
        Path2D cone = new Path2D.Double();
        cone.moveTo(610, 300);
        cone.lineTo(680, 300);
        cone.lineTo(860, 556);
        cone.lineTo(430, 556);
        cone.closePath();
        g.setColor(c(MIST, 16));
        g.fill(cone);
        stroke(g, LINE, 10, new java.awt.geom.Line2D.Double(760, 548, 760, 330));
        stroke(g, LINE, 10, new java.awt.geom.Line2D.Double(760, 330, 690, 280));
        fill(g, LINE, new RoundRectangle2D.Double(720, 540, 90, 16, 10, 10));
        Path2D shade = new Path2D.Double();
        shade.moveTo(610, 300);
        shade.lineTo(645, 245);
        shade.lineTo(705, 268);
        shade.lineTo(690, 318);
        shade.closePath();
        fill(g, MARIGOLD, shade);
        // Open notebook and pencil.
        Path2D book = new Path2D.Double();
        book.moveTo(470, 548);
        book.lineTo(500, 500);
        book.lineTo(640, 510);
        book.lineTo(780, 500);
        book.lineTo(800, 548);
        book.closePath();
        fill(g, MIST, book);
        stroke(g, LINE, 3, new java.awt.geom.Line2D.Double(640, 510, 640, 548));
        g.setColor(c(LINE));
        g.setStroke(new BasicStroke(3));
        for (int i = 0; i < 3; i++) {
            g.draw(new java.awt.geom.Line2D.Double(520 + i * 4, 518 + i * 9, 620, 522 + i * 9));
            g.draw(new java.awt.geom.Line2D.Double(660, 522 + i * 9, 760 - i * 4, 518 + i * 9));
        }
        stroke(g, MARIGOLD, 8, new java.awt.geom.Line2D.Double(700, 552, 800, 532));
        // A stack of books.
        int[] widths = {220, 200, 240, 190};
        int[] colours = {LINE, MARIGOLD_SOFT, DUSK, LINE};
        for (int i = 0; i < widths.length; i++) {
            RoundRectangle2D b = new RoundRectangle2D.Double(150 + (240 - widths[i]) / 2.0, 520 - i * 34, widths[i], 32, 6, 6);
            fill(g, colours[i], b);
            if (colours[i] == DUSK) {
                stroke(g, LINE, 3, b);
            }
        }
        fill(g, MARIGOLD_SOFT, new RoundRectangle2D.Double(1000, 486, 60, 70, 14, 14));
    }

    static void brb(Graphics2D g, int w, int h) {
        // LiveFeed's Be right back scene with no background image: the template's scene colour.
        int palette = Integer.parseInt(NicheTemplates.find("phone-gaming").orElseThrow().palette().substring(1), 16);
        fill(g, palette, new java.awt.Rectangle(0, 0, w, h));
    }

    /** Rotated fills for the few tilted shapes. */
    private static final class AffineRotate {
        static void fill(Graphics2D g, Shape s, double radians, double cx, double cy, int rgb) {
            Shape r = java.awt.geom.AffineTransform.getRotateInstance(radians, cx, cy).createTransformedShape(s);
            SiteFrames.fill(g, rgb, r);
        }
    }

    /** An example QR code for the "scan it on your phone" step, as SVG modules (no text, no colour). */
    public static String qrSvg(String content) throws com.google.zxing.WriterException {
        com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter().encode(content,
                com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                Map.of(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                        com.google.zxing.EncodeHintType.MARGIN, 0));
        StringBuilder d = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) {
                    d.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                + "\" shape-rendering=\"crispEdges\"><path fill=\"#10122A\" d=\"" + d + "\"/></svg>\n";
    }
}
