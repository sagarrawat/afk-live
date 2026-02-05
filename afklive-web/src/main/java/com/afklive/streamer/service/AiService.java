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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AiService {

    private final String geminiKey;
    private final RestTemplate restTemplate;
    private final Random random = new Random();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AiService(@Value("${app.gemini.key:}") String geminiKey, RestTemplate restTemplate) {
        this.geminiKey = geminiKey;
        this.restTemplate = restTemplate;
    }

    public String generateTitle(String context) {
        if (isAiEnabled()) {
            String title = callGemini("Generate exactly ONE catchy, viral YouTube video title about: " + context + ". Do not include any other text, quotes, or explanations.");
            if (title != null) return title;
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
            String desc = callGemini("Write a YouTube video description for a video titled: " + title + ". Do not include any other text.");
            if (desc != null) return desc;
        }
        return "In this video, we dive deep into " + title + ". \n\n" +
               "Make sure to like and subscribe for more content about this topic! \n" +
               "Follow us on social media for updates.";
    }

    public String generateTags(String context) {
        if (isAiEnabled()) {
            String tags = callGemini("Generate 10 comma-separated YouTube tags for a video about: " + context);
            if (tags != null) return tags;
        }
        String base = context.toLowerCase().replaceAll("\\s+", ",");
        return base + ",viral,trending,2024,guide,tutorial,review";
    }

    public Map<String, String> generateStreamMetadata(String context) {
        if (isAiEnabled()) {
            // Run parallel calls using Virtual Threads
            CompletableFuture<String> titleFuture = CompletableFuture.supplyAsync(() -> generateTitle(context), executor);
            CompletableFuture<String> descFuture = titleFuture.thenApplyAsync(this::generateDescription, executor);
            CompletableFuture<String> tagsFuture = CompletableFuture.supplyAsync(() -> generateTags(context), executor);
            CompletableFuture<String> tipFuture = CompletableFuture.supplyAsync(() -> {
                String tip = callGemini("Give one short, engaging tip for a streamer streaming about: " + context + ". e.g. 'Ask chat to...'");
                return tip != null ? tip : "Ask chat where they are watching from!";
            }, executor);

            CompletableFuture.allOf(titleFuture, descFuture, tagsFuture, tipFuture).join();

            return Map.of(
                "title", titleFuture.join(),
                "description", descFuture.join(),
                "tags", tagsFuture.join(),
                "tip", tipFuture.join()
            );
        }

        return Map.of(
            "title", generateTitle(context),
            "description", generateDescription(context),
            "tags", generateTags(context),
            "tip", "Ask chat where they are watching from!"
        );
    }

    public String analyzeSentiment(String text) {
        if (isAiEnabled()) {
            String sentiment = callGemini("Analyze sentiment of this comment: '" + text + "'. Return only one word: POSITIVE, NEGATIVE, or NEUTRAL.");
            if (sentiment != null) return sentiment;
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
            String reply = callGemini("Write a single, short, engaging, and friendly reply to this YouTube comment: '" + commentText + "'. Do not use quotes.");
            if (reply != null) return reply;
        }
        return "Thanks for your comment! 😊";
    }

    public String generateTwitterStyleReply(String commentText) {
        if (isAiEnabled()) {
            String reply = callGemini("Write a very short, tweet-style reply to this live stream comment: '" + commentText + "'. It should be interactive and engaging. Max 100 characters. No quotes.");
            if (reply != null) return reply;
            // If API fails, return null so caller knows to skip reply
            return null;
        }
        return "Thanks for watching! 🔥";
    }

    private boolean isAiEnabled() {
        return geminiKey != null && !geminiKey.isEmpty() && !geminiKey.contains("GEMINI_API_KEY");
    }

    private String callGemini(String prompt) {
        try {
            // Using gemini-flash-lite-latest to avoid 404s
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent?key=" + geminiKey;

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
            // In a real production app, we would log the full response body if it's a 4xx error
        }
        return null; // Fallback to null instead of prompt
    }
}
