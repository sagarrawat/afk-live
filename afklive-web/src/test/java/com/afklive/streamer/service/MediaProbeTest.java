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

    /** GR-9: a "static image" video is told apart from moving pictures, with real FFmpeg. */
    @Test
    void tellsAStillPictureVideoFromAMovingOne() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("still");
        java.nio.file.Path still = dir.resolve("still.mp4");
        java.nio.file.Path moving = dir.resolve("moving.mp4");
        java.nio.file.Path smallMotion = dir.resolve("small-motion.mp4");
        Object[][] files = {
                // One picture held for 12 s (what "image + song" videos are), encoded like an upload.
                {still, "testsrc2=s=1280x720:r=25,trim=end_frame=1,loop=loop=-1:size=1,trim=duration=12"},
                // Moving test pattern.
                {moving, "testsrc2=s=1280x720:r=25:d=12"},
                // A still background with one small moving square (a "lofi" loop with a little animation).
                {smallMotion, "color=c=0x334455:s=1280x720:r=25:d=12[bg];color=c=white:s=80x80:r=25:d=12[sq];"
                        + "[bg][sq]overlay=x='mod(t*100,1200)':y=300"},
        };
        for (Object[] f : files) {
            Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", (String) f[1],
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", f[0].toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0, "ffmpeg not available");
        }
        MediaProbe probe = new MediaProbe();
        assertThat(probe.stillPicture(still.toString(), 12)).isTrue();
        assertThat(probe.stillPicture(moving.toString(), 12)).isFalse();
        assertThat(probe.stillPicture(smallMotion.toString(), 12)).isFalse();
        assertThat(probe.stillPicture(dir.resolve("missing.mp4").toString(), 12)).as("unreadable: unknown, never a guess").isNull();
    }

    @Test
    void framesMatchOnlyWhenNothingMoves() {
        byte[] a = new byte[64 * 36];
        java.util.Arrays.fill(a, (byte) 100);
        byte[] noisy = a.clone();
        noisy[10] = (byte) 104; // compression noise
        byte[] dot = a.clone();
        dot[500] = (byte) 200; // something small moved
        byte[] darker = a.clone();
        java.util.Arrays.fill(darker, (byte) 98);
        assertThat(MediaProbe.sameFrames(java.util.List.of(a, noisy, a))).isTrue();
        assertThat(MediaProbe.sameFrames(java.util.List.of(a, dot))).isFalse();
        assertThat(MediaProbe.sameFrames(java.util.List.of(a, darker))).as("the whole picture changed").isFalse();
    }

    @Test
    void readsTheLastTimestampFromProgress() {
        assertThat(MediaProbe.parseLastTime("frame= 10 time=00:00:01.50 bitrate\nframe= 75 time=00:01:02.04 bitrate")).isEqualTo(62.04);
        assertThat(MediaProbe.parseLastTime("nothing")).isZero();
    }
}
