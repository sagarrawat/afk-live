package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/** Google Gemini's generateContent API. The key goes in a header, never the URL (so never in access logs). */
@Slf4j
final class GeminiProvider implements AiProvider {

    /** The default model: the cheapest Gemini class that writes good Indic text (Flash-Lite). */
    static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    private final String key;
    private final String model;
    private final RestTemplate http;

    GeminiProvider(String key, String model, RestTemplate http) {
        this.key = key;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.http = http;
    }

    /** A real key: not empty and not the placeholder from an example env file. */
    static boolean usable(String key) {
        return key != null && !key.isBlank() && !key.contains("GEMINI_API_KEY");
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Reply complete(String prompt, boolean json) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
            Map<String, Object> body = json
                    ? Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.7,
                                    "maxOutputTokens", 2048))
                    : Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", key);
            Map response = http.postForObject(url, new HttpEntity<>(body, headers), Map.class);
            if (response == null) {
                return null;
            }
            long in = 0;
            long out = 0;
            if (response.get("usageMetadata") instanceof Map u) {
                in = AiProvider.tokens(u.get("promptTokenCount"));
                // Thinking tokens are billed as output.
                out = AiProvider.tokens(u.get("candidatesTokenCount")) + AiProvider.tokens(u.get("thoughtsTokenCount"));
            }
            String text = null;
            if (response.get("candidates") instanceof List candidates && !candidates.isEmpty()
                    && candidates.getFirst() instanceof Map candidate
                    && candidate.get("content") instanceof Map content
                    && content.get("parts") instanceof List parts && !parts.isEmpty()
                    && parts.getFirst() instanceof Map part && part.get("text") instanceof String s) {
                text = s.strip();
            }
            return new Reply(text, in, out);
        } catch (HttpStatusCodeException e) {
            log.warn("Gemini call failed ({}): HTTP {}", model, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Gemini call failed ({}): {}", model, e.getClass().getSimpleName());
        }
        return null;
    }
}
