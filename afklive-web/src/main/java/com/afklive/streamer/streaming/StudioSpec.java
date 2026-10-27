package com.afklive.streamer.streaming;

import java.util.List;

/**
 * How a worker builds the studio for one run of a saved stream. Resolved by the web app from the
 * stream's settings: every file is a storage key the owner is allowed to use.
 *
 * @param source       PLAYLIST or PHONE
 * @param videos       the playlist (PLAYLIST source)
 * @param order        IN_ORDER or SHUFFLE
 * @param loops        0 = forever
 * @param music        music playlist under the videos (may be empty)
 * @param audioMode    KEEP, MUTE or MIX (the videos' own sound)
 * @param scenes       STARTING, LIVE, BRB, ENDING; LIVE is the playlist or the phone
 * @param initialScene scene on air when the stream starts
 * @param resume       playlist position to resume from, or null
 * @param phone        phone ingest settings (PHONE source), or null
 * @param finishAfterPass the creator asked to stop when the current pass through the playlist ends
 * @param videoKbps    FO-4: the run's video bitrate (kbps, 60 fps factor included), resolved from the owner's
 *                     plan once at go-live and kept for the whole run (live edits and plan changes never
 *                     change it); null for a run from before it was recorded: the built-in ladder
 */
public record StudioSpec(
        int height,
        boolean vertical,
        int fps,
        String source,
        List<Media> videos,
        String order,
        int loops,
        List<Media> music,
        String musicOrder,
        String audioMode,
        double videoVolume,
        double musicVolume,
        List<OverlaySetting> overlays,
        List<SceneSetting> scenes,
        String initialScene,
        String timeZone,
        Position resume,
        Phone phone,
        boolean finishAfterPass,
        String fit,
        double crossfadeSeconds,
        Integer videoKbps
) {

    /** Without a recorded bitrate (the built-in ladder's applies; the web app sets it at go-live). */
    public StudioSpec(int height, boolean vertical, int fps, String source, List<Media> videos, String order, int loops,
                      List<Media> music, String musicOrder, String audioMode, double videoVolume, double musicVolume,
                      List<OverlaySetting> overlays, List<SceneSetting> scenes, String initialScene, String timeZone,
                      Position resume, Phone phone, boolean finishAfterPass, String fit, double crossfadeSeconds) {
        this(height, vertical, fps, source, videos, order, loops, music, musicOrder, audioMode, videoVolume, musicVolume,
                overlays, scenes, initialScene, timeZone, resume, phone, finishAfterPass, fit, crossfadeSeconds, null);
    }

    /** Without fit (PL-6) and crossfade (AU-3): the stream's fit is FIT and music cuts straight. */
    public StudioSpec(int height, boolean vertical, int fps, String source, List<Media> videos, String order, int loops,
                      List<Media> music, String musicOrder, String audioMode, double videoVolume, double musicVolume,
                      List<OverlaySetting> overlays, List<SceneSetting> scenes, String initialScene, String timeZone,
                      Position resume, Phone phone, boolean finishAfterPass) {
        this(height, vertical, fps, source, videos, order, loops, music, musicOrder, audioMode, videoVolume, musicVolume,
                overlays, scenes, initialScene, timeZone, resume, phone, finishAfterPass, null, 0);
    }

    public StudioSpec withFinishAfterPass() {
        return new StudioSpec(height, vertical, fps, source, videos, order, loops, music, musicOrder, audioMode,
                videoVolume, musicVolume, overlays, scenes, initialScene, timeZone, resume, phone, true, fit, crossfadeSeconds,
                videoKbps);
    }

    /** The same settings with this run's bitrate (FO-4): set at go-live, then carried over every rebuild of the spec. */
    public StudioSpec withVideoKbps(Integer kbps) {
        return new StudioSpec(height, vertical, fps, source, videos, order, loops, music, musicOrder, audioMode,
                videoVolume, musicVolume, overlays, scenes, initialScene, timeZone, resume, phone, finishAfterPass, fit,
                crossfadeSeconds, kbps);
    }

    /**
     * The same settings at another picture size (short side) and frame rate: FO-5 fair use starts a run at 720p 30
     * past 110% of the allowance; every rebuild of a running stream's spec keeps the run's own (quality can't
     * change while live).
     */
    public StudioSpec withQuality(int newHeight, int newFps) {
        return new StudioSpec(newHeight, vertical, newFps, source, videos, order, loops, music, musicOrder, audioMode,
                videoVolume, musicVolume, overlays, scenes, initialScene, timeZone, resume, phone, finishAfterPass, fit,
                crossfadeSeconds, videoKbps);
    }

    /** The bitrate the run encodes at: the recorded one, or the built-in ladder's for a run without one. */
    public int effectiveVideoKbps() {
        return com.afklive.streamer.service.VideoBitrates.recordedOrBuiltIn(videoKbps, height, fps);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** JSON helpers shared by the web app and workers for studio settings and live payloads. */
    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write JSON", e);
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read JSON", e);
        }
    }

    public static <T> T parse(String json, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read JSON", e);
        }
    }

    public static final String LIVE = "LIVE";
    public static final String BRB = "BRB";
    public static final String STARTING = "STARTING";
    public static final String ENDING = "ENDING";

    /** A library file: id for now-playing and resume, storage key to fetch it. */
    /** {@code fit}: this item's own fit (PL-6), or null for the stream's. {@code gainDb}: loudness correction (AU-5). */
    public record Media(String id, String key, String title, double duration, Boolean hasAudio, String fit, double gainDb) {
        public Media(String id, String key, String title, double duration, Boolean hasAudio) {
            this(id, key, title, duration, hasAudio, null, 0);
        }

        public Media(String id, String key, String title, double duration, Boolean hasAudio, String fit) {
            this(id, key, title, duration, hasAudio, fit, 0);
        }
    }

    /**
     * An overlay as the creator set it up. Positions are canvas fractions; colours are #RRGGBB or
     * #AARRGGBB. {@code imageKey} is resolved from the library by the web app; {@code scene} limits it
     * to one scene (null: every scene).
     */
    public record OverlaySetting(String id, String type, boolean visible, double x, double y, double width,
                                 double size, String text, Long imageMediaId, String imageKey, String color,
                                 String background, double opacity, String timeZone, String target,
                                 double speed, String scene) {
    }

    /**
     * A scene's look: a background image or video from the library (looped), or a colour; and whether
     * the stream's music plays under it.
     */
    public record SceneSetting(String name, Long backgroundMediaId, String backgroundKey, String backgroundType,
                               String color, boolean music) {
    }

    public record Position(String itemId, double offsetSeconds, int loopsCompleted) {
    }

    /**
     * @param ingestKey    the phone stream's ingest key; the worker builds the read address from its own
     *                     configuration (the read credentials never go into the run's spec)
     * @param ingestUrl    an explicit read address instead (tests); null in production
     * @param graceSeconds how long BRB may stay on before the stream ends (0: never)
     */
    public record Phone(String ingestKey, String ingestUrl, int graceSeconds) {
    }
}
