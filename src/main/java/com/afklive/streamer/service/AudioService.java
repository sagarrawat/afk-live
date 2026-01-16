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
            Map.of(
                "id", "track_1",
                "title", "Summer Vibes",
                "artist", "Trending Sounds",
                "cover", "https://ui-avatars.com/api/?name=SV&background=FFD700",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3" // Public sample
            ),
            Map.of(
                "id", "track_2",
                "title", "Phonk Drift",
                "artist", "Viral Hits",
                "cover", "https://ui-avatars.com/api/?name=PD&background=000000&color=fff",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"
            ),
            Map.of(
                "id", "track_3",
                "title", "Lofi Study",
                "artist", "Chill Beats",
                "cover", "https://ui-avatars.com/api/?name=LS&background=A0C4FF",
                "url", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"
            )
        );
    }

    public Path getAudioPath(String trackId) throws IOException {
        String url = getTrendingTracks().stream()
                .filter(t -> t.get("id").equals(trackId))
                .findFirst()
                .map(t -> t.get("url"))
                .orElseThrow(() -> new IllegalArgumentException("Invalid Track ID"));

        Path tempFile = Files.createTempFile("audio_" + trackId, ".mp3");
        // Download
        restTemplate.execute(url, org.springframework.http.HttpMethod.GET, null, clientHttpResponse -> {
            Files.copy(clientHttpResponse.getBody(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        });
        return tempFile;
    }
}
