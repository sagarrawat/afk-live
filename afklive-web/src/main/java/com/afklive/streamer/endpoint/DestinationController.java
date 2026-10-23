package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.SecurityUtils;
import com.afklive.streamer.util.StreamAddresses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/destinations")
@RequiredArgsConstructor
public class DestinationController {

    private final StreamDestinationRepository destinationRepository;
    private final UserService userService;
    private final com.afklive.streamer.service.DestinationTestService testService;

    @GetMapping
    public ResponseEntity<?> getDestinations(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        User user = userService.getOrCreateUser(SecurityUtils.getEmail(principal));
        return ResponseEntity.ok(destinationRepository.findByUser(user).stream().map(DestinationView::of).toList());
    }

    @PostMapping
    public ResponseEntity<?> createDestination(@RequestBody Map<String, Object> body, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        User user = userService.getOrCreateUser(SecurityUtils.getEmail(principal));

        String name = (String) body.get("name");
        String key = (String) body.get("key");
        String type = (String) body.getOrDefault("type", "RTMP");
        Boolean selected = (Boolean) body.getOrDefault("selected", true);

        if (name == null || key == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Name and Key are required"));
        }
        key = key.trim();
        StreamAddresses.requireWellFormed(key);
        com.afklive.streamer.util.NetworkSafety.requireSafeStreamDestination(key);

        if (!destinationRepository.findByStreamKeyAndUser(key, user).isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Stream key already exists"));
        }

        StreamDestination dest = new StreamDestination(name, key, type, user);
        dest.setSelected(selected);
        StreamDestination saved = destinationRepository.save(dest);

        return ResponseEntity.ok(DestinationView.of(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDestination(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);

        return destinationRepository.findById(id)
                .map(dest -> {
                    if (!dest.getUser().getUsername().equals(email)) {
                        return ResponseEntity.status(403).build();
                    }
                    destinationRepository.delete(dest);
                    return ResponseEntity.ok(ApiResponse.success("Deleted", null));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDestination(@PathVariable Long id, @RequestBody Map<String, Object> body, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);

        return destinationRepository.findById(id)
                .map(dest -> {
                    if (!dest.getUser().getUsername().equals(email)) {
                        return ResponseEntity.status(403).build();
                    }
                    if (body.containsKey("name")) dest.setName((String) body.get("name"));
                    if (body.containsKey("key")) {
                        String newKey = body.get("key") == null ? null : ((String) body.get("key")).trim();
                        StreamAddresses.requireWellFormed(newKey);
                        com.afklive.streamer.util.NetworkSafety.requireSafeStreamDestination(newKey);
                        boolean exists = destinationRepository.findByStreamKeyAndUser(newKey, dest.getUser())
                                .stream()
                                .anyMatch(d -> !d.getId().equals(dest.getId()));

                        if (exists) {
                            return ResponseEntity.badRequest().body(ApiResponse.error("Stream key already exists"));
                        }
                        dest.setStreamKey(newKey);
                    }
                    if (body.containsKey("selected")) dest.setSelected((Boolean) body.get("selected"));

                    return ResponseEntity.ok(DestinationView.of(destinationRepository.save(dest)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** MS-6: ask a stream server to send a 10-second test to the platform; poll the result. */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testDestination(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);
        User user = userService.getOrCreateUser(email);
        return destinationRepository.findByIdAndUserEmail(id, email)
                .<ResponseEntity<?>>map(dest -> {
                    var t = testService.request(user, dest);
                    return ResponseEntity.status(202).body(java.util.Map.of("testId", t.getId(), "status", t.getStatus()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/tests/{testId}")
    public ResponseEntity<?> testResult(@PathVariable Long testId, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        User user = userService.getOrCreateUser(SecurityUtils.getEmail(principal));
        var t = testService.result(user, testId);
        return ResponseEntity.ok(java.util.Map.of("testId", t.getId(), "status", t.getStatus(),
                "ok", t.getStatus() == com.afklive.streamer.model.DestinationTest.Status.OK,
                "message", t.getMessage() == null ? "Testing…" : t.getMessage()));
    }

    /**
     * A destination as the app shows it: the server and the last characters of the key, never the
     * whole key (a leaked key lets anyone stream to the channel).
     */
    public record DestinationView(Long id, String name, String type, String platform, String server, String keyHint,
                                  boolean selected) {
        static DestinationView of(StreamDestination d) {
            String url = d.getStreamKey() == null ? "" : d.getStreamKey();
            String server = url;
            String key = "";
            int slash = url.lastIndexOf('/');
            if (url.contains("://") && slash > url.indexOf("://") + 2) {
                server = url.substring(0, slash);
                key = url.substring(slash + 1);
            }
            if (!url.contains("://")) {
                server = "rtmp://a.rtmp.youtube.com/live2"; // a bare key is a YouTube stream key
                key = url;
            }
            if (url.startsWith("srt://")) {
                server = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
                key = url.contains("streamid=") ? url.substring(url.indexOf("streamid=") + 9) : "";
            }
            String hint = key.length() <= 4 ? (key.isEmpty() ? "" : "••••") : "••••" + key.substring(key.length() - 4);
            String host = server.toLowerCase(java.util.Locale.ROOT);
            String platform = host.contains("youtube.com") ? "YOUTUBE" : host.contains("facebook.com") ? "FACEBOOK"
                    : host.contains("instagram.com") || host.contains("fbcdn.net") ? "INSTAGRAM" : host.contains("twitch.tv") ? "TWITCH"
                    : host.contains("kick.com") || host.contains("live-video.net") ? "KICK"
                    : host.contains("tiktok") ? "TIKTOK" : host.contains("rumble") ? "RUMBLE" : "CUSTOM";
            return new DestinationView(d.getId(), d.getName(), d.getType(), platform, server, hint, d.isSelected());
        }
    }
}
