package com.afklive.streamer.streaming.soak;

/**
 * OPS-6 pass/fail limits. Documented in docs/operations.md ("Soak test"); change both together.
 * <p>
 * Anything a viewer or a platform would notice is a hard zero: reconnects, producer crashes, a stream
 * ending, timestamps going backwards. Timing limits come from the spec (A/V drift under 40 ms) and from
 * the continuity limits the stitcher's own tests use. Leak limits are judged only on runs long enough for
 * a slope to mean something; every run checks that stopping returns the process to where it started.
 */
public final class Thresholds {

    private Thresholds() {
    }

    /** One TCP connection per destination for the whole run: a planned switch never reconnects. */
    public static final int CONNECTIONS_PER_DESTINATION = 1;
    /** A/V drift (spec OPS-6): the audio timeline against the video timeline, ms. */
    public static final long AV_DRIFT_MS = 40;
    /** The media timeline against real time, ms: what a platform's buffer has to absorb. */
    public static final long MEDIA_VS_WALL_DRIFT_MS = 2_000;
    /** An encoder progress reading below this speed means it is not keeping up with real time. */
    public static final double SLOW_SPEED = 0.9;
    /** Share of readings allowed below {@link #SLOW_SPEED}. */
    public static final double SLOW_SPEED_SHARE = 0.01;
    /** Frames the studio clock had to drop, as a share of frames sent. */
    public static final double STUDIO_DROPPED_SHARE = 0.005;
    /** Longest a studio↔copy hand-off may take (the worker gives up after 30 s). */
    public static final double SWITCH_SECONDS = 10;
    /** As TsStitcherTest: fewer FFmpeg decode error lines than this in a sample. */
    public static final int SAMPLE_DECODE_ERROR_LINES = 5;

    /** Slopes are judged only over at least this much steady running (after warm-up). */
    public static final double SLOPE_MIN_MINUTES = 30;
    /** Heap retained after a full GC, MB per hour. */
    public static final double HEAP_SLOPE_MB_PER_HOUR = 10;
    /** Worker JVM resident memory, MB per hour. */
    public static final double RSS_SLOPE_MB_PER_HOUR = 50;
    /** All FFmpeg processes' resident memory together, MB per hour. */
    public static final double FFMPEG_RSS_SLOPE_MB_PER_HOUR = 20;
    /** Most open descriptors in the last fifth of the run against the first fifth after warm-up. */
    public static final int FD_GROWTH = 10;
    /** The same for threads. */
    public static final int THREAD_GROWTH = 6;

    /** After every stream stopped: descriptors and threads back near where they were before the first started. */
    public static final int FD_AFTER_STOP = 4;
    public static final int THREADS_AFTER_STOP = 6;
}
