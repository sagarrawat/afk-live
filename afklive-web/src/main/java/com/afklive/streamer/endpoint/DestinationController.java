package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.SecurityUtils;
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
        return ResponseEntity.ok(destinationRepository.findByUser(user));
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
        com.afklive.streamer.util.NetworkSafety.requireSafeStreamDestination(key);

        if (!destinationRepository.findByStreamKeyAndUser(key, user).isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Stream key already exists"));
        }

        StreamDestination dest = new StreamDestination(name, key, type, user);
        dest.setSelected(selected);
        StreamDestination saved = destinationRepository.save(dest);

        return ResponseEntity.ok(saved);
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

                    return ResponseEntity.ok(destinationRepository.save(dest));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** MS-6: send a 10-second test to the platform and say whether it was accepted. */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testDestination(@PathVariable Long id, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        String email = SecurityUtils.getEmail(principal);
        return destinationRepository.findByIdAndUserEmail(id, email)
                .<ResponseEntity<?>>map(dest -> {
                    var result = testService.test(email, dest);
                    return ResponseEntity.ok(new ApiResponse<>(result.ok(), result.message(), null));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
