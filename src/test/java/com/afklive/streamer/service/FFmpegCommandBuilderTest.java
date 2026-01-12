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
        assertThat(command).contains("libx264", "ultrafast", "aac");
    }

    @Test
    void testBuildStreamCommandWithoutMusic() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        String streamKey = "live_12345";

        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, streamKey, null, null);

        assertThat(command).contains("ffmpeg", "-i", videoPath.toString());
        assertThat(command).contains("rtmps://a.rtmp.youtube.com:443/live2/" + streamKey);
        // We expect default audio handling when no music is present
        assertThat(command).contains("-map", "0:a?");
    }

    @Test
    void testBuildStreamCommandWithMusic() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        Path musicPath = Paths.get("/tmp/music.mp3");
        String streamKey = "live_12345";
        String musicVolume = "0.5";

        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, streamKey, musicPath, musicVolume);

        assertThat(command).contains("ffmpeg", "-i", videoPath.toString());
        assertThat(command).contains("-i", musicPath.toString());
        assertThat(command).contains("rtmps://a.rtmp.youtube.com:443/live2/" + streamKey);

        // Check for specific filters/mappings when music is present
        assertThat(command).contains("volume=" + musicVolume);
        assertThat(command).contains("-map", "1:a:0");
    }
}
