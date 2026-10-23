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
        Phone phone
) {

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
    public record Media(String id, String key, String title, double duration, Boolean hasAudio) {
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
     * @param ingestUrl  where the worker reads the phone's feed (the local ingest server)
     * @param graceSeconds how long BRB may stay on before the stream ends (0: never)
     */
    public record Phone(String ingestUrl, int graceSeconds) {
    }
}
