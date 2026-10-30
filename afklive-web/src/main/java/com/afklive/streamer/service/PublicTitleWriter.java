package com.afklive.streamer.service;

import com.afklive.streamer.service.TitleWriter.Draft;
import com.afklive.streamer.service.TitleWriter.Language;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MK-7 / GR-10: the free title and description writer on the public site (/tools/youtube-live-title-generator),
 * no sign-in. The same prompt and checks as the creator's title writer ({@link TitleWriter}: honest wording, the
 * GR-9 title rules, the language's script), from what the visitor picks and types instead of a saved stream.
 * <p>
 * Abuse and cost guards, all counted in the database so every web instance shares them: per visitor (a SHA-256 of
 * the IP address and the day, never the address) {@code APP_AI_PUBLIC_PER_IP_HOUR} (5) an hour and
 * {@code APP_AI_PUBLIC_PER_IP_DAY} (15) a day; all visitors together {@code APP_AI_PUBLIC_DAILY_LIMIT} (300) a UTC
 * day; a short description; Cloudflare Turnstile when its keys are set ({@link TurnstileVerifier}); and every call
 * counts toward the monthly AI budget ({@link AiBudget}), so the tool stops with the rest of AI at the cap.
 * A request counts when it reaches the model, even if the answer can't be used (it was paid for).
 */
@Service
@Slf4j
public class PublicTitleWriter {

    /** What the visitor can say they stream: the site's niches, plus kids and anything else. */
    public enum Niche {
        LOFI("lofi", "Lofi music"),
        DEVOTIONAL("devotional", "Devotional: bhajan, aarti, mantra"),
        GAMING("gaming", "Mobile gaming: BGMI, Free Fire"),
        PODCASTS("podcasts", "Podcast episodes"),
        STUDY("study", "Study with me"),
        KIDS("kids", "Kids rhymes and songs"),
        OTHER("other", "Other");

        public final String id;
        /** Goes into the prompt as the stream's name (with "Other" the visitor's description carries it). */
        public final String label;

        Niche(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Optional<Niche> of(String id) {
            for (Niche n : values()) {
                if (n.id.equals(id)) {
                    return Optional.of(n);
                }
            }
            return Optional.empty();
        }
    }

    public static final int DESCRIPTION_MIN = 3;
    public static final int DESCRIPTION_MAX = TitleWriter.BRIEF_MAX;

    private final AiService ai;
    private final JdbcTemplate jdbc;
    private final TurnstileVerifier turnstile;
    private final int perIpHour;
    private final int perIpDay;
    private final int dailyLimit;
    private Clock clock = Clock.systemUTC();

