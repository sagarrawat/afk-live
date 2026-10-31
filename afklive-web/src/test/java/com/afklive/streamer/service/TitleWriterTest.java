package com.afklive.streamer.service;

import com.afklive.streamer.service.TitleWriter.Draft;
import com.afklive.streamer.service.TitleWriter.Input;
import com.afklive.streamer.service.TitleWriter.Language;
import com.afklive.streamer.service.TitleWriter.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AI-1 / GR-10: the title writer's prompt, answer checks (GR-9 rules) and tracklist, without Spring. */
class TitleWriterTest {

    private static Input playlist(Language lang, boolean forever, List<Track> tracks) {
        return new Input("Lofi radio", "Lofi beats to study", "rainy night desk", false, forever, false, true, tracks, lang);
    }

    private static Input phone(Language lang) {
        return new Input("BGMI evening", null, null, true, false, false, false, List.of(), lang);
    }

    private static final List<Track> TRACKS = List.of(new Track("01 - midnight_rain.mp3", 185.0), new Track("Sunrise(1).mp3", 200.4),
            new Track("coffee-shop-jazz.m4a", 3300.0), new Track("Last one.mp3", 60.0));

    // ------------------------------------------------------------------ prompt

    @ParameterizedTest
    @EnumSource(Language.class)
    void everyLanguageGetsAnHonestPromptInItsOwnScript(Language lang) {
        String p = TitleWriter.prompt(playlist(lang, true, TRACKS));
        assertThat(p).contains("Reply with JSON only").contains("\"titles\"").contains("\"tags\"");
        assertThat(p).contains("words people would type into YouTube search").contains("No clickbait")
                .contains("at most 100 characters").contains("5 to 10");
        if (lang == Language.EN) {
            assertThat(p).contains("in English.");
        } else {
            assertThat(p).contains(lang.english + " (" + lang.nativeName + ")").contains("script");
        }
        String lower = p.toLowerCase();
        assertThat(lower).as("never asks for hype").doesNotContain("catchy").doesNotContain("viral title")
                .doesNotContain("engaging").doesNotContain("clickable");
        // "viral" appears only where it is forbidden.
        assertThat(lower.indexOf("viral")).isEqualTo(lower.indexOf("such as viral") + "such as ".length())
                .isEqualTo(lower.lastIndexOf("viral"));
    }

    @Test
    void aPlaylistPromptForbidsLiveAndOffersReplayWording() {
        String forever = TitleWriter.prompt(playlist(Language.EN, true, TRACKS));
        assertThat(forever).contains("Never write \"LIVE\", \"live now\", \"streaming now\" or a red dot")
                .contains("\"24/7\" and \"replay\" are fine").contains("don't call it live").contains("and no \"live\"")
                .contains("recorded earlier, around the clock");
        String finite = TitleWriter.prompt(playlist(Language.HI, false, TRACKS));
        assertThat(finite).contains("Never write \"LIVE\"").contains("\"replay\" is fine").doesNotContain("\"24/7\" and");
    }

    @Test
    void aPhoneLiveMaySayLiveButNotShoutIt() {
        String p = TitleWriter.prompt(phone(Language.EN));
        assertThat(p).contains("really live, from their phone").contains("saying it's live is fine")
                .doesNotContain("Never write \"LIVE\"").doesNotContain("don't call it live");
    }

    @Test
    void thePromptListsCleanTrackNamesAndCannotBeBrokenOutOf() {
        Input in = new Input("say \"LIVE NOW\" please", null, "x".repeat(500), false, true, false, true, TRACKS, Language.EN);
        String p = TitleWriter.prompt(in);
        assertThat(p).contains("\"say 'LIVE NOW' please\"").contains("\"Midnight rain\"").contains("\"Coffee shop jazz\"")
                .contains("Music that plays (4)").doesNotContain("x".repeat(201));
    }

    // ------------------------------------------------------------------ answer checks (GR-9 rules)

    @Test
    void aGoodAnswerIsKeptAndGetsTheTracklist() {
        String raw = """
                {"titles": ["Lofi beats to study · 24/7 replay", "Rainy night lofi for focus", "Chill lofi mix for work"],
                 "description": "Soft lofi beats with rain sounds for studying and sleep.",
                 "tags": ["lofi", "study music", "rain sounds", "chill beats", "lofi hip hop", "focus music"]}
                """;
        Draft d = TitleWriter.parse(raw, playlist(Language.EN, true, TRACKS));
        assertThat(d.titles()).containsExactly("Lofi beats to study · 24/7 replay", "Rainy night lofi for focus", "Chill lofi mix for work");
        assertThat(d.description()).startsWith("Soft lofi beats with rain sounds").contains("\n\nTracklist\n0:00:00 Midnight rain\n");
        assertThat(d.tags()).hasSize(6).contains("study music");
    }

