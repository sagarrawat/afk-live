package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamItemRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.ReferralService;
import com.afklive.streamer.service.TitleWriterService;
import com.afklive.streamer.service.TitleWriterService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI-1 / GR-10: the title writer against the real database: no-key behaviour, fair use, the cache and the playlist. */
@SpringBootTest
class TitleWriterServiceTest extends PostgresIntegrationTest {

    @Autowired LiveStreamService streams;
    @Autowired StreamItemRepository items;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReferralService referrals;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    AiService ai;

    static final String ANSWER = """
            {"titles": ["Lofi beats to study · 24/7 replay", "🔴 LIVE NOW lofi radio", "Rainy lofi for focus"],
             "description": "Soft lofi beats with rain for studying.",
             "tags": ["lofi", "study music", "rain sounds", "chill beats", "focus music", "viral"]}
            """;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ai = mock(AiService.class);
        when(ai.isAvailable()).thenReturn(true);
        when(ai.model()).thenReturn("gemini-test");
        when(ai.completeJson(anyString())).thenReturn(ANSWER);
    }

    private TitleWriterService writer(int free, int paid) {
        return new TitleWriterService(streams, items, ai, jdbc, referrals, free, paid);
    }

    private String newUser(PlanType plan) {
        String email = "tw-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(plan);
        users.save(u);
        return email;
    }

    private LiveStream stream(String email) {
        LiveStream s = streams.create(email, new CreateStream("Lofi radio", null, null, 720, 30));
        List<Long> ids = new ArrayList<>();
        for (String[] t : new String[][] {{"01_midnight_rain.mp3", "185"}, {"sunrise.mp3", "200"}}) {
            MediaAsset m = new MediaAsset(users.findByEmail(email).orElseThrow(), t[0], "key/" + UUID.randomUUID(), 100L,
                    MediaAsset.ContentType.AUDIO);
            m.setDurationSeconds(Double.parseDouble(t[1]));
            ids.add(media.save(m).getId());
        }
        streams.setItems(email, s.getId(), StreamItem.Kind.AUDIO, ids);
        return s;
    }

    @Test
    void writesThreeHonestTitlesADescriptionWithTheTracklistAndTags() {
        String email = newUser(PlanType.FREE);
        LiveStream s = stream(email);
        Result r = writer(20, 100).write(email, s.getId(), "en", "rainy night desk");
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.draft().titles()).containsExactly("Lofi beats to study · 24/7 replay", "Lofi radio · 24/7 replay",
                "Rainy lofi for focus");
        assertThat(r.draft().description()).isEqualTo("Soft lofi beats with rain for studying.\n\nTracklist\n00:00 Midnight rain\n03:05 Sunrise");
        assertThat(r.draft().tags()).doesNotContain("viral").contains("study music");
        assertThat(r.usage().used()).isEqualTo(1);
        assertThat(r.usage().limit()).isEqualTo(20);
    }

    @Test
    void theSameInputsWithin24HoursReadTheCacheAndCostNothing() {
        String email = newUser(PlanType.FREE);
        LiveStream s = stream(email);
        TitleWriterService w = writer(20, 100);
        assertThat(w.write(email, s.getId(), "hi", null).cached()).isFalse();
        Result again = w.write(email, s.getId(), "hi", null);
        assertThat(again.cached()).isTrue();
        assertThat(again.usage().used()).isEqualTo(1);
        verify(ai, times(1)).completeJson(anyString());

        // Another language or a changed brief is new work.
        assertThat(w.write(email, s.getId(), "ta", null).cached()).isFalse();
        assertThat(w.write(email, s.getId(), "hi", "evening aarti").cached()).isFalse();
        verify(ai, times(3)).completeJson(anyString());

        // A day-old draft is written again.
        jdbc.update("UPDATE ai_drafts SET created_at = now() - interval '25 hours' WHERE stream_id = ?", s.getId());
        assertThat(w.write(email, s.getId(), "hi", null).cached()).isFalse();
    }

    @Test
    void theDailyLimitStopsWritingWithAMessageAndPaidPlansGetMore() {
        String free = newUser(PlanType.FREE);
        LiveStream s = stream(free);
        TitleWriterService w = writer(2, 5);
        assertThat(w.write(free, s.getId(), "en", "a").status()).isEqualTo("OK");
        assertThat(w.write(free, s.getId(), "en", "b").status()).isEqualTo("OK");
        Result limited = w.write(free, s.getId(), "en", "c");
        assertThat(limited.status()).isEqualTo("LIMIT");
        assertThat(limited.message()).contains("2 drafts today").contains("tomorrow");
        assertThat(limited.usage().used()).isEqualTo(2);
        verify(ai, times(2)).completeJson(anyString());
        // A cached draft is still readable at the limit.
        assertThat(w.write(free, s.getId(), "en", "a").status()).isEqualTo("OK");

        String paid = newUser(PlanType.ESSENTIALS);
        assertThat(w.status(paid, stream(paid).getId()).usage().limit()).isEqualTo(5);
    }

    @Test
    void twoWebInstancesRacingCannotPassTheLimit() throws Exception {
        String email = newUser(PlanType.FREE);
        Long userId = users.findByEmail(email).orElseThrow().getId();
        TitleWriterService a = writer(20, 100);
        TitleWriterService b = writer(20, 100); // a second instance: shares nothing but the database
        LocalDate day = LocalDate.now();
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            TitleWriterService w = i % 2 == 0 ? a : b;
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                if (claim(w, userId, day, 20)) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(granted.get()).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT generations FROM ai_usage WHERE user_id = ? AND day = ?", Integer.class, userId,
                java.sql.Date.valueOf(day))).isEqualTo(20);
    }

    /** The package-private claim, reached by reflection (the test lives with the other database tests). */
    private static boolean claim(TitleWriterService w, Long userId, LocalDate day, int limit) throws Exception {
        var m = TitleWriterService.class.getDeclaredMethod("claim", Long.class, LocalDate.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(w, userId, day, limit);
    }

    @Test
    void anUnusableAnswerFallsBackAndIsNotCounted() {
        when(ai.completeJson(anyString())).thenReturn("Sorry, I can't do that.");
        String email = newUser(PlanType.FREE);
        LiveStream s = stream(email);
        Result r = writer(20, 100).write(email, s.getId(), "en", null);
        assertThat(r.status()).isEqualTo("FALLBACK");
        assertThat(r.draft().titles()).containsExactly("Lofi radio", "Lofi radio · 24/7");
        assertThat(r.draft().description()).startsWith("Tracklist\n00:00 Midnight rain");
        assertThat(r.usage().used()).isZero();
        assertThat(r.message()).contains("wasn't counted");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_drafts WHERE stream_id = ?", Integer.class, s.getId())).isZero();
    }

    @Test
    void theChosenLanguageIsRememberedForNextTime() {
        String email = newUser(PlanType.FREE);
        LiveStream s = stream(email);
        TitleWriterService w = writer(20, 100);
        assertThat(w.status(email, s.getId()).language()).isNull();
        w.write(email, s.getId(), "mr", null);
        assertThat(w.status(email, s.getId()).language()).isEqualTo("mr");
        assertThat(w.write(email, s.getId(), null, null).language()).isEqualTo("mr");
        assertThat(w.write(email, s.getId(), "xx", null).language()).isEqualTo("mr");
    }

    @Test
    void withoutAnAiKeyTheWriterSaysNotAvailableAndNeverCallsOut() throws Exception {
        when(ai.isAvailable()).thenReturn(false);
        String email = newUser(PlanType.FREE);
        LiveStream s = stream(email);
        Result r = writer(20, 100).write(email, s.getId(), "en", null);
        assertThat(r.status()).isEqualTo("UNAVAILABLE");
        assertThat(r.usage().used()).isZero();
        verify(ai, never()).completeJson(anyString());

        // The real app in tests has no GEMINI_API_KEY: both endpoints answer 200, never a 500.
        mvc.perform(get("/api/streams/" + s.getId() + "/ai/metadata").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.languages.length()").value(6))
                .andExpect(jsonPath("$.usage.limit").value(20));
        mvc.perform(post("/api/streams/" + s.getId() + "/ai/metadata").with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }

    @Test
    void someoneElsesStreamIsNotFound() throws Exception {
        String owner = newUser(PlanType.FREE);
        LiveStream s = stream(owner);
        mvc.perform(get("/api/streams/" + s.getId() + "/ai/metadata").with(user(newUser(PlanType.FREE))))
                .andExpect(status().isNotFound());
    }
}
