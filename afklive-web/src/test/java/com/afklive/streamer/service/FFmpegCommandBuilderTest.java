package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FFmpegCommandBuilderTest {

    @Test
    void testBuildConversionCommand() {
        Path source = Paths.get("/tmp/input.mp4");
        Path target = Paths.get("/tmp/output.mp4");

        List<String> command = FFmpegCommandBuilder.buildConversionCommand(source, target);

        assertThat(command).contains("ffmpeg", "-i", source.toString(), target.toString());
        assertThat(command).contains("libx264", "ultrafast", "aac", "yuv420p", "+faststart");
    }

    @Test
    void testBuildStreamCommandWithoutMusic() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);

        // Max Height 1080
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, null, null, -1, null, false, "original", 1080);

        assertThat(command).contains("ffmpeg", "-i", videoPath.toString());
        assertThat(command.toString()).contains("rtmp://a.rtmp.youtube.com:1935/live2/" + streamKey);
        assertThat(command).contains("-map", "0:a?"); // audio presence unknown: optional map
        // Scaling dynamic
        assertThat(command.toString()).contains("scale=-2:min(ih\\,1080)");
    }

    @Test
    void testBuildStreamCommandWithMusic() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        Path musicPath = Paths.get("/tmp/music.mp3");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);
        String musicVolume = "0.5";

        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, musicPath, musicVolume, 1, null, false, "original", 1080);

        assertThat(command).contains("ffmpeg", "-i", videoPath.toString());
        assertThat(command).contains("-i", musicPath.toString());
        assertThat(command.toString()).contains("rtmp://a.rtmp.youtube.com:1935/live2/" + streamKey);

        // Check for specific filters/mappings when music is present
        assertThat(command.toString()).contains("volume=" + musicVolume);
        assertThat(command.toString()).contains("amix=inputs=2");
    }

    @Test
    void testBuildStreamCommand4K() {
        Path videoPath = Paths.get("in.mp4");
        List<String> keys = List.of("key");

        // 4K limit
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, null, null, -1, null, false, "original", 2160);

        assertThat(command.toString()).contains("scale=-2:min(ih\\,2160)");
        // Check for high bitrate
        assertThat(command).contains("15000k"); // 4k bitrate
    }

    @Test
    void testBuildOptimizeCommand() {
        Path input = Paths.get("in.mp4");
        Path output = Paths.get("out.mp4");

        List<String> command = FFmpegCommandBuilder.buildOptimizeCommand(input, output, "portrait", 1920);
        assertThat(command).contains("-vf");
        // Should contain portrait specific filter (gblur)
        assertThat(command.toString()).contains("gblur=sigma=20");
    }

    @Test
    void testBuildProbeCommand() {
        Path input = Paths.get("/tmp/check.mp4");
        List<String> command = FFmpegCommandBuilder.buildProbeCommand(input);

        // It might be "ffprobe" or absolute path depending on environment, but checking endsWith is safe
        assertThat(command.get(0)).satisfiesAnyOf(
                cmd -> assertThat(cmd).isEqualTo("ffprobe"),
                cmd -> assertThat(cmd).endsWith("/ffprobe")
        );

        assertThat(command).contains("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name", input.toString());
    }

    @Test
    void bareKeyThatLooksLikeAFileGoesToYouTubeNotDisk() {
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(java.nio.file.Paths.get("v.mp4"), List.of("rtmp_x.flv"),
                null, null, -1, null, false, "original", 1080);
        assertThat(command.get(command.size() - 1)).isEqualTo("rtmp://a.rtmp.youtube.com:1935/live2/rtmp_x.flv");
    }

    @Test
    void srtDestinationsUseMpegTs() {
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(java.nio.file.Paths.get("v.mp4"), List.of("srt://8.8.8.8:9000?streamid=abc"),
                null, null, -1, null, false, "original", 1080);
        assertThat(command.get(command.size() - 1)).isEqualTo("srt://8.8.8.8:9000?streamid=abc");
        assertThat(command.get(command.size() - 2)).isEqualTo("mpegts");
    }

    @Test
    void musicVolumeCanOnlyBeANumber() {
        assertThat(FFmpegCommandBuilder.safeVolume("0.5")).isEqualTo("0.50");
        assertThat(FFmpegCommandBuilder.safeVolume("5")).isEqualTo("2.00");
        assertThat(FFmpegCommandBuilder.safeVolume("-1")).isEqualTo("0.00");
        assertThat(FFmpegCommandBuilder.safeVolume("1[x];amovie=/etc/passwd")).isEqualTo("1.00");
        assertThat(FFmpegCommandBuilder.safeVolume(null)).isEqualTo("1.00");
    }

    @Test
    void theLiveEncoderUsesTheConfiguredPresetAndKeepsYouTubesMinimums() {
        List<String> args = FFmpegCommandBuilder.liveEncodeArgs(720, 30, EncoderSettings.DEFAULT);
        assertThat(args).containsSequence("-c:v", "libx264", "-preset", "superfast").doesNotContain("-threads:v", "veryfast");
        // Constant bitrate with filler, keyframe every 2 s, AAC 44.1 kHz: unchanged by the preset.
        assertThat(args).containsSequence("-b:v", "4000k", "-minrate", "4000k", "-maxrate", "4000k", "-bufsize", "8000k")
                .containsSequence("-x264-params", "nal-hrd=cbr:force-cfr=1")
                .containsSequence("-g", "60", "-keyint_min", "60", "-sc_threshold", "0")
                .containsSequence("-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2");
        assertThat(FFmpegCommandBuilder.liveEncodeArgs(1080, 30, EncoderSettings.DEFAULT)).containsSequence("-b:v", "6000k");
        assertThat(FFmpegCommandBuilder.liveEncodeArgs(1080, 60, EncoderSettings.DEFAULT))
                .containsSequence("-b:v", "9000k").containsSequence("-g", "120", "-keyint_min", "120");
        assertThat(FFmpegCommandBuilder.liveVideoKbps(1080, 30)).isGreaterThanOrEqualTo(5000);
    }

    @Test
    void aThreadCapAppliesToTheVideoEncoderOnly() {
        List<String> args = FFmpegCommandBuilder.liveEncodeArgs(720, 30, new EncoderSettings("veryfast", 2));
        assertThat(args).containsSequence("-preset", "veryfast", "-threads:v", "2").doesNotContain("-threads");
    }

    @Test
    void aStillPictureStillGoesOutAtTheTargetBitrate() throws Exception {
        // Found on production: a meeting recording (little motion) went out at ~0.4 Mbps against a 4 Mbps
        // target, and YouTube Studio warned "lower than the recommended bitrate".
        org.junit.jupiter.api.Assumptions.assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("cbr").resolve("out.ts");
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "color=c=gray:s=1280x720:r=30", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", "6"));
        cmd.addAll(FFmpegCommandBuilder.liveEncodeArgs(720, 30, EncoderSettings.DEFAULT));
        cmd.addAll(java.util.List.of("-f", "mpegts", out.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        double kbps = java.nio.file.Files.size(out) * 8 / 6.0 / 1000;
        assertThat(kbps).as("output bitrate of a still picture").isGreaterThan(3000);
    }
}
