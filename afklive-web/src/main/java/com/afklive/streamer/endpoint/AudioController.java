package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AudioService;
import com.afklive.streamer.service.UserFileService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audio")
@CrossOrigin(origins = "*")
public class AudioController {

    @Autowired
    private AudioService audioService;
    @Autowired
    private UserFileService userFileService;

    @GetMapping("/trending")
    public List<Map<String, String>> getTrending() {
        return audioService.getTrendingTracks();
    }

    @GetMapping("/my-library")
    public ResponseEntity<?> getMyLibrary(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(userFileService.listAudioFiles(SecurityUtils.getEmail(principal)));
    }
}
