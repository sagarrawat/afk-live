package com.afklive.streamer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD-6: every FFmpeg line the worker stores passes {@link Redact#line}. These are lines FFmpeg 4–7
 * actually prints when an output fails, with real-looking keys in them.
 */
class RedactTest {

    static final String KEY = "abcd-efgh-ijkl-mnop-qrst";
    static final String FB_KEY = "FB-1234567890123456-0-AbCdEfGhIjKlMnOp";
    static final String TWITCH_KEY = "live_123456789_AbCdEfGhIjKlMnOpQrStUvWxYz";
    static final String SRT_ID = "#!::r=live/srtSecret9876,m=publish";
    static final String SRT_PASS = "topsecretphrase";
    static final String QS_KEY = "qsKeyValue12345";

    record Case(String address, String line, List<String> mustNotContain) {
    }

    static Stream<Case> realFfmpegLines() {
        String yt = "rtmp://a.rtmp.youtube.com/live2/" + KEY;
        String fb = "rtmps://live-api-s.facebook.com:443/rtmp/" + FB_KEY;
        String tw = "rtmp://live.twitch.tv/app/" + TWITCH_KEY;
        String srt = "srt://ingest.example.com:9000?streamid=" + SRT_ID + "&passphrase=" + SRT_PASS;
        String qs = "rtmp://live.example.com/app?key=" + QS_KEY;
        return Stream.of(
                new Case(yt, "[out#0/flv @ 0x55d5c8a0b2c0] Error opening output " + yt + ": Input/output error", List.of(KEY)),
                new Case(yt, "[flv @ 0x5581a] Error opening output file " + yt + ".", List.of(KEY)),
                new Case(yt, yt + ": Broken pipe", List.of(KEY)),
                new Case(yt, "Could not write header for output file #0 (incorrect codec parameters ?): Input/output error", List.of(KEY)),
                new Case(yt, "[rtmp @ 0x7f2a] Failed to publish 'live2/" + KEY + "'", List.of(KEY)),
                new Case(yt, "[rtmp @ 0x7f2a] Server error: Unknown publish key " + KEY, List.of(KEY)),
                new Case(fb, "[tls @ 0x55e] Error in the pull function.", List.of(FB_KEY)),
                new Case(fb, "[out#0/flv @ 0x55d] Error opening output " + fb + ": Connection reset by peer", List.of(FB_KEY)),
                new Case(tw, "[rtmp @ 0x1] Closing connection to " + tw.replace("rtmp://", "RTMP://"), List.of(TWITCH_KEY)),
                new Case(tw, "Output #0, flv, to '" + tw + "':", List.of(TWITCH_KEY)),
                new Case(srt, "[srt @ 0x560] Connection to " + srt + " failed: Connection setup failure: connection timed out",
                        List.of("srtSecret9876", SRT_PASS)),
                new Case(srt, "[srt @ 0x560] streamid=" + SRT_ID + " rejected", List.of("srtSecret9876")),
                new Case(srt, "[srt @ 0x560] passphrase=" + SRT_PASS + ": wrong length", List.of(SRT_PASS)),
                // FFmpeg sometimes prints the stream id URL-encoded.
                new Case(srt, "[srt @ 0x560] Opening srt%3A//x?streamid=%23%21%3A%3Ar%3Dlive%2FsrtSecret9876%2Cm%3Dpublish",
                        List.of("srtSecret9876")),
                new Case(qs, "[rtmp @ 0x2] Handshaking with live.example.com/app?key=" + QS_KEY + " failed", List.of(QS_KEY)),
                new Case(qs, "[tcp @ 0x3] Connection to tcp://live.example.com:1935?key=" + QS_KEY + "&tcp_nodelay=1 failed: Connection refused",
                        List.of(QS_KEY)),
                // A key nobody told us about still goes when it's a named query value.
                new Case("rtmp://other.example.com/app/x", "[https @ 0x4] Opening 'https://cdn.example.com/hls/index.m3u8?token=unknownTok3n999&sig=s1gnature77' for writing",
                        List.of("unknownTok3n999", "s1gnature77")));
    }

    @ParameterizedTest
    @MethodSource("realFfmpegLines")
    void noKeySurvivesARealFfmpegLine(Case c) {
        String out = Redact.line(c.line(), Redact.secretsOf(c.address()));
        for (String secret : c.mustNotContain()) {
            assertThat(out).doesNotContain(secret);
        }
    }

    @Test
    void cutsEveryStreamUrl() {
        assertThat(Redact.streamKeys("[flv] Error opening output rtmp://a.rtmp.youtube.com/live2/" + KEY + ": I/O error"))
                .isEqualTo("[flv] Error opening output rtmp://[REDACTED] I/O error")
                .doesNotContain(KEY);
        assertThat(Redact.streamKeys("srt://host:9000?streamid=" + KEY)).doesNotContain(KEY);
    }

    @Test
    void cutsAKnownKeyWhereverItAppearsAlone() {
        List<String> secrets = Redact.secretsOf("rtmp://a.rtmp.youtube.com/live2/" + KEY);
        assertThat(Redact.line("[rtmp @ 0x1] Publishing to live2/" + KEY + " failed", secrets))
                .isEqualTo("[rtmp @ 0x1] Publishing to live2/[REDACTED] failed");
        // A bare key (sent to YouTube) is a secret as a whole.
        assertThat(Redact.line("key " + KEY, Redact.secretsOf(KEY))).isEqualTo("key [REDACTED]");
    }

    @Test
    void findsPasswordsAndQueryValues() {
        List<String> s = Redact.secretsOf("srt://user:hunter2pass@ingest.example.com:9000?streamid=publish/mystream&passphrase=topsecretphrase");
        assertThat(s).contains("hunter2pass", "publish/mystream", "topsecretphrase");
        assertThat(Redact.line("passphrase topsecretphrase rejected", s)).isEqualTo("passphrase [REDACTED] rejected");
        assertThat(Redact.line("user hunter2pass denied", s)).isEqualTo("user [REDACTED] denied");
    }

    @Test
    void cutsNamedQueryValuesEvenWithoutKnownSecrets() {
        assertThat(Redact.line("GET /live?key=abc123def456&x=1", null)).isEqualTo("GET /live?key=[REDACTED]&x=1");
        assertThat(Redact.line("streamid=#!::r=a/b,m=publish refused", List.of())).isEqualTo("streamid=[REDACTED] refused");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        List<String> secrets = Redact.secretsOf("rtmp://live.example.com/app/key");
        assertThat(Redact.line("Connection to tcp://live.example.com:1935 failed", secrets)).isEqualTo("Connection to tcp://live.example.com:1935 failed");
        assertThat(Redact.line("frame=  120 fps= 30 q=28.0 size=1024kB keyint=60 bypass=1", secrets))
                .isEqualTo("frame=  120 fps= 30 q=28.0 size=1024kB keyint=60 bypass=1");
        assertThat(Redact.line(null, secrets)).isNull();
        assertThat(Redact.secretsOf(null)).isEmpty();
    }
}
