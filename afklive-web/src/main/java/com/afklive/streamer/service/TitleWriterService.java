package com.afklive.streamer.service;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamItemRepository;
import com.afklive.streamer.service.TitleWriter.Draft;
import com.afklive.streamer.service.TitleWriter.Language;
import com.afklive.streamer.service.TitleWriter.Track;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * AI-1 / GR-10: "Write title and description" for one stream. Three titles, a description with the
 * playlist's tracklist, and tags, in the creator's language, checked with the GR-9 title rules. Drafts
 * only: nothing is sent to YouTube. Cost guard: a fair-use limit per account per day, counted in the
 * database with a conditional increment, and the same inputs within 24 hours read the cached draft.
 */
@Service
@Slf4j
public class TitleWriterService {

    static final long CACHE_HOURS = 24;

    private final LiveStreamService streams;
    private final StreamItemRepository items;
    private final AiService ai;
    private final JdbcTemplate jdbc;
    private final ReferralService referrals;
    private final int limitFree;
    private final int limitPaid;
    private final ObjectMapper json = new ObjectMapper();
    private Clock clock = Clock.systemUTC();

    public TitleWriterService(LiveStreamService streams, StreamItemRepository items, AiService ai, JdbcTemplate jdbc,
                              ReferralService referrals,
                              @Value("${app.ai.daily-limit-free:20}") int limitFree,
                              @Value("${app.ai.daily-limit-paid:100}") int limitPaid) {
        this.streams = streams;
        this.items = items;
        this.ai = ai;
        this.jdbc = jdbc;
        this.referrals = referrals;
        this.limitFree = limitFree;
        this.limitPaid = limitPaid;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public record Usage(int used, int limit) {
    }

    public record LanguageOption(String code, String name, String nativeName) {
    }

    public static final List<LanguageOption> LANGUAGES = java.util.Arrays.stream(Language.values())
            .map(l -> new LanguageOption(l.code(), l.english, l.nativeName)).toList();

    /** @param language the creator's last choice, or null (the app then guesses from the browser, else English) */
    public record Status(boolean available, String language, List<LanguageOption> languages, Usage usage) {
    }

    /**
     * @param status  OK (a draft), FALLBACK (the model's answer couldn't be used: a start from the creator's own
     *                words, not counted), LIMIT (today's fair use is used up) or UNAVAILABLE (no AI configured)
     * @param cached  the same inputs were written in the last 24 hours (not counted again)
     */
    public record Result(String status, Draft draft, String language, Usage usage, boolean cached, String message) {
    }

    public Status status(String email, Long streamId) {
        LiveStream s = streams.get(email, streamId);
        User u = s.getUser();
        return new Status(ai.isAvailable(), savedLanguage(u.getId()).map(Language::code).orElse(null), LANGUAGES,
                new Usage(used(u), limit(u)));
    }

    public Result write(String email, Long streamId, String languageCode, String brief) {
        LiveStream s = streams.get(email, streamId);
        User u = s.getUser();
        Language lang = Language.of(languageCode).or(() -> savedLanguage(u.getId())).orElse(Language.EN);
        int limit = limit(u);
        if (!ai.isAvailable()) {
            return new Result("UNAVAILABLE", null, lang.code(), new Usage(used(u), limit), false,
                    "Writing titles isn't available right now.");
        }
        jdbc.update("UPDATE users SET ai_language = ? WHERE id = ?", lang.code(), u.getId());

        TitleWriter.Input in = input(s, lang, brief);
        String prompt = TitleWriter.prompt(in);
        String hash = sha256(ai.model() + "\n" + prompt);

        Draft cached = cached(s.getId(), lang, hash);
        if (cached != null) {
            return new Result("OK", withCreditLine(u, cached), lang.code(), new Usage(used(u), limit), true, null);
        }

        LocalDate day = today(u);
        if (!claim(u.getId(), day, limit)) {
            return new Result("LIMIT", null, lang.code(), new Usage(used(u), limit), false,
                    "You've written " + limit + " drafts today, your plan's daily limit. You can write more tomorrow.");
        }
        Draft draft = TitleWriter.parse(ai.completeJson(prompt), in);
        if (draft == null) {
            refund(u.getId(), day);
            return new Result("FALLBACK", withCreditLine(u, TitleWriter.fallback(in)), lang.code(), new Usage(used(u), limit), false,
                    "We couldn't write this one, so here's a start from your stream's name and playlist. Try again in a minute; it wasn't counted.");
        }
        store(s.getId(), lang, hash, draft);
        return new Result("OK", withCreditLine(u, draft), lang.code(), new Usage(used(u), limit), false, null);
    }

    // ------------------------------------------------------------------ inputs

    TitleWriter.Input input(LiveStream s, Language lang, String brief) {
        boolean phone = s.getSource() == LiveStream.Source.PHONE;
        List<StreamItem> music = ready(items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.AUDIO));
        boolean useMusic = !music.isEmpty();
        List<StreamItem> list = phone ? List.of()
                : useMusic ? music : ready(items.findAllByStreamIdAndKindOrderByPositionAsc(s.getId(), StreamItem.Kind.VIDEO));
        LiveStream.Order order = useMusic ? s.getMusicOrder() : s.getPlaybackOrder();
        List<Track> tracks = list.stream().map(i -> new Track(i.getMedia().getTitle(), i.getMedia().getDurationSeconds())).toList();
        String b = brief == null ? null : brief.strip();
        if (b != null && b.length() > TitleWriter.BRIEF_MAX) {
            b = b.substring(0, TitleWriter.BRIEF_MAX);
        }
        return new TitleWriter.Input(s.getName(), s.getYoutubeTitle(), b == null || b.isEmpty() ? null : b, phone,
                s.getLoopCount() == 0, order == LiveStream.Order.SHUFFLE, useMusic, tracks, lang);
    }

