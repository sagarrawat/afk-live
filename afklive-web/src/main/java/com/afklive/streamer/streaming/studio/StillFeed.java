package com.afklive.streamer.streaming.studio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A still picture (an image, or a plain colour) with silence: backgrounds and the always-ready fallback. */
public final class StillFeed implements Feed {

    private final byte[] picture;

    private StillFeed(byte[] picture) {
        this.picture = picture;
    }

    public static StillFeed color(Canvas canvas, int rgb) {
        byte[] video = new byte[canvas.frameBytes()];
        Frame.fill(video, canvas, rgb);
        return new StillFeed(video);
    }

    /** Decodes an image once, fitted inside the canvas on black (letterboxed, never stretched or cropped). */
    public static StillFeed image(Canvas canvas, Path image, String ffmpeg) throws IOException {
        List<String> cmd = List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-i", image.toString(),
                "-vf", "scale=" + canvas.width() + ":" + canvas.height() + ":force_original_aspect_ratio=decrease,pad="
                        + canvas.width() + ":" + canvas.height() + ":(ow-iw)/2:(oh-ih)/2:color=black,format=yuv420p",
                "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1");
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] data;
        try (InputStream in = p.getInputStream()) {
            data = in.readAllBytes();
        }
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                com.afklive.streamer.streaming.Processes.kill(p);
                throw new IOException("Timed out decoding " + image.getFileName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted decoding " + image.getFileName(), e);
        }
        if (p.exitValue() != 0 || data.length != canvas.frameBytes()) {
            throw new IOException("Not a usable image: " + image.getFileName());
        }
        return new StillFeed(data);
    }

    @Override
    public void next(Frame out) {
        System.arraycopy(picture, 0, out.video, 0, picture.length);
        out.silence();
    }
}
