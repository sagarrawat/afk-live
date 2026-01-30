package com.afklive.streamer.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@Service
public class AudioService {

    private final RestTemplate restTemplate = new RestTemplate();

    // Mock Trending Tracks
    public List<Map<String, String>> getTrendingTracks() {
        return List.of(
            // Royalty Free (Mixable)
            Map.of(
                "id", "royalty_1",
                "type", "ROYALTY_FREE",
                "title", "Summer Vibes",
                "artist", "Stock Library",
                "cover", "https://ui-avatars.com/api/?name=SV&background=FFD700",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            ),
            Map.of(
                "id", "royalty_2",
                "type", "ROYALTY_FREE",
                "title", "Lofi Study",
                "artist", "Chill Beats",
                "cover", "https://ui-avatars.com/api/?name=LS&background=A0C4FF",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"
            ),
            // Trending (Deep Link Only)
            Map.of(
                "id", "trending_1",
                "type", "TRENDING",
                "title", "Viral Song 2026",
                "artist", "Famous Artist",
                "cover", "https://ui-avatars.com/api/?name=VS&background=FF0000&color=fff",
                "ytUrl", "https://www.youtube.com/shorts/audio/1A2B3C4D"
            ),
            Map.of(
                "id", "trending_2",
                "type", "TRENDING",
                "title", "Dance Hit",
                "artist", "Pop Star",
                "cover", "https://ui-avatars.com/api/?name=DH&background=000000&color=fff",
                "ytUrl", "https://www.youtube.com/shorts/audio/5E6F7G8H"
            ),
            // Additional Stock Music
            Map.of(
                "id", "royalty_3",
                "type", "ROYALTY_FREE",
                "title", "Electronic Upbeat",
                "artist", "ElectroWorld",
                "cover", "https://ui-avatars.com/api/?name=EU&background=7B1FA2&color=fff",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3"
            ),
            Map.of(
                "id", "royalty_4",
                "type", "ROYALTY_FREE",
                "title", "Calm Piano",
                "artist", "Peaceful Mind",
                "cover", "https://ui-avatars.com/api/?name=CP&background=00796B&color=fff",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-16.mp3"
            ),
            Map.of(
                "id", "royalty_5",
                "type", "ROYALTY_FREE",
                "title", "Cinematic Epic",
                "artist", "Movie Scores",
                "cover", "https://ui-avatars.com/api/?name=CE&background=C62828&color=fff",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-10.mp3"
            ),
            Map.of(
                "id", "rain_thunder_1",
                "type", "ROYALTY_FREE",
                "title", "Rain & Thunder",
                "artist", "Nature Sounds",
                "cover", "https://ui-avatars.com/api/?name=RT&background=5D4037&color=fff",
                "url", "https://actions.google.com/sounds/v1/weather/thunderstorm.ogg"
            )
        );
    }

    public Path getAudioPath(String trackId) throws IOException {
        String url = getTrendingTracks().stream()
                .filter(t -> t.get("id").equals(trackId))
                .findFirst()
                .map(t -> t.get("url"))
                .orElseThrow(() -> new IllegalArgumentException("Invalid Track ID"));

        if (url == null) {
            throw new IllegalArgumentException("This track cannot be mixed (Trending Audio). Please use the YouTube App.");
        }

        Path tempFile = Files.createTempFile("audio_" + trackId, ".mp3");
        // Download
        restTemplate.execute(url, org.springframework.http.HttpMethod.GET, null, clientHttpResponse -> {
            Files.copy(clientHttpResponse.getBody(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        });
        return tempFile;
    }
}
