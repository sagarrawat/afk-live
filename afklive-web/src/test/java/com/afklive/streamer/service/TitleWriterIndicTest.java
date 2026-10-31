package com.afklive.streamer.service;

import com.afklive.streamer.service.TitleWriter.Draft;
import com.afklive.streamer.service.TitleWriter.Input;
import com.afklive.streamer.service.TitleWriter.Language;
import com.afklive.streamer.service.TitleWriter.Track;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-1 / GR-10 Indic quality check, offline: 30 realistic streams (lofi, devotional, gaming, podcasts, kids rhymes)
 * in English, Hindi, Tamil, Telugu, Bengali and Marathi ({@code ai-eval/inputs.json}), and recorded model answers
 * for some of them, several deliberately bad ({@code ai-eval/answers.json}). The title writer's checks must turn
 * each answer into a draft in the right script, with no misleading "live" on a replay (in English or in the
 * language's own script), within YouTube's limits and with 5–10 tags. No API calls: the real-model run is
 * {@code afklive-scripts/ai-eval.sh}.
 */
class TitleWriterIndicTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** One stream from the fixture. Replays play around the clock (the usual AFK Live stream). */
    record Case(String id, String niche, Input input) {
    }

    static List<Case> cases() throws IOException {
        List<Case> out = new ArrayList<>();
        for (JsonNode n : read("/ai-eval/inputs.json")) {
            List<Track> tracks = new ArrayList<>();
            n.path("tracks").forEach(t -> tracks.add(new Track(t.asText(), 240.0)));
            boolean phone = n.path("phone").asBoolean();
            Language lang = Language.of(n.path("language").asText()).orElseThrow();
            out.add(new Case(n.path("id").asText(), n.path("niche").asText(),
                    new Input(n.path("name").asText(), null, n.path("brief").asText(null), phone, !phone, false, true, tracks, lang)));
        }
        return out;
    }

    /** id → the raw answer text the model returned. */
    static Map<String, String> answers() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        read("/ai-eval/answers.json").fields().forEachRemaining(e -> {
            if (!e.getKey().startsWith("_")) {
                out.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
            }
        });
        return out;
    }

    static JsonNode read(String path) throws IOException {
        try (InputStream in = TitleWriterIndicTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return JSON.readTree(in);
        }
    }

    @Test
    void theFixtureCoversEveryLanguageAndNiche() throws IOException {
        List<Case> cases = cases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
        assertThat(cases.stream().map(c -> c.input().language()).distinct()).containsExactlyInAnyOrder(Language.values());
        assertThat(cases.stream().map(Case::niche).distinct()).contains("lofi", "devotional", "gaming", "podcast", "kids");
        assertThat(cases.stream().filter(c -> c.input().phone())).as("some are really live from a phone").isNotEmpty();
        assertThat(answers().keySet()).as("every recorded answer belongs to an input")
                .allMatch(id -> cases.stream().anyMatch(c -> c.id().equals(id)));
        assertThat(answers().keySet().stream().map(id -> id.substring(0, 2)).distinct())
                .as("answers in every language").containsExactlyInAnyOrder("en", "hi", "ta", "te", "bn", "mr");
    }

    @Test
    void everyPromptAsksForTheRightLanguageAndHonestWording() throws IOException {
        for (Case c : cases()) {
            String p = TitleWriter.prompt(c.input());
            Language l = c.input().language();
            assertThat(p).as(c.id()).contains("write the titles, description and tags in " + (l == Language.EN ? "English." : l.english + " ("));
            if (c.input().phone()) {
                assertThat(p).as(c.id()).contains("really live").doesNotContain("Never write \"LIVE\"");
            } else {
                assertThat(p).as(c.id()).contains("Never write \"LIVE\"").contains("recorded earlier");
            }
        }
    }

    @Test
    void recordedAnswersBecomeDraftsInTheRightScriptWithoutMisleadingLive() throws IOException {
        Map<String, String> answers = answers();
        int checked = 0;
        for (Case c : cases()) {
            String raw = answers.get(c.id());
            if (raw == null) {
                continue;
            }
            Input in = c.input();
            Draft d = TitleWriter.parse(raw, in);
            assertThat(d).as(c.id()).isNotNull();
            assertDraft(c, d);
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
    }

    @Test
    void withoutAnAnswerTheFallbackObeysTheSameRules() throws IOException {
        for (Case c : cases()) {
            Draft d = TitleWriter.fallback(c.input());
            assertThat(d.titles()).as(c.id()).isNotEmpty();
            for (String t : d.titles()) {
                assertThat(t.length()).as(c.id()).isLessThanOrEqualTo(TitleWriter.TITLE_MAX);
                if (TitleWriter.inScript(c.input().streamName(), c.input().language())) {
                    // The fallback is the creator's own words: in the language's script when theirs were.
                    assertThat(TitleWriter.inScript(t, c.input().language())).as(c.id() + ": " + t).isTrue();
                }
                if (!c.input().phone()) {
                    assertThat(GoLiveCheck.misleadingLive(t)).as(c.id()).isNull();
                }
            }
            assertThat(d.tags().size()).as(c.id()).isLessThanOrEqualTo(TitleWriter.TAGS_MAX);
        }
    }

    static void assertDraft(Case c, Draft d) {
        Input in = c.input();
        Language l = in.language();
        assertThat(d.titles()).as(c.id()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        for (String t : d.titles()) {
            String why = c.id() + ": " + t;
            assertThat(t.length()).as(why).isLessThanOrEqualTo(TitleWriter.TITLE_MAX);
            assertThat(TitleWriter.inScript(t, l)).as(why + " is in " + l.english + " script").isTrue();
            assertThat(t).as(why).doesNotContain("#").doesNotContain("🔴").doesNotContain("🙏").doesNotContain("\n");
            if (!in.phone()) {
                assertThat(GoLiveCheck.misleadingLive(t)).as(why).isNull();
                assertThat(TitleWriter.withoutNativeLive(t)).as(why + " has no 'live' in " + l.english).isNull();
            }
        }
        assertThat(d.description().length()).as(c.id()).isLessThanOrEqualTo(TitleWriter.DESCRIPTION_MAX);
        String intro = d.description().split("\n\n" + l.tracklistHeading)[0];
        assertThat(TitleWriter.inScript(intro, l)).as(c.id() + " description: " + intro).isTrue();
        assertThat(intro).as(c.id()).doesNotContain("#").doesNotContain("🙏");
        if (!in.phone()) {
            assertThat(GoLiveCheck.misleadingLive(intro)).as(c.id()).isNull();
            assertThat(intro.toLowerCase(Locale.ROOT)).as(c.id()).doesNotContain("live now");
            assertThat(TitleWriter.withoutNativeLive(intro)).as(c.id() + " description").isNull();
        }
        assertThat(d.tags().size()).as(c.id() + " tags " + d.tags()).isBetween(TitleWriter.TAGS_MIN, TitleWriter.TAGS_MAX);
        assertThat(String.join(",", d.tags()).length()).isLessThanOrEqualTo(TitleWriter.TAGS_MAX_TOTAL);
        for (String tag : d.tags()) {
            String k = tag.toLowerCase(Locale.ROOT);
            assertThat(k).as(c.id()).isNotIn("viral", "#viral", "trending").doesNotMatch("(19|20)\\d\\d").doesNotStartWith("#");
            if (!in.phone()) {
                assertThat(k).as(c.id()).isNotEqualTo("live");
                assertThat(TitleWriter.withoutNativeLive(tag)).as(c.id() + " tag " + tag).isNull();
            }
        }
    }

    @Test
    void specificRewrites() throws IOException {
        Map<String, Case> byId = new LinkedHashMap<>();
        cases().forEach(c -> byId.put(c.id(), c));
        Map<String, String> a = answers();

        Draft hanuman = TitleWriter.parse(a.get("hi-hanuman-chalisa"), byId.get("hi-hanuman-chalisa").input());
        assertThat(hanuman.titles()).as("'लाइव' rewritten like the GR-9 fix; the English slip dropped")
                .containsExactly("हनुमान चालीसा पाठ 24 घंटे · 24/7 replay", "हनुमान चालीसा | संकट मोचन हनुमान अष्टक");
        assertThat(hanuman.tags()).doesNotContain("लाइव", "trending");

        Draft murugan = TitleWriter.parse(a.get("ta-murugan"), byId.get("ta-murugan").input());
        assertThat(murugan.titles()).as("code fences read; the English title dropped")
                .containsExactly("முருகன் பக்தி பாடல்கள் | கந்த சஷ்டி கவசம்", "கந்த சஷ்டி கவசம் 24/7");

        Draft kids = TitleWriter.parse(a.get("en-kids-rhymes"), byId.get("en-kids-rhymes").input());
        assertThat(kids.titles().getFirst()).as("cut at a word within 100").hasSizeLessThanOrEqualTo(100).doesNotEndWith(",");

        Draft bgmi = TitleWriter.parse(a.get("hi-bgmi-phone"), byId.get("hi-bgmi-phone").input());
        assertThat(bgmi.titles().getFirst()).as("really live from a phone: 'लाइव' is honest").contains("लाइव");

        Draft ganpati = TitleWriter.parse(a.get("mr-ganpati-aarti"), byId.get("mr-ganpati-aarti").input());
        assertThat(ganpati.titles()).contains("गणपती आरती | सुखकर्ता दुखहर्ता");
    }

    @Test
    void scriptCheck() {
        assertThat(TitleWriter.inScript("Lofi beats to study", Language.EN)).isTrue();
        assertThat(TitleWriter.inScript("हनुमान चालीसा", Language.EN)).isFalse();
        assertThat(TitleWriter.inScript("Lofi गाने 24/7", Language.HI)).isTrue();
        assertThat(TitleWriter.inScript("Lofi songs 24/7", Language.HI)).as("no Devanagari at all").isFalse();
        assertThat(TitleWriter.inScript("गणपती आरती", Language.MR)).isTrue();
        assertThat(TitleWriter.inScript("முருகன் பாடல்கள்", Language.TA)).isTrue();
        assertThat(TitleWriter.inScript("முருகன் हनुमान", Language.TA)).as("two Indic scripts").isFalse();
        assertThat(TitleWriter.inScript("చందమామ రావే", Language.TE)).isTrue();
        assertThat(TitleWriter.inScript("আয় আয় চাঁদ মামা", Language.BN)).isTrue();
        assertThat(TitleWriter.inScript("আয় আয় চাঁদ মামা", Language.HI)).isFalse();
        assertThat(TitleWriter.withoutNativeLive("लाईव्ह विठ्ठल अभंग")).isEqualTo("विठ्ठल अभंग");
        assertThat(TitleWriter.withoutNativeLive("लाइव अभी भजन")).isEqualTo("भजन");
        assertThat(TitleWriter.withoutNativeLive("লাইভ রবীন্দ্রসংগীত এখন")).isEqualTo("রবীন্দ্রসংগীত");
        assertThat(TitleWriter.withoutNativeLive("Hanuman Chalisa")).isNull();
    }
}
