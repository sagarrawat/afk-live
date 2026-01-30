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

        // Mock latency removed for better UX or kept short
        try { Thread.sleep(500); } catch (InterruptedException e) {}

        String result = "";
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

    @PostMapping("/stream-metadata")
    public ResponseEntity<?> generateStreamMetadata(@RequestBody Map<String, String> body) {
        String context = body.get("context");
        if (context == null || context.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Context required"));
        }
        return ResponseEntity.ok(aiService.generateStreamMetadata(context));
    }
}
