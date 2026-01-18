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

    public String analyzeSentiment(String text) {
        if (isAiEnabled()) {
            return callGemini("Analyze sentiment of this comment: '" + text + "'. Return only one word: POSITIVE, NEGATIVE, or NEUTRAL.");
        }
        if (text.toLowerCase().contains("bad") || text.toLowerCase().contains("hate") || text.toLowerCase().contains("awful")) return "NEGATIVE";
        if (text.toLowerCase().contains("good") || text.toLowerCase().contains("love") || text.toLowerCase().contains("great")) return "POSITIVE";
        return "NEUTRAL";
    }

    public List<String> generateReplySuggestions(String commentText) {
        if (isAiEnabled()) {
            String prompt = "Generate 3 short, engaging, and polite reply suggestions for this YouTube comment: '" + commentText + "'. Return them as a pipe-separated string (e.g. Reply 1|Reply 2|Reply 3). Do not include numbering or quotes.";
            String result = callGemini(prompt);
            if (result != null && !result.isEmpty()) {
                String[] splits = result.split("\\|");
                List<String> suggestions = new java.util.ArrayList<>();
                for (String s : splits) {
                    if (!s.trim().isEmpty()) suggestions.add(s.trim());
                }
                // Fallback if parsing fails but we got text
                if (suggestions.isEmpty()) {
                    suggestions.add(result);
                }
                // Ensure we have 3, fill with generic if needed
                while (suggestions.size() < 3) {
                     suggestions.add("Thanks for watching! 😊");
                }
                return suggestions.subList(0, 3);
            }
        }
        // Fallback
        return List.of(
            "Thanks for your comment! 😊",
            "Appreciate the support! 👍",
            "Glad you enjoyed the video! 🔥"
        );
    }

    public String generateSingleReply(String commentText) {
        if (isAiEnabled()) {
            return callGemini("Write a single, short, engaging, and friendly reply to this YouTube comment: '" + commentText + "'. Do not use quotes.");
        }
        return "Thanks for your comment! 😊";
    }

    private boolean isAiEnabled() {
        return geminiKey != null && !geminiKey.isEmpty() && !geminiKey.contains("GEMINI_API_KEY");
    }

    private String callGemini(String prompt) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiKey;

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