    @Test
    void liveWordingOnAReplayIsRewrittenTheWayTheOneTapFixDoes() {
        String raw = """
                {"titles": ["🔴 LIVE NOW Lofi beats to study", "Lofi radio live now", "LIVE lofi beats"],
                 "description": "🔴 LIVE now: lofi beats for studying. #lofi #study",
                 "tags": ["live", "live stream", "lofi", "viral", "trending", "2026", "#study music", "lofi", "Lofi"]}
                """;
        Draft d = TitleWriter.parse(raw, playlist(Language.EN, true, List.of()));
        for (String t : d.titles()) {
            assertThat(GoLiveCheck.misleadingLive(t)).as(t).isNull();
            assertThat(GoLiveCheck.hasSearchablePhrase(t)).isTrue();
            assertThat(t).doesNotContain("🔴");
        }
        assertThat(d.titles()).contains("Lofi beats to study · 24/7 replay");
        assertThat(d.description()).doesNotContain("LIVE").doesNotContain("#").doesNotContain("🔴").contains("lofi beats for studying");
        assertThat(d.tags()).doesNotContain("live", "live stream", "viral", "trending", "2026").contains("lofi", "study music");
        assertThat(d.tags().stream().filter("lofi"::equalsIgnoreCase)).hasSize(1);
        assertThat(d.tags()).hasSizeBetween(5, 10);
    }

    @Test
    void onAPhoneLiveTheWordLiveStays() {
        Draft d = TitleWriter.parse("{\"titles\":[\"BGMI LIVE rush gameplay\"],\"description\":\"BGMI live.\",\"tags\":[\"BGMI LIVE\",\"bgmi\"]}",
                phone(Language.EN));
        assertThat(d.titles()).containsExactly("BGMI LIVE rush gameplay");
        assertThat(d.tags()).contains("BGMI LIVE", "bgmi");
        Draft replay = TitleWriter.parse("{\"titles\":[\"BGMI LIVE rush gameplay\"],\"tags\":[\"BGMI LIVE\",\"bgmi\"]}",
                playlist(Language.EN, false, List.of()));
        assertThat(replay.titles()).containsExactly("BGMI rush gameplay · replay");
        assertThat(replay.tags()).doesNotContain("BGMI LIVE").contains("bgmi");
    }

    @Test
    void titlesAreCutToYouTubesLimitAtAWordAndEmptyOrUnsearchableOnesAreDropped() {
        String longTitle = "Hanuman Chalisa " + "bhajan ".repeat(30);
        String raw = "{\"titles\": [\"" + longTitle + "\", \"Live stream now\", \"\", \"1. \\\"Morning aarti\\\"\", \"#bhajan #live\"],"
                + "\"description\": \"\", \"tags\": []}";
        Draft d = TitleWriter.parse(raw, playlist(Language.HI, false, List.of()));
        assertThat(d.titles()).hasSize(2);
        assertThat(d.titles().get(0)).hasSizeLessThanOrEqualTo(100).startsWith("Hanuman Chalisa").doesNotEndWith(" ");
        assertThat(d.titles().get(1)).isEqualTo("Morning aarti");
    }

    @Test
    void indicTitlesPassTheSearchablePhraseCheck() {
        Draft d = TitleWriter.parse("{\"titles\":[\"हनुमान चालीसा · 24/7 replay\",\"காலை பக்தி பாடல்கள்\"],\"description\":\"सुबह की आरती।\",\"tags\":[\"हनुमान चालीसा\",\"भजन\"]}",
                playlist(Language.HI, true, List.of()));
        assertThat(d.titles()).as("a Tamil title in a Hindi draft is in the wrong script, so it's dropped")
                .containsExactly("हनुमान चालीसा · 24/7 replay");
        assertThat(d.tags()).contains("हनुमान चालीसा", "भजन").hasSizeGreaterThanOrEqualTo(5);
        Draft ta = TitleWriter.parse("{\"titles\":[\"காலை பக்தி பாடல்கள்\"],\"description\":\"\",\"tags\":[]}",
                playlist(Language.TA, true, List.of()));
        assertThat(ta.titles()).containsExactly("காலை பக்தி பாடல்கள்");
    }