    public PublicTitleWriter(AiService ai, JdbcTemplate jdbc, TurnstileVerifier turnstile,
                             @Value("${app.ai.public-per-ip-hour:5}") int perIpHour,
                             @Value("${app.ai.public-per-ip-day:15}") int perIpDay,
                             @Value("${app.ai.public-daily-limit:300}") int dailyLimit) {
        this.ai = ai;
        this.jdbc = jdbc;
        this.turnstile = turnstile;
        this.perIpHour = perIpHour;
        this.perIpDay = perIpDay;
        this.dailyLimit = dailyLimit;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The visitor's request, as the page sends it. {@code mode}: "replay" (24/7 from uploads) or "phone" (really live). */
    public record Request(String niche, String description, String language, String mode, String turnstileToken) {
    }

    /**
     * @param status OK, INVALID (400), CHECK (Turnstile failed, 403), LIMIT (this visitor, 429), BUSY (today's
     *               total, 429), UNAVAILABLE (AI off or the monthly budget used up, 503), FAILED (no usable answer, 502)
     */
    public record Result(String status, String message, List<String> titles, String description, List<String> tags, String language) {
        static Result of(String status, String message) {
            return new Result(status, message, null, null, null, null);
        }
    }

    public record NicheOption(String id, String label) {
    }

    /** What the page needs before showing the form: whether the tool is up, and the Turnstile site key if it's on. */
    public record Status(boolean available, String turnstileSiteKey, int descriptionMax, List<NicheOption> niches,
                         List<TitleWriterService.LanguageOption> languages) {
    }

    public Status status() {
        boolean up = ai.isAvailable() && usedToday() < dailyLimit;
        return new Status(up, turnstile.siteKey(), DESCRIPTION_MAX,
                java.util.Arrays.stream(Niche.values()).map(n -> new NicheOption(n.id, n.label)).toList(), TitleWriterService.LANGUAGES);
    }

    // ------------------------------------------------------------------ writing

    public Result write(Request req, String ip) {
        Optional<Niche> niche = Niche.of(req.niche());
        Optional<Language> lang = Language.of(req.language());
        String mode = req.mode() == null ? "" : req.mode().strip();
        String desc = clean(req.description());
        if (niche.isEmpty() || lang.isEmpty() || !(mode.equals("replay") || mode.equals("phone"))) {
            return Result.of("INVALID", "Pick what you stream, a language and how you stream.");
        }
        if (desc.codePointCount(0, desc.length()) < DESCRIPTION_MIN) {
            return Result.of("INVALID", "Tell us in a few words what's on your stream.");
        }
        if (desc.codePointCount(0, desc.length()) > DESCRIPTION_MAX) {
            return Result.of("INVALID", "Keep the description under " + DESCRIPTION_MAX + " characters.");
        }
        if (!ai.isAvailable()) {
            return Result.of("UNAVAILABLE", "This tool is resting right now. Try again later.");
        }
        if (!turnstile.verify(req.turnstileToken(), ip)) {
            return Result.of("CHECK", "We couldn't check that you're a person. Reload the page and try again.");
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        String visitor = TitleWriterService.sha256((ip == null ? "" : ip) + "|" + now.toLocalDate());
        String hourKey = "ip:h:" + visitor + ":" + now.getHour();
        String dayKey = "ip:d:" + visitor;
        String allKey = "all:d:" + now.toLocalDate();
        Instant endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
        if (!claim(dayKey, perIpDay, endOfDay)) {
            return Result.of("LIMIT", "You've written " + perIpDay + " today, the most for one visitor. Come back tomorrow, "
                    + "or sign up free to write more for your own streams.");
        }
        if (!claim(hourKey, perIpHour, endOfHour)) {
            refund(dayKey);
            return Result.of("LIMIT", "You've written " + perIpHour + " in the last hour. Try again in a little while, "
                    + "or sign up free to write more for your own streams.");
        }
        if (!claim(allKey, dailyLimit, endOfDay)) {
            refund(dayKey);
            refund(hourKey);
            return Result.of("BUSY", "Lots of people used this today, so it's resting until tomorrow. Sign up free to write titles for your own streams.");
        }
        Language l = lang.get();
        TitleWriter.Input in = new TitleWriter.Input(niche.get() == Niche.OTHER ? cut(desc, 60) : niche.get().label, null, desc,
                mode.equals("phone"), mode.equals("replay"), false, false, List.of(), l);
        Draft d = TitleWriter.parse(ai.completeJson(TitleWriter.prompt(in)), in);
        if (d == null) {
            return Result.of("FAILED", "We couldn't write this one. Try again in a minute.");
        }
        return new Result("OK", null, d.titles(), d.description(), d.tags(), l.code());
    }

    /** One line of plain text: control characters and angle brackets out, spaces tidied. Never treated as HTML. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\u200C\\u200D]]", " ").replaceAll("[<>]", " ").replaceAll("\\s+", " ").strip();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).strip();
    }

    // ------------------------------------------------------------------ counters

    /** One more on this counter, if it's under {@code limit}: a single conditional upsert, safe across instances. */
    boolean claim(String counter, int limit, Instant expires) {
        if (limit <= 0) {
            return false;
        }
        List<Integer> rows = jdbc.queryForList("""
                INSERT INTO ai_public_counts (counter, count, expires_at) VALUES (?, 1, ?)
                ON CONFLICT (counter) DO UPDATE SET count = ai_public_counts.count + 1
                WHERE ai_public_counts.count < ?
                RETURNING count
                """, Integer.class, counter, Timestamp.from(expires), limit);
        if (!rows.isEmpty() && rows.getFirst() == 1 && counter.startsWith("all:")) {
            // The day's first request: tidy expired counters (two days at most, so the table stays small).
            jdbc.update("DELETE FROM ai_public_counts WHERE expires_at < ?", Timestamp.from(clock.instant()));
        }
        return !rows.isEmpty();
    }

    void refund(String counter) {
        jdbc.update("UPDATE ai_public_counts SET count = count - 1 WHERE counter = ? AND count > 0", counter);
    }

    /** Requests that reached the model today (UTC), all visitors. */
    public int usedToday() {
        String key = "all:d:" + ZonedDateTime.now(clock.withZone(ZoneOffset.UTC)).toLocalDate();
        Integer n = jdbc.query("SELECT count FROM ai_public_counts WHERE counter = ?", rs -> rs.next() ? rs.getInt(1) : 0, key);
        return n == null ? 0 : n;
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    /** HTTP status for a result. */
    public static int httpStatus(String status) {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("OK", 200);
        codes.put("INVALID", 400);
        codes.put("CHECK", 403);
        codes.put("LIMIT", 429);
        codes.put("BUSY", 429);
        codes.put("UNAVAILABLE", 503);
        codes.put("FAILED", 502);
        return codes.getOrDefault(status, 500);
    }
}
