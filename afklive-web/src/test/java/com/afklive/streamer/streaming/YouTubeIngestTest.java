package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.afklive.streamer.streaming.YouTubeIngest.Ingest.BACKUP;
import static com.afklive.streamer.streaming.YouTubeIngest.Ingest.PRIMARY;
import static org.assertj.core.api.Assertions.assertThat;

/** OPS-3: which ingest a handover publishes to is a pure choice of URL. */
class YouTubeIngestTest {

    private final YouTubeIngest yt = YouTubeIngest.INSTANCE;

    @Test
    void aBareKeyGoesToThePrimaryOrTheBackupServer() {
        assertThat(yt.hasBackup("abcd-efgh-ijkl")).isTrue();
        assertThat(yt.route("abcd-efgh-ijkl", PRIMARY)).isEqualTo("rtmp://a.rtmp.youtube.com:1935/live2/abcd-efgh-ijkl");
        assertThat(yt.route("abcd-efgh-ijkl", BACKUP)).isEqualTo("rtmp://b.rtmp.youtube.com:1935/live2?backup=1/abcd-efgh-ijkl");
    }

    @Test
    void thePrimaryRouteOfABareKeyIsWhatRelaysAlwaysUsed() {
        // Upgrading must not change running relays' commands (a changed command reconnects the destination).
        assertThat(FFmpegCommandBuilder.buildRelayCommand(yt.route("abcd", PRIMARY)))
                .isEqualTo(FFmpegCommandBuilder.buildRelayCommand("abcd"));
    }

    @Test
    void fullYouTubeUrlsSwitchServerAndKeepSchemePortAndKey() {
        assertThat(yt.route("rtmp://a.rtmp.youtube.com/live2/k1", BACKUP)).isEqualTo("rtmp://b.rtmp.youtube.com/live2?backup=1/k1");
        assertThat(yt.route("rtmp://b.rtmp.youtube.com/live2?backup=1/k1", PRIMARY)).isEqualTo("rtmp://a.rtmp.youtube.com/live2/k1");
        assertThat(yt.route("rtmps://a.rtmps.youtube.com:443/live2/k2", BACKUP)).isEqualTo("rtmps://b.rtmps.youtube.com:443/live2?backup=1/k2");
        assertThat(yt.route("rtmps://b.rtmps.youtube.com:443/live2?backup=1/k2", PRIMARY)).isEqualTo("rtmps://a.rtmps.youtube.com:443/live2/k2");
        assertThat(yt.route("RTMP://A.RTMP.YOUTUBE.COM/live2/MixedCaseKey", BACKUP)).isEqualTo("rtmp://b.rtmp.youtube.com/live2?backup=1/MixedCaseKey");
        // Already on the wanted server: unchanged in substance.
        assertThat(yt.route("rtmp://a.rtmp.youtube.com:1935/live2/k3", PRIMARY)).isEqualTo("rtmp://a.rtmp.youtube.com:1935/live2/k3");
        // Routing twice comes back to the start.
        String start = "rtmps://a.rtmps.youtube.com:443/live2/k4";
        assertThat(yt.route(yt.route(start, BACKUP), PRIMARY)).isEqualTo(start);
    }

    @Test
    void otherDestinationsHaveNoBackupAndAreNeverChanged() {
        for (String url : List.of(
                "rtmps://live-api-s.facebook.com:443/rtmp/FB-123",
                "rtmp://live.twitch.tv/app/live_123",
                "srt://example.com:9000?streamid=abc",
                "rtmp://x.rtmp.youtube.com/live2/k", // not one of YouTube's two ingest servers
                "https://a.upload.youtube.com/http_upload_hls?cid=k",
                "rtmp://a.rtmp.youtube.com/live2/k/extra",
                "file:///tmp/out.flv")) {
            assertThat(yt.hasBackup(url)).as(url).isFalse();
            assertThat(yt.route(url, BACKUP)).as(url).isEqualTo(url);
        }
        assertThat(yt.hasBackup(null)).isFalse();
        assertThat(yt.hasBackup(" ")).isFalse();
    }

    @Test
    void theStoredIngestDefaultsToPrimaryAndAlternates() {
        assertThat(YouTubeIngest.Ingest.of(null)).isEqualTo(PRIMARY);
        assertThat(YouTubeIngest.Ingest.of("BACKUP")).isEqualTo(BACKUP);
        assertThat(PRIMARY.other()).isEqualTo(BACKUP);
        assertThat(BACKUP.other()).isEqualTo(PRIMARY);
    }

    @Test
    void aCopyBeingTakenOverOnlyPublishesWhereThereIsABackupIngest() {
        List<StreamSpec.Destination> ds = List.of(
                new StreamSpec.Destination("YouTube", "key-1"),
                new StreamSpec.Destination("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/FB-1"),
                new StreamSpec.Destination("YouTube off", "key-2", true));
        List<List<String>> incoming = StreamWorker.relayCommands(ds, BACKUP, true, yt);
        assertThat(String.join(" ", incoming.get(0))).contains("rtmp://b.rtmp.youtube.com:1935/live2?backup=1/key-1");
        assertThat(incoming.get(1)).isNull();
        assertThat(incoming.get(2)).isNull();

        List<List<String>> owner = StreamWorker.relayCommands(ds, BACKUP, false, yt);
        assertThat(String.join(" ", owner.get(0))).contains("b.rtmp.youtube.com");
        assertThat(String.join(" ", owner.get(1))).contains("FB-1");
        assertThat(owner.get(2)).isNull();
    }
}
