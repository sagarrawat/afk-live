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
        assertThat(command).contains("libx264", "veryfast", "aac", "yuv420p", "+faststart");
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
        assertThat(command).contains("-map", "0:a?");
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
}
