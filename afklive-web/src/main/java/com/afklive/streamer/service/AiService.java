package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * Text drafts for titles, descriptions, tags and reply suggestions, from one {@link AiProvider}: Anthropic
 * (Claude) when {@code ANTHROPIC_API_KEY} is set, else Google Gemini when {@code GEMINI_API_KEY} is set, else
 * none (AI is off and every draft falls back to the creator's own words, never an error). Every call is
 * counted against the monthly AI budget ({@link AiBudget}); once it's used up, AI is off until the next UTC
 * month. Everything here is a draft the creator reads and edits: nothing is posted to YouTube from this class.
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

    /** The default Gemini model: the cheapest Gemini class that writes good Indic text (Flash-Lite). */
    static final String DEFAULT_MODEL = GeminiProvider.DEFAULT_MODEL;

    /** The model in use, or null when AI is off. */
    private final AiProvider provider;
    /** Null only in unit tests: no budget. */
    private final AiBudget budget;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param anthropicKey   {@code ANTHROPIC_API_KEY}; set: Claude writes (it wins over Gemini)
     * @param anthropicModel {@code ANTHROPIC_MODEL}; blank uses {@link AnthropicProvider#DEFAULT_MODEL}
     * @param geminiKey      {@code GEMINI_API_KEY}; used when there's no Anthropic key
     * @param geminiModel    {@code GEMINI_MODEL}; blank uses {@link #DEFAULT_MODEL}
     */
    @Autowired
    public AiService(@Value("${app.anthropic.key:}") String anthropicKey, @Value("${app.anthropic.model:}") String anthropicModel,
                     @Value("${app.gemini.key:}") String geminiKey, @Value("${app.gemini.model:}") String geminiModel,
                     AiBudget budget) {
        this(select(anthropicKey, anthropicModel, geminiKey, geminiModel, timed()), budget);
        log.info("AI writing: {}", provider == null ? "off (no ANTHROPIC_API_KEY or GEMINI_API_KEY)" : provider.name() + ", " + provider.model());
    }

    /** Gemini only, no budget (unit tests). */
    AiService(String geminiKey, RestTemplate restTemplate) {
        this(geminiKey, null, restTemplate);
    }

    /** Gemini only, no budget (unit tests). */
    AiService(String geminiKey, String model, RestTemplate restTemplate) {
        this(select(null, null, geminiKey, model, restTemplate), null);
    }

    AiService(AiProvider provider, AiBudget budget) {
        this.provider = provider;
        this.budget = budget;
    }

    /** Anthropic when its key is set, else Gemini when its key is set, else null (AI off). */
    static AiProvider select(String anthropicKey, String anthropicModel, String geminiKey, String geminiModel, RestTemplate http) {
        if (AnthropicProvider.usable(anthropicKey)) {
            return new AnthropicProvider(anthropicKey.strip(), anthropicModel, http);
        }
        if (GeminiProvider.usable(geminiKey)) {
            return new GeminiProvider(geminiKey.strip(), geminiModel, http);
        }
        return null;
    }

    /** Its own client with timeouts: a slow model must not hold a web request for minutes. */
    static RestTemplate timed() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(25_000);
        return new RestTemplate(f);
    }

    /** The model in use; with AI off, the default Gemini model (only used in cache keys then). */
    public String model() {
        return provider == null ? DEFAULT_MODEL : provider.model();
    }

    /** "anthropic", "gemini", or null when no key is set. */
    public String providerName() {
        return provider == null ? null : provider.name();
    }

    /** This month's AI spend against the cap, for admin. */
    public AiBudget.Report spend() {
        return budget == null ? null : budget.report(providerName(), provider == null ? null : provider.model());
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
            String title = cleanTitle(call(titlePrompt(context)));
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
            String desc = call(descriptionPrompt(title));
            if (desc != null) return desc;
        }
        return title;
    }

    public String generateTags(String context) {
        if (isAiEnabled()) {
            String tags = call(tagsPrompt(context));
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
                String tip = call("Give one short, engaging tip for a streamer streaming about: " + context + ". e.g. 'Ask chat to...'");
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
            String result = call(prompt);
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

    /**
     * True when a key is configured and this month's AI budget isn't used up. Without it, nothing calls out
     * and drafts fall back (the title writer is hidden or says it isn't available).
     */
    public boolean isAvailable() {
        return provider != null && (budget == null || budget.allows());
    }

    /** True when a key is configured, whatever the budget. */
    public boolean isConfigured() {
        return provider != null;
    }

    /**
     * One answer from the model, asked to reply in JSON (AI-1 title writer); null when AI is off, the budget
     * is used up, or the call fails or times out. Never throws.
     */
    public String completeJson(String prompt) {
        return call(prompt, true);
    }

    private boolean isAiEnabled() {
        return provider != null;
    }

    private String call(String prompt) {
        return call(prompt, false);
    }

    /** Checks the budget, calls the model and counts the tokens it used. The prompt and key are never logged. */
    private String call(String prompt, boolean json) {
        if (provider == null || (budget != null && !budget.allows())) {
            return null;
        }
        AiProvider.Reply r = provider.complete(prompt, json);
        if (r == null) {
            return null;
        }
        if (budget != null && (r.inputTokens() > 0 || r.outputTokens() > 0)) {
            budget.record(provider.name(), provider.model(), r.inputTokens(), r.outputTokens());
        }
        return r.text();
    }
}
