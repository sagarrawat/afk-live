package com.afklive.streamer.streaming;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DS-5: the destination list a TARGETS command carries, and how a worker turns it into relays. */
class LiveDestinationsTest {

    @Test
    void turnedOffDestinationsKeepTheirPlaceAndGetNoRelay() {
        List<StreamSpec.Destination> list = List.of(
                new StreamSpec.Destination("YouTube", "rtmp://a.rtmp.youtube.com/live2/abcd", true),
                new StreamSpec.Destination("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/efgh"));
        List<StreamSpec.Destination> back = StreamSpec.destinationsFromJson(StreamSpec.destinationsJson(list));
        assertThat(back).isEqualTo(list);

        List<List<String>> relays = StreamWorker.relayCommands(back);
        assertThat(relays).hasSize(2);
        assertThat(relays.get(0)).isNull();
        assertThat(String.join(" ", relays.get(1))).contains("efgh");
    }

    @Test
    void specsWrittenBeforeThisChangeReadAsAllOn() {
        List<StreamSpec.Destination> old = StreamSpec.destinationsFromJson("[{\"name\":\"YouTube\",\"url\":\"abcd-efgh\"}]");
        assertThat(old.getFirst().off()).isFalse();
    }
}