    private static List<StreamItem> ready(List<StreamItem> list) {
        return list.stream().filter(i -> i.getMedia() != null && i.getMedia().getStatus() == MediaStatus.READY).toList();
    }

    private Draft withCreditLine(User u, Draft d) {
        String desc = referrals == null ? d.description() : referrals.withCreditLine(u.getId(), d.description());
        return new Draft(d.titles(), desc, d.tags());
    }

    // ------------------------------------------------------------------ fair use

    int limit(User u) {
        return u.getPlanType() == null || u.getPlanType() == PlanType.FREE ? limitFree : limitPaid;
    }

    /** The account's day: the day resets at midnight where the creator is. */
    LocalDate today(User u) {
        ZoneId zone = ZoneId.of("UTC");
        try {
            if (u.getTimeZone() != null && !u.getTimeZone().isBlank()) {
                zone = ZoneId.of(u.getTimeZone());
            }
        } catch (Exception ignored) {
            // an unknown zone counts in UTC
        }
        return LocalDate.now(clock.withZone(zone));
    }

    int used(User u) {
        Integer n = jdbc.query("SELECT generations FROM ai_usage WHERE user_id = ? AND day = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, u.getId(), Date.valueOf(today(u)));
        return n == null ? 0 : n;
    }

    /**
     * One more draft today, if the account is under its limit. A single conditional upsert: two web
     * instances racing for the last draft can't both get it, because the row lock serialises them and
     * the second sees the count already at the limit.
     */
    boolean claim(Long userId, LocalDate day, int limit) {
        if (limit <= 0) {
            return false;
        }
        List<Integer> rows = jdbc.queryForList("""
                INSERT INTO ai_usage (user_id, day, generations) VALUES (?, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET generations = ai_usage.generations + 1
                WHERE ai_usage.generations < ?
                RETURNING generations
                """, Integer.class, userId, Date.valueOf(day), limit);
        if (!rows.isEmpty() && rows.getFirst() == 1) {
            // First draft of the day: tidy this account's old days (the primary key makes it cheap).
            jdbc.update("DELETE FROM ai_usage WHERE user_id = ? AND day < ?", userId, Date.valueOf(day.minusDays(7)));
        }
        return !rows.isEmpty();
    }

    void refund(Long userId, LocalDate day) {
        jdbc.update("UPDATE ai_usage SET generations = generations - 1 WHERE user_id = ? AND day = ? AND generations > 0",
                userId, Date.valueOf(day));
    }

    // ------------------------------------------------------------------ cache

    private Draft cached(Long streamId, Language lang, String hash) {
        String body = jdbc.query("SELECT draft FROM ai_drafts WHERE stream_id = ? AND language = ? AND inputs_hash = ? "
                        + "AND created_at > now() - make_interval(hours => ?)",
                rs -> rs.next() ? rs.getString(1) : null, streamId, lang.code(), hash, (int) CACHE_HOURS);
        if (body == null) {
            return null;
        }
        try {
            return json.readValue(body, Draft.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void store(Long streamId, Language lang, String hash, Draft d) {
        try {
            jdbc.update("""
                    INSERT INTO ai_drafts (stream_id, language, inputs_hash, draft, created_at) VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (stream_id, language, inputs_hash) DO UPDATE SET draft = EXCLUDED.draft, created_at = now()
                    """, streamId, lang.code(), hash, json.writeValueAsString(d));
            jdbc.update("DELETE FROM ai_drafts WHERE stream_id = ? AND created_at < now() - make_interval(hours => ?)",
                    streamId, (int) CACHE_HOURS);
        } catch (Exception e) {
            log.warn("Title draft for stream {} not cached: {}", streamId, e.getMessage());
        }
    }

    private Optional<Language> savedLanguage(Long userId) {
        String code = jdbc.query("SELECT ai_language FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        return Language.of(code);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
