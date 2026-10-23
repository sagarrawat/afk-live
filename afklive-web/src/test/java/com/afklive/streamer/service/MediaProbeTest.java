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
}
