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
    private final com.afklive.streamer.service.LiveStreamService liveStreams;

    @GetMapping
    public ResponseEntity<?> getDestinations(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);
        User user = userService.getOrCreateUser(email);
        Map<Long, List<String>> live = liveStreams.destinationsLive(email);
        return ResponseEntity.ok(destinationRepository.findByUser(user).stream()
                .map(d -> DestinationView.of(d, live.getOrDefault(d.getId(), List.of()))).toList());
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

    /** Refused (409) while a live stream is sending to it: nothing that is live may be deleted. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDestination(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        liveStreams.deleteDestination(SecurityUtils.getEmail(principal), id);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
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
                    if (body.get("name") instanceof String n && !n.isBlank()) dest.setName(n.trim());
                    // The key is write-only: the app never has it, so an empty key means "keep the saved one".
                    if (body.get("key") instanceof String k && !k.isBlank()) {
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

                    StreamDestination saved = destinationRepository.save(dest);
                    // A live run keeps the address it started with (its own copy); the app says so.
                    return ResponseEntity.ok(DestinationView.of(saved, liveStreams.destinationsLive(email).getOrDefault(saved.getId(), List.of())));
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
                                  boolean selected, List<String> liveIn) {
        static DestinationView of(StreamDestination d) {
            return of(d, List.of());
        }

        /** {@code liveIn}: the live streams sending to it now (a key change reaches them only at their next go-live). */
        static DestinationView of(StreamDestination d, List<String> liveIn) {
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
            String platform = com.afklive.streamer.util.Platforms.code(server);
            return new DestinationView(d.getId(), d.getName(), d.getType(), platform, server, hint, d.isSelected(), liveIn);
        }
    }
}
