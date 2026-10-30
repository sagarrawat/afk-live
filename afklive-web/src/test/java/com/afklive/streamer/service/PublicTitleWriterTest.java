package com.afklive.streamer.service;

import com.afklive.streamer.PostgresIntegrationTest;
import com.afklive.streamer.service.PublicTitleWriter.Request;
import com.afklive.streamer.service.PublicTitleWriter.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MK-7: the free public title writer: validation, limits per visitor and per day (in the database), the budget, Turnstile. */
@SpringBootTest
class PublicTitleWriterTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;

    static final String ANSWER = """
            {"titles": ["🔴 LIVE NOW Hanuman Chalisa", "Hanuman Chalisa morning path 24/7", "Hanuman Chalisa for peace"],
             "description": "Hanuman Chalisa recited every morning. LIVE now!",
             "tags": ["hanuman chalisa", "bhajan", "aarti", "devotional", "morning prayer", "viral", "live"]}
            """;

    AiService ai;
    RestTemplate cloudflare;
    ZonedDateTime day;
    String ipBase;

    @BeforeEach
    void setUp() {
        ai = mock(AiService.class);
        when(ai.isAvailable()).thenReturn(true);
        when(ai.completeJson(anyString())).thenReturn(ANSWER);
        cloudflare = mock(RestTemplate.class);
        // A day of its own, so other runs' counters don't count here.
        String tag = UUID.randomUUID().toString();
        day = ZonedDateTime.of(2200 + Math.floorMod(tag.hashCode(), 7000), 3, 10, 9, 15, 0, 0, ZoneOffset.UTC);
        ipBase = "203.0." + Math.floorMod(tag.hashCode() >> 8, 250) + ".";
    }

    private PublicTitleWriter writer(int hour, int dayLimit, int all, TurnstileVerifier t, ZonedDateTime at) {
        PublicTitleWriter w = new PublicTitleWriter(ai, jdbc, t == null ? new TurnstileVerifier("", "", cloudflare) : t, hour, dayLimit, all);
        w.setClock(Clock.fixed(at.toInstant(), ZoneOffset.UTC));
        return w;
    }

    static Request req(String niche, String desc, String lang, String mode) {
        return new Request(niche, desc, lang, mode, null);
    }

    static final Request GOOD = req("devotional", "Hanuman Chalisa every morning", "en", "replay");

    @Test
    void writesAnHonestDraftFromThePickedNicheAndDescription() {
        Result r = writer(5, 15, 300, null, day).write(GOOD, ipBase + "1");
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.titles()).hasSizeBetween(1, 3).allSatisfy(t -> {
            assertThat(GoLiveCheck.misleadingLive(t)).as("a replay is never LIVE").isNull();
            assertThat(t.length()).isLessThanOrEqualTo(100);
        });
        assertThat(r.titles().getFirst()).isEqualTo("Hanuman Chalisa · 24/7 replay");
        assertThat(r.description()).doesNotContain("LIVE");
        assertThat(r.tags()).hasSizeBetween(5, 10).doesNotContain("viral", "live");
        assertThat(r.language()).isEqualTo("en");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(prompt.capture());
        assertThat(prompt.getValue()).as("the creator writer's prompt, from the niche and description")
                .isEqualTo(TitleWriter.prompt(new TitleWriter.Input("Devotional: bhajan, aarti, mantra", null,
                        "Hanuman Chalisa every morning", false, true, false, false, java.util.List.of(), TitleWriter.Language.EN)));
        assertThat(prompt.getValue()).contains("Never write \"LIVE\"");
    }

    @Test
    void liveFromAPhoneMayBeCalledLive() {
        Result r = writer(5, 15, 300, null, day).write(req("gaming", "BGMI rush gameplay with chat", "hi", "phone"), ipBase + "2");
        assertThat(r.status()).isEqualTo("OK");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(prompt.capture());
        assertThat(prompt.getValue()).contains("really live, from their phone").contains("Hindi (हिन्दी)");
    }

    @Test
    void badInputIsRefusedAndNotCounted() {
        PublicTitleWriter w = writer(1, 1, 1, null, day);
        String ip = ipBase + "3";
        for (Request bad : new Request[] {
                req("cooking", "x y z", "en", "replay"), req("lofi", "lofi beats", "fr", "replay"), req("lofi", "lofi beats", "en", "vod"),
                req("lofi", "ab", "en", "replay"), req("lofi", "a".repeat(PublicTitleWriter.DESCRIPTION_MAX + 1), "en", "replay"),
                req(null, null, null, null)}) {
            assertThat(w.write(bad, ip).status()).as(String.valueOf(bad)).isEqualTo("INVALID");
        }
        verifyNoInteractions(ai);
        assertThat(w.write(GOOD, ip).status()).as("nothing was counted").isEqualTo("OK");
    }

    @Test
    void descriptionsArePlainText() {
        assertThat(PublicTitleWriter.clean("  <script>alert(1)</script>\n\tlofi\u0000 beats ")).isEqualTo("script alert(1) /script lofi beats");
        assertThat(PublicTitleWriter.clean("ট্র্যাক‍লিস্ট")).as("joiners Indic scripts need stay").contains("‍");
    }

    @Test
    void eachVisitorGetsFiveAnHourAndFifteenADay() {
        String ip = ipBase + "4";
        PublicTitleWriter w = writer(2, 3, 300, null, day);
        assertThat(w.write(GOOD, ip).status()).isEqualTo("OK");
        assertThat(w.write(GOOD, ip).status()).isEqualTo("OK");
        Result third = w.write(GOOD, ip);
        assertThat(third.status()).isEqualTo("LIMIT");
        assertThat(third.message()).contains("in the last hour").contains("sign up free");
        assertThat(PublicTitleWriter.httpStatus(third.status())).isEqualTo(429);
        assertThat(w.write(GOOD, ipBase + "5").status()).as("another visitor").isEqualTo("OK");

        // An hour later the hourly limit starts over, but the day's is 3.
        PublicTitleWriter later = writer(2, 3, 300, null, day.plusHours(1));
        assertThat(later.write(GOOD, ip).status()).isEqualTo("OK");
        Result fourth = later.write(GOOD, ip);
        assertThat(fourth.status()).isEqualTo("LIMIT");
        assertThat(fourth.message()).contains("today");
        // The next day starts over.
        assertThat(writer(2, 3, 300, null, day.plusDays(1)).write(GOOD, ip).status()).isEqualTo("OK");
        verify(ai, times(5)).completeJson(anyString());
    }

    @Test
    void allVisitorsTogetherStopAtTheDailyLimit() {
        PublicTitleWriter w = writer(5, 15, 3, null, day);
        for (int i = 0; i < 3; i++) {
            assertThat(w.write(GOOD, ipBase + (10 + i)).status()).isEqualTo("OK");
        }
        Result busy = w.write(GOOD, ipBase + "20");
        assertThat(busy.status()).isEqualTo("BUSY");
        assertThat(busy.message()).contains("Sign up free");
        assertThat(w.usedToday()).isEqualTo(3);
        assertThat(w.status().available()).as("the page shows the tool resting").isFalse();
        // The refused visitor wasn't charged for it.
        String visitor = TitleWriterService.sha256(ipBase + "20|" + day.toLocalDate());
        assertThat(jdbc.queryForObject("SELECT count FROM ai_public_counts WHERE counter = ?", Integer.class, "ip:d:" + visitor)).isZero();
        assertThat(jdbc.queryForObject("SELECT count FROM ai_public_counts WHERE counter = ?", Integer.class,
                "ip:h:" + visitor + ":" + day.getHour())).isZero();
    }

    @Test
    void offOrOverTheMonthlyBudgetTheToolRests() {
        when(ai.isAvailable()).thenReturn(false);
        PublicTitleWriter w = writer(5, 15, 300, null, day);
        Result r = w.write(GOOD, ipBase + "30");
        assertThat(r.status()).isEqualTo("UNAVAILABLE");
        assertThat(r.message()).contains("resting");
        assertThat(PublicTitleWriter.httpStatus(r.status())).isEqualTo(503);
        assertThat(w.status().available()).isFalse();
        verify(ai, never()).completeJson(anyString());
        assertThat(w.usedToday()).isZero();
    }

    @Test
    void anUnusableAnswerIsAFailureAndStillCounts() {
        when(ai.completeJson(anyString())).thenReturn("sorry");
        PublicTitleWriter w = writer(1, 15, 300, null, day);
        assertThat(w.write(GOOD, ipBase + "40").status()).isEqualTo("FAILED");
        assertThat(w.write(GOOD, ipBase + "40").status()).as("it reached the model and was paid for").isEqualTo("LIMIT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void turnstileIsSkippedWithoutKeysAndCheckedWithThem() {
        writer(5, 15, 300, null, day).write(GOOD, ipBase + "50");
        verifyNoInteractions(cloudflare);
        assertThat(new TurnstileVerifier("secret", "", cloudflare).enabled()).as("both keys needed").isFalse();
        assertThat(new TurnstileVerifier("", "", cloudflare).siteKey()).isNull();

        TurnstileVerifier on = new TurnstileVerifier("cf-secret", "cf-site", cloudflare);
        assertThat(on.siteKey()).isEqualTo("cf-site");
        PublicTitleWriter w = writer(5, 15, 300, on, day);
        Result noToken = w.write(GOOD, ipBase + "51");
        assertThat(noToken.status()).isEqualTo("CHECK");
        assertThat(PublicTitleWriter.httpStatus(noToken.status())).isEqualTo(403);

        when(cloudflare.postForObject(eq(TurnstileVerifier.URL), any(), eq(Map.class))).thenReturn(Map.of("success", false));
        assertThat(w.write(new Request("lofi", "lofi beats", "en", "replay", "bad-token"), ipBase + "51").status()).isEqualTo("CHECK");

        when(cloudflare.postForObject(eq(TurnstileVerifier.URL), any(), eq(Map.class))).thenReturn(Map.of("success", true));
        assertThat(w.write(new Request("lofi", "lofi beats", "en", "replay", "good-token"), ipBase + "51").status()).isEqualTo("OK");
        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> body = ArgumentCaptor.forClass(HttpEntity.class);
        verify(cloudflare, times(2)).postForObject(eq(TurnstileVerifier.URL), body.capture(), eq(Map.class));
        assertThat(body.getValue().getBody().getFirst("secret")).isEqualTo("cf-secret");
        assertThat(body.getValue().getBody().getFirst("response")).isEqualTo("good-token");
        assertThat(body.getValue().getBody().getFirst("remoteip")).isEqualTo(ipBase + "51");
        assertThat(body.getValue().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);

        when(cloudflare.postForObject(eq(TurnstileVerifier.URL), any(), eq(Map.class))).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
        assertThat(on.verify("t", "1.2.3.4")).as("no answer from Cloudflare: not verified").isFalse();
    }

    @Test
    void theEndpointsArePublicNeedCsrfAndSayWhenAiIsOff() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/public/title-writer/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(false)) // no AI key in tests
                .andExpect(jsonPath("$.turnstileSiteKey").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.descriptionMax").value(200))
                .andExpect(jsonPath("$.niches[0].id").value("lofi"))
                .andExpect(jsonPath("$.languages.length()").value(6));
        String body = "{\"niche\":\"lofi\",\"description\":\"lofi beats to study\",\"language\":\"en\",\"mode\":\"replay\"}";
        mvc.perform(post("/api/public/title-writer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/title-writer").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("This tool is resting right now. Try again later."));
        mvc.perform(post("/api/public/title-writer").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"niche\":\"nope\",\"description\":\"x\",\"language\":\"en\",\"mode\":\"replay\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID"));
    }
}
