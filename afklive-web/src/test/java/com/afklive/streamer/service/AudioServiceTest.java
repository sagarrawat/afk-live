package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class AudioServiceTest {

    @Test
    public void testRainAndThunderTrackExists() {
        AudioService audioService = new AudioService();
        List<Map<String, String>> tracks = audioService.getTrendingTracks();

        boolean found = false;
        for (Map<String, String> track : tracks) {
            if ("rain_thunder_1".equals(track.get("id"))) {
                assertEquals("Rain & Thunder", track.get("title"));
                assertEquals("https://actions.google.com/sounds/v1/weather/thunderstorm.ogg", track.get("url"));
                found = true;
                break;
            }
        }

        assertTrue(found, "Rain & Thunder track should exist in trending tracks");
    }
}
