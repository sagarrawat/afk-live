package com.afklive.streamer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class AiService {

    @Value("${app.gemini.key:}")
    private String geminiKey;

    private final Random random = new Random();
    private final RestTemplate restTemplate = new RestTemplate();

    public String generateTitle(String context) {
        if (isAiEnabled()) {
            return callGemini("Generate a catchy, viral YouTube video title about: " + context);
        }
        String[] templates = {
            "Amazing Video about " + context,
            "Why " + context + " is a Game Changer",
            "The Ultimate Guide to " + context,
            "Unboxing " + context + " (2024 Edition)",
            context + ": Explained in 5 Minutes"
        };
        return templates[random.nextInt(templates.length)];
    }

    public String generateDescription(String title) {
        if (isAiEnabled()) {
            return callGemini("Write a YouTube video description for a video titled: " + title);
        }
        return "In this video, we dive deep into " + title + ". \n\n" +
               "Make sure to like and subscribe for more content about this topic! \n" +
               "Follow us on social media for updates.";
    }

    public String generateTags(String context) {
        if (isAiEnabled()) {
            return callGemini("Generate 10 comma-separated YouTube tags for a video about: " + context);
        }
        String base = context.toLowerCase().replaceAll("\\s+", ",");
        return base + ",viral,trending,2024,guide,tutorial,review";
    }

    private boolean isAiEnabled() {
        return geminiKey != null && !geminiKey.isEmpty() && !geminiKey.contains("GEMINI_API_KEY");
    }

    private String callGemini(String prompt) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=" + geminiKey;

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            Map response = restTemplate.postForObject(url, entity, Map.class);

            // Parse response: candidates[0].content.parts[0].text
            if (response != null && response.containsKey("candidates")) {
                List candidates = (List) response.get("candidates");
                if (!candidates.isEmpty()) {
                    Map candidate = (Map) candidates.get(0);
                    Map content = (Map) candidate.get("content");
                    List parts = (List) content.get("parts");
                    Map part = (Map) parts.get(0);
                    return ((String) part.get("text")).trim();
                }
            }
        } catch (Exception e) {
            System.err.println("Gemini API failed: " + e.getMessage());
        }
        return prompt; // Fallback or throw? Fallback for now.
    }
}
