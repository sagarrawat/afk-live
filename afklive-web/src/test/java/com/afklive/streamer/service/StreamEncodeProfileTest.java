package com.afklive.streamer.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real stream command through FFmpeg (writing to a file instead of a platform) and checks
 * the output against YouTube's ingest requirements. Skipped when FFmpeg isn't installed.
 */
class StreamEncodeProfileTest {

    @TempDir
    static Path dir;

    static Path silentVideo;
    static Path videoWithAudio;

    @BeforeAll
    static void makeSources() throws Exception {
        assumeTrue(run(List.of("ffmpeg", "-version"), 10) == 0, "ffmpeg not installed");
        silentVideo = dir.resolve("silent.mp4");
        videoWithAudio = dir.resolve("audio.mp4");
        // 25 fps source on purpose: the output must still be 30 fps with 2 s keyframes.
        assertThat(run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", silentVideo.toString()), 60)).isZero();
        assertThat(run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", videoWithAudio.toString()), 60)).isZero();
    }

    @Test
    void silentSourceGetsAnAudioTrack() throws Exception {
        Path out = encode(silentVideo, false, "silent-out.flv");
        String streams = probe(out, "stream=codec_type,codec_name,sample_rate,channels");
        assertThat(streams).contains("audio", "aac", "44100");
        assertThat(streams).contains("video", "h264");
    }

    @Test
    void outputIs30FpsWithKeyframesEveryTwoSeconds() throws Exception {
        Path out = encode(videoWithAudio, false, "fps-out.flv");
        assertThat(probe(out, "stream=avg_frame_rate")).contains("30/1");

        String frames = run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "frame=pts_time,key_frame", "-of", "csv=p=0", out.toString()));
        List<Double> keyframeTimes = new ArrayList<>();
        for (String line : frames.split("\\R")) {
            String[] parts = line.split(",");
            if (parts.length >= 2 && parts[0].trim().equals("1")) {
                keyframeTimes.add(Double.parseDouble(parts[1].trim()));
            }
        }
        assertThat(keyframeTimes).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 1; i < keyframeTimes.size(); i++) {
            assertThat(keyframeTimes.get(i) - keyframeTimes.get(i - 1)).isBetween(1.9, 2.1);
        }
    }

    @Test
    void mutedSourceStillHasAudio() throws Exception {
        Path out = encode(videoWithAudio, true, "muted-out.flv");
        assertThat(probe(out, "stream=codec_type")).contains("audio");
    }

    private static Path encode(Path source, boolean mute, String name) throws Exception {
        boolean hasAudio = source.equals(videoWithAudio);
        Path out = dir.resolve(name);
        List<String> command = new ArrayList<>(FFmpegCommandBuilder.buildStreamCommand(source, List.of("test-key"),
                null, "1.0", -1, null, mute, "original", 720, false, null, hasAudio));
        // Swap the platform output (last three args: -f flv URL) for a 6-second file, without real-time pacing.
        command.subList(command.size() - 3, command.size()).clear();
        command.remove("-re");
        command.addAll(Arrays.asList("-t", "6", "-y", "-f", "flv", out.toString()));
        assertThat(run(command, 120)).as("ffmpeg exit code for %s", command).isZero();
        assertThat(Files.size(out)).isPositive();
        return out;
    }

    private static String probe(Path file, String entries) throws Exception {
        return run(List.of("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=nw=1", file.toString()));
    }

    private static int run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(60, TimeUnit.SECONDS);
        return out;
    }
}
