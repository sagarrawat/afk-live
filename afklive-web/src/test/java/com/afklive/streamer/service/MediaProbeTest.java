package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MediaProbeTest {

    @Test
    void readsVideoAndAudioStreams() throws Exception {
        String json = """
                {"streams":[
                  {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"avg_frame_rate":"30000/1001"},
                  {"codec_type":"audio","codec_name":"aac"}
                ]}""";
        MediaProbe.MediaInfo info = MediaProbe.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(info.videoCodec()).isEqualTo("h264");
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.fps()).isBetween(29.9, 30.0);
        assertThat(info.hasAudio()).isTrue();
    }

    @Test
    void detectsMissingAudioAndIgnoresCoverArt() throws Exception {
        String json = """
                {"streams":[
                  {"codec_type":"video","codec_name":"mjpeg","width":300,"height":300},
                  {"codec_type":"video","codec_name":"hevc","width":1280,"height":720,"avg_frame_rate":"0/0","r_frame_rate":"25/1"}
                ]}""";
        MediaProbe.MediaInfo info = MediaProbe.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(info.videoCodec()).isEqualTo("hevc");
        assertThat(info.height()).isEqualTo(720);
        assertThat(info.fps()).isEqualTo(25.0);
        assertThat(info.hasAudio()).isFalse();
    }

    @Test
    void readsTheIntegratedLoudnessFromTheSummary() {
        String log = """
                [Parsed_ebur128_0 @ 0x1] Summary:

                  Integrated loudness:
                    I:         -18.3 LUFS
                    Threshold: -28.4 LUFS
                """;
        assertThat(MediaProbe.parseLoudness(log)).isEqualTo(-18.3);
        assertThat(MediaProbe.parseLoudness("Summary:\n  I:         -70.0 LUFS")).isNull(); // silence
        assertThat(MediaProbe.parseLoudness("no summary")).isNull();
    }

    @Test
    void measuresARealFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("loud");
        java.nio.file.Path quiet = dir.resolve("quiet.m4a");
        java.nio.file.Path loud = dir.resolve("loud.m4a");
        for (var f : new Object[][]{{quiet, "0.05"}, {loud, "0.5"}}) {
            Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=f=440:r=44100:d=4",
                    "-af", "volume=" + f[1], "-c:a", "aac", f[0].toString()).start();
            org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0, "ffmpeg not available");
        }
        MediaProbe probe = new MediaProbe();
        Double q = probe.loudness(quiet.toString());
        Double l = probe.loudness(loud.toString());
        assertThat(q).isNotNull();
        assertThat(l - q).as("10x the amplitude is 20 dB louder").isBetween(19.0, 21.0);
    }

    @Test
    void readsTheLastTimestampFromProgress() {
        assertThat(MediaProbe.parseLastTime("frame= 10 time=00:00:01.50 bitrate\nframe= 75 time=00:01:02.04 bitrate")).isEqualTo(62.04);
        assertThat(MediaProbe.parseLastTime("nothing")).isZero();
    }
}
