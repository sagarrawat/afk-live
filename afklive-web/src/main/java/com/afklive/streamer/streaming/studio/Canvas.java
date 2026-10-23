package com.afklive.streamer.streaming.studio;

/**
 * The fixed output format of a studio: every source is converted to this before it reaches the
 * encoder, so the encoder never sees a change and never has to restart.
 * Video is raw yuv420p, audio is signed 16-bit little-endian stereo PCM.
 */
public record Canvas(int width, int height, int fps, int sampleRate) {

    public static final int CHANNELS = 2;
    public static final int BYTES_PER_SAMPLE = 2;

    public Canvas {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Canvas size must be positive and even: " + width + "x" + height);
        }
        if (fps <= 0 || sampleRate % fps != 0) {
            throw new IllegalArgumentException("Sample rate must divide evenly into frames: " + sampleRate + "/" + fps);
        }
    }

    /** Landscape 16:9 or vertical 9:16 at the given height class (720, 1080, ...). */
    public static Canvas of(int heightClass, boolean vertical, int fps) {
        int shortSide = heightClass;
        int longSide = even(Math.round(heightClass * 16f / 9f));
        return vertical ? new Canvas(shortSide, longSide, fps, 44100) : new Canvas(longSide, shortSide, fps, 44100);
    }

    private static int even(int v) {
        return v % 2 == 0 ? v : v + 1;
    }

    public int lumaBytes() {
        return width * height;
    }

    public int chromaBytes() {
        return (width / 2) * (height / 2);
    }

    public int frameBytes() {
        return lumaBytes() + 2 * chromaBytes();
    }

    public int samplesPerFrame() {
        return sampleRate / fps;
    }

    public int audioBytesPerFrame() {
        return samplesPerFrame() * CHANNELS * BYTES_PER_SAMPLE;
    }

    public long frameNanos() {
        return 1_000_000_000L / fps;
    }
}