    @Test
    void anUnusableAnswerIsNullSoTheServiceFallsBack() {
        Input in = playlist(Language.EN, true, TRACKS);
        assertThat(TitleWriter.parse(null, in)).isNull();
        assertThat(TitleWriter.parse("Sorry, I can't help with that.", in)).isNull();
        assertThat(TitleWriter.parse("{\"titles\": [\"LIVE\", \"stream now\"]}", in)).isNull();
        assertThat(TitleWriter.parse("{not json", in)).isNull();
        // JSON wrapped in prose or a code fence still reads.
        assertThat(TitleWriter.parse("```json\n{\"titles\":[\"Rain sounds for sleep\"]}\n```", in).titles()).containsExactly("Rain sounds for sleep");
    }

    @Test
    void theFallbackUsesTheCreatorsOwnWordsMadeHonest() {
        Input in = new Input("Lofi radio", "🔴 LIVE lofi beats", null, false, true, false, true, TRACKS, Language.EN);
        Draft d = TitleWriter.fallback(in);
        assertThat(d.titles()).containsExactly("Lofi beats · 24/7 replay");
        assertThat(d.description()).startsWith("Tracklist\n");
        assertThat(d.tags()).contains("lofi beats").doesNotContain("live");
    }

    @Test
    void tagsStayWithinYouTubesTotal() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("relaxing piano music number " + (char) ('a' + i % 26) + i);
        }
        List<String> tags = TitleWriter.cleanTags(many, playlist(Language.EN, true, List.of()));
        assertThat(tags).hasSizeLessThanOrEqualTo(10);
        assertThat(String.join(",", tags).length()).isLessThanOrEqualTo(500);
    }

    // ------------------------------------------------------------------ tracklist

    @Test
    void timestampsAddUpTheLengthsBeforeEachTrack() {
        String list = TitleWriter.tracklist(List.of(new Track("a.mp3", 185.0), new Track("b.mp3", 200.4), new Track("c.mp3", 60.0)),
                false, Language.EN);
        assertThat(list).isEqualTo("Tracklist\n00:00 A\n03:05 B\n06:25 C");
    }

    @Test
    void anHourOrMoreSwitchesEveryTimestampToHours() {
        String list = TitleWriter.tracklist(TRACKS, false, Language.EN);
        assertThat(list).isEqualTo("Tracklist\n0:00:00 Midnight rain\n0:03:05 Sunrise\n0:06:25 Coffee shop jazz\n1:01:25 Last one");
    }

    @Test
    void shuffledPlaylistsGetNoTimestampsAndUnknownLengthsStopThem() {
        assertThat(TitleWriter.tracklist(TRACKS, true, Language.HI)).isEqualTo("ट्रैकलिस्ट\n- Midnight rain\n- Sunrise\n- Coffee shop jazz\n- Last one");
        String list = TitleWriter.tracklist(List.of(new Track("a.mp3", 60.0), new Track("b.mp3", null), new Track("c.mp3", 60.0)),
                false, Language.TA);
        assertThat(list).isEqualTo("டிராக்லிஸ்ட்\n00:00 A\n01:00 B\n- C");
    }

    @Test
    void longPlaylistsEndWithACount() {
        List<Track> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new Track("Track " + i, 120.0));
        }
        String list = TitleWriter.tracklist(many, false, Language.EN);
        assertThat(list.lines().count()).isEqualTo(1 + 100 + 1);
        assertThat(list).endsWith("\n+50").contains("3:18:00 Track 99");
        assertThat(TitleWriter.tracklist(List.of(), false, Language.EN)).isEmpty();
    }

    @Test
    void fileNamesBecomeReadable() {
        assertThat(TitleWriter.cleanFileName("01 - midnight_rain.mp3")).isEqualTo("Midnight rain");
        assertThat(TitleWriter.cleanFileName("coffee-shop-jazz.m4a")).isEqualTo("Coffee shop jazz");
        assertThat(TitleWriter.cleanFileName("Sunrise (1).MP4")).isEqualTo("Sunrise");
        assertThat(TitleWriter.cleanFileName("Hanuman Chalisa [Official Video] 1080p.mp4")).isEqualTo("Hanuman Chalisa");
        assertThat(TitleWriter.cleanFileName("lo-fi beats vol. 2.mp3")).isEqualTo("Lo-fi beats vol. 2");
        assertThat(TitleWriter.cleanFileName("गायत्री_मंत्र.mp3")).isEqualTo("गायत्री मंत्र");
        assertThat(TitleWriter.cleanFileName(".mp3")).isEmpty();
    }

    @Test
    void languagesReadFromCodesAndLocales() {
        assertThat(Language.of("hi-IN")).contains(Language.HI);
        assertThat(Language.of("TA")).contains(Language.TA);
        assertThat(Language.of("fr")).isEmpty();
        assertThat(Language.of(null)).isEmpty();
    }
}
