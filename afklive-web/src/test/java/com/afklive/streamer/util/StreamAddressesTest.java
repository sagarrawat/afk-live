package com.afklive.streamer.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "rtmp://a.rtmp.youtube.com/live2/abcd-efgh-ijkl-mnop-qrst",
            "rtmps://a.rtmps.youtube.com:443/live2/abcd-efgh-ijkl-mnop-qrst",
            "rtmp://b.rtmp.youtube.com/live2?backup=1/abcd-efgh-ijkl-mnop-qrst",
            "abcd-efgh-ijkl-mnop-qrst",
            "rtmps://live-api-s.facebook.com:443/rtmp/FB-123-0-Ab?s_bl=1&s_sc=2",
            "rtmp://live.twitch.tv/app/live_123_abc",
            "srt://ingest.example.com:9000?streamid=abc&passphrase=x",
            "rtmp://live.example.com/app/key"
    })
    void acceptsWorkingAddresses(String address) {
        assertThatCode(() -> StreamAddresses.requireWellFormed(address)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The production incident: the server address pasted into the key box.
            "rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2",
            "rtmps://live-api-s.facebook.com:443/rtmp/rtmps://live-api-s.facebook.com:443/rtmp",
            "rtmp://live.twitch.tv/app/RTMP://live.twitch.tv/app",
            "rtmp://live.example.com/app/srt://other.example.com:9000"
    })
    void rejectsAnAddressInsideAnAddress(String address) {
        assertThatThrownBy(() -> StreamAddresses.requireWellFormed(address))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StreamAddresses.NESTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rtmp://a.rtmp.youtube.com/live2",
            "rtmp://a.rtmp.youtube.com/live2/",
            "rtmp://a.rtmp.youtube.com/live2/live2",
            "rtmp://a.rtmp.youtube.com/live2/LIVE2",
            "rtmps://a.rtmps.youtube.com:443/live2",
            "rtmp://b.rtmp.youtube.com/live2?backup=1",
            "rtmp://a.rtmp.youtube.com",
            "rtmp://a.rtmp.youtube.com/",
            "live2"
    })
    void rejectsYouTubeAddressesWithoutAKey(String address) {
        assertThatThrownBy(() -> StreamAddresses.requireWellFormed(address))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StreamAddresses.YOUTUBE_NO_KEY);
    }
}
