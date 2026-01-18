package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.SocialChannel;
import com.afklive.streamer.service.ChannelService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    public List<SocialChannel> getChannels(Principal principal) {
        return channelService.getChannels(SecurityUtils.getEmail(principal));
    }

    @PostMapping
    public ResponseEntity<?> addChannel(@RequestBody Map<String, String> body, Principal principal) {
        String name = body.get("name");
        String platform = body.get("platform");
        if (name == null || name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Channel name required"));
        }
        try {
            SocialChannel channel = channelService.addChannel(SecurityUtils.getEmail(principal), name, platform);
            return ResponseEntity.ok(channel);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> removeChannel(@PathVariable Long id, Principal principal) {
        channelService.removeChannel(SecurityUtils.getEmail(principal), id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
