package com.afklive.streamer.streaming.studio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real media for studio tests, made with FFmpeg's test sources. */
final class StudioFixtures {

    static final Canvas CANVAS = new Canvas(320, 180, 30, 44100);

    private StudioFixtures() {
    }

    static void requireFfmpeg() throws Exception {
        Process v = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0, "ffmpeg not installed");
    }

    /** A clip of the given size and length; with a tone at {@code toneHz}, or silent (no audio track) when 0. */
    static Path clip(Path dir, String name, int w, int h, double seconds, int toneHz) throws Exception {
        Path out = dir.resolve(name + ".mp4");
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=" + w + "x" + h + ":rate=25"));
        if (toneHz > 0) {
            cmd.addAll(List.of("-f", "lavfi", "-i", "sine=f=" + toneHz + ":r=48000"));
        }
        cmd.addAll(List.of("-t", String.valueOf(seconds), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"));
        if (toneHz > 0) {
            cmd.addAll(List.of("-c:a", "aac"));
        }
        cmd.add(out.toString());
        run(cmd);
        return out;
    }

    static Path audio(Path dir, String name, double seconds, int toneHz) throws Exception {
        Path out = dir.resolve(name + ".m4a");
        run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=f=" + toneHz + ":r=44100",
                "-t", String.valueOf(seconds), "-c:a", "aac", out.toString()));
        return out;
    }

    static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }

    /** Duration (s) of the given stream type in a media file, from ffprobe. */
    static double duration(Path file, String stream) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", stream, "-show_entries",
                "packet=pts_time,duration_time", "-of", "csv=p=0", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(60, TimeUnit.SECONDS);
        double first = Double.NaN;
        double last = 0;
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split(",");
            if (parts.length < 1 || parts[0].isBlank() || parts[0].equals("N/A")) {
                continue;
            }
            double t = Double.parseDouble(parts[0]);
            double d = parts.length > 1 && !parts[1].isBlank() && !parts[1].equals("N/A") ? Double.parseDouble(parts[1]) : 0;
            if (Double.isNaN(first)) {
                first = t;
            }
            last = Math.max(last, t + d);
        }
        return Double.isNaN(first) ? 0 : last - first;
    }

    /** Largest gap (s) between consecutive video timestamps, except at the very end: a stall or a hole in the output. */
    static double largestVideoGap(Path file) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "packet=pts_time", "-of", "csv=p=0", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(60, TimeUnit.SECONDS);
        List<Double> times = new ArrayList<>();
        for (String line : out.split("\n")) {
            String t = line.trim().replace(",", "");
            if (!t.isBlank() && !t.equals("N/A")) {
                times.add(Double.parseDouble(t));
            }
        }
        times.sort(Double::compare);
        double gap = 0;
        // The last few frames are cut by stopping the encoder mid-GOP (B-frames never emitted): ignore the tail.
        for (int i = 1; i < times.size() - 10; i++) {
            gap = Math.max(gap, times.get(i) - times.get(i - 1));
        }
        return gap;
    }

    static PlaylistFeed.SourceFactory factory(Path workDir) {
        return (item, start, audioOnly) -> new DecoderSource(CANVAS,
                audioOnly ? DecoderSource.Options.audioFile(item.path(), start)
                        : DecoderSource.Options.file(item.path(), item.hasAudio(), start),
                workDir, "ffmpeg", item.id());
    }
}
