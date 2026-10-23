package com.afklive.streamer.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactTest {

    static final String KEY = "abcd-efgh-ijkl-mnop-qrst";

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
    }

    @Test
    void leavesOrdinaryTextAlone() {
        List<String> secrets = Redact.secretsOf("rtmp://live.example.com/app/key");
        assertThat(Redact.line("Connection to tcp://live.example.com:1935 failed", secrets)).isEqualTo("Connection to tcp://live.example.com:1935 failed");
        assertThat(Redact.line(null, secrets)).isNull();
        assertThat(Redact.secretsOf(null)).isEmpty();
    }
}
