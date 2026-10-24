package com.afklive.streamer.service;

import com.afklive.streamer.service.DestinationInspector.Inspection;
import com.afklive.streamer.service.DestinationInspector.Level;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationInspectorTest {

    static final String YT_KEY = "abcd-efgh-ijkl-mnop-qrst";

    @Test
    void showsTheServerAndOnlyTheLastFourOfTheKeyAsTheCreatorSeesIt() {
        Inspection i = DestinationInspector.inspect("rtmp://a.rtmp.youtube.com/live2/" + YT_KEY);
        assertThat(i.server()).isEqualTo("rtmp://a.rtmp.youtube.com/live2");
        assertThat(i.keyMasked()).isEqualTo("••••qrst");
        assertThat(i.keyLength()).isEqualTo(24);
        assertThat(i.level()).isEqualTo(Level.OK);
        assertThat(i.check()).isEqualTo("Looks right");
    }

    @Test
    void aBareKeyGoesToYouTube() {
        Inspection i = DestinationInspector.inspect(YT_KEY);
        assertThat(i.server()).isEqualTo("rtmp://a.rtmp.youtube.com/live2");
        assertThat(i.keyMasked()).isEqualTo("••••qrst");
    }

    @Test
    void aShortKeyIsNeverShownEvenInPart() {
        assertThat(DestinationInspector.mask("abcdefgh")).isEqualTo("••••");
        assertThat(DestinationInspector.mask("")).isEmpty();
        Inspection i = DestinationInspector.inspect("rtmp://live.example.com/app/secret12");
        assertThat(i.keyMasked()).isEqualTo("••••").doesNotContain("secret");
    }

    @Test
    void catchesTheCommonMistakes() {
        assertThat(DestinationInspector.inspect("").check()).isEqualTo("Empty address");
        // The app's own save-time check (StreamAddresses) words the YouTube cases.
        assertThat(DestinationInspector.inspect("rtmp://a.rtmp.youtube.com/live2/").check()).startsWith("The YouTube stream key is missing");
        assertThat(DestinationInspector.inspect("rtmp://live.example.com/app/").check()).isEqualTo("Empty key");
        // The server address pasted into the key box.
        Inspection nested = DestinationInspector.inspect("rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2");
        assertThat(nested.level()).isEqualTo(Level.BAD);
        assertThat(nested.check()).contains("second server address");
        assertThat(DestinationInspector.inspect("rtmp://a.rtmp.youtube.com/live2/live2").level()).isEqualTo(Level.BAD);
        assertThat(DestinationInspector.inspect("rtmp://live.example.com/app/live.example.com").check()).isEqualTo("Key looks like a server address");
        assertThat(DestinationInspector.inspect("rtmp://a.rtmp.youtube.com/live2/abcd efgh ijkl mnop qrst").check()).isEqualTo("Key has spaces in it");
        Inspection odd = DestinationInspector.inspect("rtmp://a.rtmp.youtube.com/live2/notayoutubekeyatall");
        assertThat(odd.level()).isEqualTo(Level.WARN);
        assertThat(odd.check()).startsWith("Doesn't look like a YouTube key");
        assertThat(DestinationInspector.inspect("rtmp://live.twitch.tv/app/live_123456_abcdefghijklmnop").level()).isEqualTo(Level.OK);
    }

    @Test
    void neverShowsAPasswordOrAQueryString() {
        Inspection i = DestinationInspector.inspect("rtmp://user:hunter2pass@live.example.com/app/stream-key-0123456789?auth=tok3n");
        assertThat(i.server()).isEqualTo("rtmp://••••@live.example.com/app").doesNotContain("hunter2").doesNotContain("tok3n");
        Inspection srt = DestinationInspector.inspect("srt://ingest.example.com:9000?streamid=publish/abcdefghijklmnopqrst&passphrase=topsecretphrase");
        assertThat(srt.server()).isEqualTo("srt://ingest.example.com:9000");
        assertThat(srt.keyMasked()).doesNotContain("efghijklmnop");
        assertThat(DestinationInspector.inspect("srt://ingest.example.com:9000").check()).isEqualTo("No stream id in the address");
    }
}
