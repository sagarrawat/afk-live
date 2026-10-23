package com.afklive.streamer.streaming.studio;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One overlay layer, positioned in canvas fractions so the same layout works landscape and vertical.
 *
 * @param x        left edge, 0..1 of canvas width
 * @param y        top edge, 0..1 of canvas height (ignored by tickers when {@code bottom} anchoring is used)
 * @param width    IMAGE: width as a fraction of canvas width (height keeps the image's aspect)
 * @param size     text height as a fraction of canvas height (e.g. 0.04)
 * @param text     TEXT and TICKER content; may contain {title} / {next} (the video playlist) and {track} (the music)
 * @param image    IMAGE: a PNG/JPG on this worker
 * @param color    text colour 0xRRGGBB
 * @param background box colour 0xAARRGGBB (alpha 0 = no box)
 * @param opacity  0..1 for the whole layer
 * @param timeZone CLOCK: IANA zone, e.g. Asia/Kolkata
 * @param target   COUNTDOWN: the moment it counts down to
 * @param speed    TICKER: pixels per second at 1080p (scaled to the canvas)
 */
public record Overlay(String id, Type type, boolean visible, double x, double y, double width, double size,
                      String text, Path image, int color, int background, double opacity,
                      String timeZone, Instant target, double speed) {

    public enum Type { IMAGE, TEXT, CLOCK, COUNTDOWN, TICKER }

    public Overlay withVisible(boolean v) {
        return new Overlay(id, type, v, x, y, width, size, text, image, color, background, opacity, timeZone, target, speed);
    }

    public static Overlay image(String id, Path image, double x, double y, double width, double opacity) {
        return new Overlay(id, Type.IMAGE, true, x, y, width, 0, null, image, 0xffffff, 0, opacity, null, null, 0);
    }

    public static Overlay text(String id, String text, double x, double y, double size, int color, int background) {
        return new Overlay(id, Type.TEXT, true, x, y, 0, size, text, null, color, background, 1, null, null, 0);
    }

    public static Overlay clock(String id, String timeZone, double x, double y, double size) {
        return new Overlay(id, Type.CLOCK, true, x, y, 0, size, null, null, 0xffffff, 0x99000000, 1, timeZone, null, 0);
    }

    public static Overlay countdown(String id, String label, Instant target, double x, double y, double size) {
        return new Overlay(id, Type.COUNTDOWN, true, x, y, 0, size, label, null, 0xffffff, 0x99000000, 1, null, target, 0);
    }

    public static Overlay ticker(String id, String text, double y, double size, double speed) {
        return new Overlay(id, Type.TICKER, true, 0, y, 1, size, text, null, 0xffffff, 0xcc000000, 1, null, null, speed);
    }
}
