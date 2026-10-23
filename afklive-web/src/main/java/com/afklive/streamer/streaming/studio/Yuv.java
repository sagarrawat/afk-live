package com.afklive.streamer.streaming.studio;

/** Colour conversion and PCM helpers. */
final class Yuv {

    private Yuv() {
    }

    /** RGB (0xRRGGBB) to limited-range BT.601 Y, U, V. */
    static int[] fromRgb(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
        int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
        int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
        return new int[]{clamp(y), clamp(u), clamp(v)};
    }

    static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * Adds {@code src} scaled by {@code gain} into {@code dst} (both s16le), clipping at full scale.
     * With {@code dstGain} the existing content is scaled first.
     */
    static void mix(byte[] dst, double dstGain, byte[] src, double gain) {
        for (int i = 0; i + 1 < dst.length; i += 2) {
            int a = (short) ((dst[i] & 0xff) | (dst[i + 1] << 8));
            int b = (short) ((src[i] & 0xff) | (src[i + 1] << 8));
            int m = (int) Math.round(a * dstGain + b * gain);
            if (m > Short.MAX_VALUE) {
                m = Short.MAX_VALUE;
            } else if (m < Short.MIN_VALUE) {
                m = Short.MIN_VALUE;
            }
            dst[i] = (byte) m;
            dst[i + 1] = (byte) (m >> 8);
        }
    }

    /** Scales s16le samples in place. */
    static void gain(byte[] pcm, double gain) {
        if (gain == 1.0) {
            return;
        }
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int a = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int m = (int) Math.round(a * gain);
            m = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, m));
            pcm[i] = (byte) m;
            pcm[i + 1] = (byte) (m >> 8);
        }
    }
}
