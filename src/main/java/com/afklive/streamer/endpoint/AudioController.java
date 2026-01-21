package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AudioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private final AudioService audioService;

    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

    @GetMapping("/trending")
    public ResponseEntity<?> getTrendingAudio() {
        return ResponseEntity.ok(audioService.getTrendingTracks());
    }
}
