package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FFmpegCommandBuilderTest {

//    @Test
    void testBuildConversionCommand() {
        Path source = Paths.get("/tmp/input.mp4");
        Path target = Paths.get("/tmp/output.mp4");

        List<String> command = FFmpegCommandBuilder.buildConversionCommand(source, target);

        assertThat(command).contains("ffmpeg", "-i", source.toString(), target.toString());
        assertThat(command).contains("libx264", "veryfast", "aac", "yuv420p", "+faststart");
    }

//    @Test
    void testBuildStreamCommandWithoutMusic() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);

        // Max Height 1080
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, null, null, -1, null, false, "original", 1080);

        assertThat(command).contains("ffmpeg", "-i", videoPath.toString());
        assertThat(command).contains("rtmps://a.rtmp.youtube.com:443/live2/" + streamKey);
        assertThat(command).contains("-map", "0:a?");
        // Should have scaling limit filter now
        assertThat(command.toString()).contains("scale='if(gt(iw,ih),-2,min(iw,1080))'");
    }

//    @Test
    void testBuildStreamCommandWithMusic() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        Path musicPath = Paths.get("/tmp/music.mp3");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);
        String musicVolume = "0.5";

        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, musicPath, musicVolume, 1, null, false, "original", 1080);

        assertThat(command).contains("ffmpeg", "-i", videoPath.toString());
        assertThat(command).contains("-i", musicPath.toString());
        assertThat(command).contains("rtmps://a.rtmp.youtube.com:443/live2/" + streamKey);

        // Check for specific filters/mappings when music is present
        // Updated expectation for complex filter
        assertThat(command.toString()).contains("volume=" + musicVolume);
    }

//    @Test
    void testBuildStreamCommandWithWatermark() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        Path watermarkPath = Paths.get("/tmp/logo.png");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);

        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, null, null, 1, watermarkPath, false, "original", 1080);

        assertThat(command).contains("-i", watermarkPath.toString());
        // Should contain filter complex for overlay
        assertThat(command.toString()).contains("overlay=");
        // Should force re-encode
        assertThat(command).contains("libx264");
    }

//    @Test
    void testBuildStreamCommandWithMuteAndScaling() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        Path musicPath = Paths.get("/tmp/music.mp3");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);

        // Enforce 720p limit even if force_landscape (1080p target)
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, musicPath, "1.0", 1, null, true, "force_landscape", 720);

        // Scaling Check: Should be 1280:720 because 720 < 1080
        assertThat(command.toString()).contains("scale=1280:720");

        // Mute Check (Should not mix [0:a])
        assertThat(command.toString()).doesNotContain("amix");
        // Should use music only
        assertThat(command.toString()).contains("[1:a]volume=1.0[aout]");
    }

//    @Test
    void testBuildStreamCommandWithSilence() {
        Path videoPath = Paths.get("/tmp/video.mp4");
        String streamKey = "live_12345";
        List<String> keys = List.of(streamKey);

        // Mute video audio, but NO music provided
        List<String> command = FFmpegCommandBuilder.buildStreamCommand(videoPath, keys, null, null, -1, null, true, "original", 1080);

        // Should generate silence
        assertThat(command).contains("anullsrc=channel_layout=stereo:sample_rate=44100");
        // Should map silence input (index 1) to audio
        assertThat(command).contains("-map", "1:a");
        // Should loop video
        assertThat(command).contains("-stream_loop", "-1");
    }
}
