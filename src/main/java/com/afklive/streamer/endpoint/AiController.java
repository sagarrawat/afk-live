package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, String> body) {
        String type = body.get("type");
        String context = body.get("context");

        if (type == null || context == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Type and context required"));
        }

        String result = "";
        try {
            Thread.sleep(1500); // Simulate network latency/thinking
        } catch (InterruptedException e) {}

        switch (type) {
            case "title":
                result = aiService.generateTitle(context);
                break;
            case "description":
                result = aiService.generateDescription(context);
                break;
            case "tags":
                result = aiService.generateTags(context);
                break;
            default:
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid type"));
        }

        return ResponseEntity.ok(Map.of("result", result));
    }
}
