package com.afklive.streamer.streaming.studio;

import java.util.Arrays;

/** One tick of output: a video frame and exactly the audio that plays during it. Reused, never shared across threads. */
public final class Frame {

    public final byte[] video;
    public final byte[] audio;

    public Frame(Canvas canvas) {
        this.video = new byte[canvas.frameBytes()];
        this.audio = new byte[canvas.audioBytesPerFrame()];
    }

    public void copyFrom(Frame other) {
        System.arraycopy(other.video, 0, video, 0, video.length);
        System.arraycopy(other.audio, 0, audio, 0, audio.length);
    }

    public void silence() {
        Arrays.fill(audio, (byte) 0);
    }

    /** Fills the picture with one colour (BT.601 limited range, as FFmpeg's default yuv420p conversion). */
    public static void fill(byte[] video, Canvas canvas, int rgb) {
        int[] yuv = Yuv.fromRgb(rgb);
        Arrays.fill(video, 0, canvas.lumaBytes(), (byte) yuv[0]);
        Arrays.fill(video, canvas.lumaBytes(), canvas.lumaBytes() + canvas.chromaBytes(), (byte) yuv[1]);
        Arrays.fill(video, canvas.lumaBytes() + canvas.chromaBytes(), canvas.frameBytes(), (byte) yuv[2]);
    }
}
