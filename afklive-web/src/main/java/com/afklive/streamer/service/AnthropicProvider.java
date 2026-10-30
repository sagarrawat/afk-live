package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic's Messages API over plain HTTP (no SDK). Errors, timeouts, rate limits (429), overload (529) and
 * an empty credit balance (400/402) all answer null; only the status code is logged, never the prompt, the
 * answer or the key.
 */
@Slf4j
final class AnthropicProvider implements AiProvider {

    static final String URL = "https://api.anthropic.com/v1/messages";
    static final String VERSION = "2023-06-01";
    static final String DEFAULT_MODEL = "claude-haiku-5-5";
    static final int MAX_TOKENS = 2048;
    /** For the title writer: the answer is read as one JSON object (TitleWriter.parse also tolerates code fences). */
    static final String JSON_SYSTEM = "Reply with one JSON object only: no code fences, no text before or after it.";

    private final String key;
    private final String model;
    private final RestTemplate http;

    AnthropicProvider(String key, String model, RestTemplate http) {
        this.key = key;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.http = http;
    }

    static boolean usable(String key) {
        return key != null && !key.isBlank() && !key.contains("ANTHROPIC_API_KEY");
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    @SuppressWarnings({"rawtypes"})
    public Reply complete(String prompt, boolean json) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", MAX_TOKENS);
            body.put("temperature", 0.7);
            if (json) {
                body.put("system", JSON_SYSTEM);
            }
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", key);
            headers.set("anthropic-version", VERSION);
            Map response = http.postForObject(URL, new HttpEntity<>(body, headers), Map.class);
            if (response == null) {
                return null;
            }
            long in = 0;
            long out = 0;
            if (response.get("usage") instanceof Map u) {
                in = AiProvider.tokens(u.get("input_tokens")) + AiProvider.tokens(u.get("cache_creation_input_tokens"))
                        + AiProvider.tokens(u.get("cache_read_input_tokens"));
                out = AiProvider.tokens(u.get("output_tokens"));
            }
            StringBuilder text = new StringBuilder();
            if (response.get("content") instanceof List blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map block && "text".equals(block.get("type")) && block.get("text") instanceof String s) {
                        text.append(s);
                    }
                }
            }
            String t = text.toString().strip();
            boolean refused = "refusal".equals(response.get("stop_reason"));
            return new Reply(t.isEmpty() || refused ? null : t, in, out);
        } catch (HttpStatusCodeException e) {
            log.warn("Anthropic call failed ({}): HTTP {}", model, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Anthropic call failed ({}): {}", model, e.getClass().getSimpleName());
        }
        return null;
    }
}
