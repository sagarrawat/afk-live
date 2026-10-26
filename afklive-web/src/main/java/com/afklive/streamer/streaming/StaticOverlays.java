package com.afklive.streamer.streaming;

import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.streaming.studio.Overlay;
import com.afklive.streamer.streaming.studio.OverlayCompositor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FO-2: which overlays a profile copy can carry. A copy is a file made once, so it can only hold what
 * never changes while a stream runs: the Free plan's mark, fixed text and a logo at a fixed place, size
 * and opacity. A clock, a countdown, a scrolling ticker, or text with a placeholder ({title}, {next},
 * {track}) changes as the stream plays, so a run that shows one stays in the studio.
 * <p>
 * The overlays a copy carries have an identity ({@link #identity}): a hash of everything that decides
 * how they look on this canvas. It is part of the copy's key, so a copy with the mark is never played
 * for a run without it (after an upgrade) or the other way round, and a moved logo gets new copies.
 */
public final class StaticOverlays {

    /** Bump when OverlayCompositor draws text or images differently: copies are made again. */
    static final String RENDER_VERSION = "1";
    /** A placeholder the studio fills in as the stream plays (it only knows a few; any is treated as one). */
    private static final Pattern TOKEN = Pattern.compile("\\{[^{}]*}");

    private StaticOverlays() {
    }

    /**
     * Why the overlays on the LIVE scene need the studio, in a few words; null when every overlay that
     * shows there is static (or there are none). Hidden overlays don't count: showing one is a live change,
     * which brings the run back to the studio anyway.
     */
    public static String dynamicReason(StudioSpec s) {
        if (s == null || s.overlays() == null) {
            return null;
        }
        for (StudioSpec.OverlaySetting o : s.overlays()) {
            if (!onLive(o) || !o.visible()) {
                continue;
            }
            Overlay.Type type = typeOf(o);
            if (type == null) {
                continue; // the studio skips types it doesn't know
            }
            switch (type) {
                case CLOCK -> {
                    return "a clock overlay";
                }
                case COUNTDOWN -> {
                    return "a countdown overlay";
                }
                case TICKER -> {
                    return "a scrolling ticker";
                }
                case TEXT -> {
                    java.util.regex.Matcher m = o.text() == null ? null : TOKEN.matcher(o.text());
                    if (m != null && m.find()) {
                        return "overlay text that changes (" + m.group() + ")";
                    }
                }
                case IMAGE -> {
                    // a still image, drawn the same every frame
                }
            }
        }
        return null;
    }

    /**
     * The overlays a copy of this run carries, in the order the studio draws them (every-scene overlays,
     * then the LIVE scene's): visible ones that draw something. Empty when there are none. Only meaningful
     * when {@link #dynamicReason} is null.
     */
    public static List<StudioSpec.OverlaySetting> staticSet(StudioSpec s) {
        List<StudioSpec.OverlaySetting> global = new ArrayList<>();
        List<StudioSpec.OverlaySetting> live = new ArrayList<>();
        if (s == null || s.overlays() == null) {
            return List.of();
        }
        for (StudioSpec.OverlaySetting o : s.overlays()) {
            if (!onLive(o) || !o.visible()) {
                continue;
            }
            Overlay.Type type = typeOf(o);
            boolean draws = type == Overlay.Type.IMAGE ? o.imageKey() != null
                    : type == Overlay.Type.TEXT && o.text() != null && !o.text().isBlank();
            if (!draws) {
                continue;
            }
            (o.scene() == null || o.scene().isBlank() ? global : live).add(o);
        }
        global.addAll(live);
        return List.copyOf(global);
    }

    /**
     * The identity of a set of static overlays on a canvas: '' for none, otherwise a SHA-256 (hex) of every
     * input that decides the picture: the canvas, kind, mark or not, position, size, width, text, image
     * (library id and stored file), colours, opacity, font and the drawing code's version.
     */
    public static String identity(List<StudioSpec.OverlaySetting> set, Canvas canvas) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("v").append(RENDER_VERSION).append('|').append(OverlayCompositor.fontName())
                .append('|').append(canvas.width()).append('x').append(canvas.height());
        for (StudioSpec.OverlaySetting s : set) {
            // As the studio draws it: defaults and colours resolved the same way (StudioBuilder.overlay).
            Overlay o = StudioBuilder.overlay(s, s.imageKey() == null ? null : Path.of("image"));
            if (o == null) {
                continue;
            }
            b.append("\n").append(o.type())
                    .append('|').append(OverlayCompositor.WATERMARK_ID.equals(o.id()) ? "mark" : "")
                    .append('|').append(num(o.x())).append(',').append(num(o.y()))
                    .append('|').append(num(o.width())).append(',').append(num(o.size()))
                    .append('|').append(o.type() == Overlay.Type.TEXT ? escape(o.text()) : "")
                    .append('|').append(o.type() == Overlay.Type.IMAGE ? s.imageMediaId() + ":" + escape(s.imageKey()) : "")
                    .append('|').append(Integer.toHexString(o.color())).append(',').append(Integer.toHexString(o.background()))
                    .append('|').append(num(o.opacity()));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Draws the set with the studio's own compositor into one yuva420p frame at the canvas size and writes
     * it to {@code out} (raw planes Y, U, V, A), for the profile copy's overlay filter. {@code images}
     * gives the local file of each image overlay's stored key.
     */
    public static void writeLayer(List<StudioSpec.OverlaySetting> set, java.util.function.Function<String, Path> images,
                                  Canvas canvas, Path out) throws java.io.IOException {
        List<Overlay> overlays = new ArrayList<>();
        for (StudioSpec.OverlaySetting s : set) {
            Path image = s.imageKey() == null ? null : images.apply(s.imageKey());
            if (s.imageKey() != null && (image == null || javax.imageio.ImageIO.read(image.toFile()) == null)) {
                throw new java.io.IOException("Overlay image " + s.imageMediaId() + " couldn't be read");
            }
            Overlay o = StudioBuilder.overlay(s, image);
            if (o != null) {
                overlays.add(o);
            }
        }
        OverlayCompositor compositor = new OverlayCompositor(canvas);
        compositor.setOverlays(overlays);
        java.nio.file.Files.write(out, compositor.renderLayer(java.util.Map.of()));
    }

    /** FFmpeg input arguments for a layer written by {@link #writeLayer}. */
    public static List<String> layerInput(Path layer, Canvas canvas) {
        return List.of("-f", "rawvideo", "-pixel_format", "yuva420p", "-video_size", canvas.width() + "x" + canvas.height(),
                "-framerate", String.valueOf(canvas.fps()), "-i", layer.toString());
    }

    private static boolean onLive(StudioSpec.OverlaySetting o) {
        return o.scene() == null || o.scene().isBlank() || StudioSpec.LIVE.equals(o.scene());
    }

    private static Overlay.Type typeOf(StudioSpec.OverlaySetting o) {
        try {
            return o.type() == null ? null : Overlay.Type.valueOf(o.type());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.6f", d);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n");
    }
}
