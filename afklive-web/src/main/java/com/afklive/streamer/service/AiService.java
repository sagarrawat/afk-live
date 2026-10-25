package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Text drafts from Gemini for titles, descriptions, tags and reply suggestions. Everything here is a
 * draft the creator reads and edits: nothing is posted to YouTube from this class.
 *
 * <p>Titles follow the growth plan's honest-title rule (docs/growth-plan.md, rule 6) and the GR-9 title
 * checks ({@link GoLiveCheck}): start with what people search for, say only what is on the stream, and
 * never claim "LIVE" over videos that were uploaded (most AFK Live streams are replays). YouTube's spam
 * policy bans misleading titles, descriptions and tags. The model's answer is checked with the same
 * rules GR-9 uses, so a "LIVE NOW" that slips through is rewritten before anyone sees it.
 */
@Service
@Slf4j
public class AiService {

    /** YouTube's title limit, in characters. */
    static final int TITLE_MAX = 100;

    /** The default model: the cheapest Gemini class that writes good Indic text (Flash-Lite). */
    static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    private final String geminiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param geminiKey empty: AI is off and every draft falls back to the creator's own words (never an error)
     * @param model     the Gemini model id ({@code GEMINI_MODEL}); blank uses {@link #DEFAULT_MODEL}
     */
    @Autowired
    public AiService(@Value("${app.gemini.key:}") String geminiKey, @Value("${app.gemini.model:}") String model) {
        this(geminiKey, model, timed());
    }

    AiService(String geminiKey, RestTemplate restTemplate) {
        this(geminiKey, null, restTemplate);
    }

    AiService(String geminiKey, String model, RestTemplate restTemplate) {
        this.geminiKey = geminiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.restTemplate = restTemplate;
    }

    /** Its own client with timeouts: a slow model must not hold a web request for minutes. */
    private static RestTemplate timed() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(25_000);
        return new RestTemplate(f);
    }

    public String model() {
        return model;
    }

    // ------------------------------------------------------------------ prompts (tested on their own)

    /** The rules every title prompt carries: the honest-title rule and the GR-9 title checks, in words a model follows. */
    static final String TITLE_RULES = String.join(" ",
            "Rules:",
            "1. Start with the words people would type into YouTube search to find exactly this, for example",
            "\"lofi beats to study\", \"Hanuman Chalisa\" or \"rain sounds for sleep\".",
            "2. Describe only what is really on the stream or video. No clickbait, no exaggeration, no promises,",
            "no words in ALL CAPS, no emoji, no hashtags.",
            "3. The stream plays videos that were recorded earlier, so never write \"LIVE\", \"live now\",",
            "\"streaming now\" or a red dot. \"24/7\" is fine only if it plays around the clock.",
            "4. Keep it under " + TITLE_MAX + " characters, ideally under 70.",
            "5. Write in the same language as the topic.");

    static String titlePrompt(String context) {
        return "Write exactly ONE YouTube title for a stream about: " + quoted(context) + ". " + TITLE_RULES
                + " Reply with the title only, without quotes or explanations.";
    }

    static String descriptionPrompt(String title) {
        return "Write a YouTube description for a stream titled: " + quoted(title) + ". "
                + "The first sentence says plainly what viewers will see and hear, using the words people search for. "
                + "Describe only what is really on the stream: no clickbait, no promises, no lists of unrelated keywords. "
                + "The stream plays videos recorded earlier, so don't call it live. "
                + "Keep it under 800 characters. Reply with the description only.";
    }

    static String tagsPrompt(String context) {
        return "Write 10 comma-separated YouTube tags for a stream about: " + quoted(context) + ". "
                + "Use only phrases people would really search for that describe this stream. "
                + "No unrelated popular words such as viral, trending or a year, and no \"live\". Reply with the tags only.";
    }

    private static String quoted(String s) {
        return "\"" + (s == null ? "" : s.replace('"', '\'').strip()) + "\"";
    }

    // ------------------------------------------------------------------ drafts

    public String generateTitle(String context) {
        if (isAiEnabled()) {
            String title = cleanTitle(callGemini(titlePrompt(context)));
            if (title != null) return title;
        }
        return fallbackTitle(context);
    }

    /**
     * The model's answer made safe to show: one line, no quotes, no misleading "LIVE" (rewritten the way
     * the GR-9 one-tap fix does), within YouTube's limit, and with a searchable phrase; null when it can't be used.
     */
    static String cleanTitle(String raw) {
        return TitleWriter.cleanTitle(raw, false, false);
    }

    /** Without AI: the creator's own words, tidied. No invented hooks ("Game Changer", "Unboxing", a year). */
    static String fallbackTitle(String context) {
        String t = context == null ? "" : context.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return "Untitled stream";
        if (GoLiveCheck.misleadingLive(t) != null) {
            t = GoLiveCheck.honestTitle(t, false);
        }
        t = t.substring(0, t.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT) + t.substring(t.offsetByCodePoints(0, 1));
        return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX).strip() : t;
    }

    public String generateDescription(String title) {
        if (isAiEnabled()) {
            String desc = callGemini(descriptionPrompt(title));
            if (desc != null) return desc;
        }
        return title;
    }

    public String generateTags(String context) {
        if (isAiEnabled()) {
            String tags = callGemini(tagsPrompt(context));
            if (tags != null) return tags;
        }
        return fallbackTags(context);
    }

    /** Without AI: the topic itself and its words. No "viral", "trending" or a year (misleading tags are spam). */
    static String fallbackTags(String context) {
        String c = context == null ? "" : context.strip().toLowerCase(Locale.ROOT).replaceAll("[,\\s]+", " ");
        Set<String> tags = new LinkedHashSet<>();
        if (!c.isEmpty()) tags.add(c);
        for (String w : c.split(" ")) {
            if (w.codePointCount(0, w.length()) >= 3) tags.add(w);
        }
        return String.join(",", tags);
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

    /**
     * Three reply drafts for one comment, shown to the creator. Nothing is posted: the creator edits one and
     * sends it themselves (POST /api/comments/{id}/reply), which is the per-action consent YouTube's API
     * Developer Policies require (III.E.3.d).
     */
    public List<String> generateReplySuggestions(String commentText) {
        if (isAiEnabled()) {
            String prompt = "You are a YouTuber. Generate 3 short, engaging, and polite reply suggestions for this comment: '" + commentText + "'. Return them as a pipe-separated string (e.g. Reply 1|Reply 2|Reply 3). Do not include numbering or quotes.";
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

    /** True when a Gemini key is configured. Without one, nothing calls out and drafts fall back. */
    public boolean isAvailable() {
        return isAiEnabled();
    }

    /**
     * One answer from the model, asked to reply in JSON (AI-1 title writer); null when AI is off, the call
     * fails or times out. Never throws.
     */
    public String completeJson(String prompt) {
        return isAiEnabled() ? callGemini(prompt, true) : null;
    }

    private boolean isAiEnabled() {
        return geminiKey != null && !geminiKey.isBlank() && !geminiKey.contains("GEMINI_API_KEY");
    }

    private String callGemini(String prompt) {
        return callGemini(prompt, false);
    }

    private String callGemini(String prompt, boolean json) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";

            Map<String, Object> requestBody = json
                    ? Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.7,
                                    "maxOutputTokens", 2048))
                    : Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // In a header, not the URL, so the key never lands in access logs or error messages.
            headers.set("x-goog-api-key", geminiKey);

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
            log.warn("Gemini call failed ({}): {}", model, e.getClass().getSimpleName());
        }
        return null; // Fallback to null instead of prompt
    }
}
