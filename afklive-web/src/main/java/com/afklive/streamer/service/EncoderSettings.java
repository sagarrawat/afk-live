package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How live video is encoded with x264: the speed preset and a cap on encoder threads. Everything else
 * (bitrate ladder, constant bitrate with filler, keyframe every 2 s, AAC 44.1 kHz) is fixed in
 * {@link FFmpegCommandBuilder#liveEncodeArgs}, because YouTube requires it.
 * <p>
 * The preset only changes how hard x264 searches for a good encoding, except where it changes the shape
 * of the stream: B-frames, reference frames, CABAC and the H.264 profile. Copy mode (FO-2) hands a running
 * stream between profile copies and the studio encoder without reconnecting, so both must send the same
 * shape; {@link #family} groups the presets whose sequence and picture parameter sets (SPS/PPS) are
 * byte-identical for the same canvas and bitrate ({@code EncoderSettingsFfmpegTest} checks this with real
 * FFmpeg). superfast and veryfast are one family, so profile copies made with veryfast before this was
 * configurable stay valid with a superfast studio.
 */
@Slf4j
public record EncoderSettings(String preset, int threads) {

    /** Measured (Oct 2026): about 40% less encoder CPU than veryfast for an SSIM drop of 0.001–0.002. */
    public static final String DEFAULT_PRESET = "superfast";
    /** What the live encoder and profile copies used before the preset was configurable. */
    public static final String LEGACY_PRESET = "veryfast";
    /** Fastest to slowest. Slower than medium costs several cores per stream; never worth it live. */
    public static final List<String> PRESETS = List.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium");
    public static final int MAX_THREADS = 16;
    public static final EncoderSettings DEFAULT = new EncoderSettings(DEFAULT_PRESET, 0);

    /** Checked byte for byte against real x264 output by EncoderSettingsFfmpegTest; add a preset only with it. */
    private static final Map<String, String> FAMILY = Map.of(
            "ultrafast", "ultrafast",  // Constrained Baseline: no B-frames, no CABAC
            "superfast", "high-ref1",  // High, 3 B-frames, 1 reference frame: identical SPS/PPS
            "veryfast", "high-ref1",
            "faster", "faster",        // 2 reference frames
            "fast", "fast",            // 2 reference frames too, but its PPS differs from faster's
            "medium", "medium");       // 3 reference frames

    public EncoderSettings {
        if (preset == null || !PRESETS.contains(preset)) {
            throw new IllegalArgumentException("Unknown x264 preset " + preset + "; use one of " + PRESETS);
        }
        if (threads < 0 || threads > MAX_THREADS) {
            throw new IllegalArgumentException("x264 threads must be 0 (auto) to " + MAX_THREADS + ", not " + threads);
        }
    }

    /**
     * From configuration: an unknown preset or an out-of-range thread count falls back to the default with
     * a warning, so a typo in an environment variable never stops a worker from streaming.
     */
    public static EncoderSettings fromConfig(String preset, String threads) {
        String p = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            p = DEFAULT_PRESET;
        } else if (!PRESETS.contains(p)) {
            log.warn("Unknown x264 preset '{}' (allowed: {}); using {}", preset, PRESETS, DEFAULT_PRESET);
            p = DEFAULT_PRESET;
        }
        int t = 0;
        String raw = threads == null ? "" : threads.trim();
        if (!raw.isEmpty()) {
            try {
                t = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                t = -1;
            }
            if (t < 0 || t > MAX_THREADS) {
                log.warn("x264 threads '{}' is not 0 (auto) to {}; using 0", threads, MAX_THREADS);
                t = 0;
            }
        }
        return new EncoderSettings(p, t);
    }

    /** The same preset with another thread cap (0 = let x264 decide). */
    public EncoderSettings withThreads(int t) {
        return new EncoderSettings(preset, t);
    }

    /**
     * The stream shape a preset produces. Presets in one family can follow each other in one stream
     * (copy mode, crash restarts) with no change a decoder or relay could notice. A null or unknown preset
     * means a profile copy made before the preset was recorded: {@link #LEGACY_PRESET}.
     */
    public static String family(String preset) {
        String p = preset == null ? LEGACY_PRESET : preset.trim().toLowerCase(Locale.ROOT);
        return FAMILY.getOrDefault(p, FAMILY.get(LEGACY_PRESET));
    }

    /** Whether something encoded with {@code otherPreset} (null = legacy) can be spliced into this encoder's stream. */
    public boolean compatibleWith(String otherPreset) {
        return family(preset).equals(family(otherPreset));
    }
}
