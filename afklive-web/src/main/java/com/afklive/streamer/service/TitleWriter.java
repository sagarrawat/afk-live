package com.afklive.streamer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI-1 / GR-10: the title writer's rules, kept free of storage and Spring so each is tested on its own.
 * It builds one prompt for three titles, a description and tags in the creator's language, checks the
 * model's answer with the GR-9 title rules ({@link GoLiveCheck}), and adds a tracklist with timestamps
 * worked out from the playlist (never left to the model). Everything it returns is a draft the creator
 * edits and copies into YouTube Studio: nothing is posted anywhere.
 */
public final class TitleWriter {

    private TitleWriter() {
    }

    /** YouTube's limits, in characters. */
    static final int TITLE_MAX = 100;
    static final int DESCRIPTION_MAX = 5000;
    static final int TAGS_MAX_TOTAL = 500;
    /** The part of the description the model writes; the tracklist comes after it. */
    static final int INTRO_MAX = 1200;
    static final int TRACKLIST_MAX_CHARS = 3000;
    static final int TRACKLIST_MAX_LINES = 100;
    static final int TAGS_MIN = 5;
    static final int TAGS_MAX = 10;
    static final int BRIEF_MAX = 200;

    /** The languages a creator can pick: English, Hindi and one regional language (GR-10). */
    public enum Language {
        EN("English", "English", "Tracklist"),
        HI("Hindi", "हिन्दी", "ट्रैकलिस्ट"),
        TA("Tamil", "தமிழ்", "டிராக்லிஸ்ட்"),
        TE("Telugu", "తెలుగు", "ట్రాక్‌లిస్ట్"),
        BN("Bengali", "বাংলা", "ট্র্যাকলিস্ট"),
        MR("Marathi", "मराठी", "ट्रॅकलिस्ट");

        public final String english;
        public final String nativeName;
        final String tracklistHeading;

        Language(String english, String nativeName, String tracklistHeading) {
            this.english = english;
            this.nativeName = nativeName;
            this.tracklistHeading = tracklistHeading;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** "hi", "HI" or "hi-IN"; empty for anything else. */
        public static Optional<Language> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.empty();
            }
            String c = code.strip().split("[-_]")[0].toUpperCase(Locale.ROOT);
            return Arrays.stream(values()).filter(l -> l.name().equals(c)).findFirst();
        }
    }

    /** One playlist entry: its library title (often a file name) and length in seconds (null when unknown). */
    public record Track(String title, Double seconds) {
    }

    /**
     * Everything the writer reads about one stream.
     *
     * @param streamName   the stream's name in AFK Live
     * @param currentTitle the YouTube title the creator typed in (GR-9), or null
     * @param brief        a few words from the creator ("morning aarti from Varanasi"), or null
     * @param phone        the creator is really live from their phone
     * @param forever      a playlist that repeats without end
     * @param shuffled     the playlist plays in random order (no timestamps then)
     * @param music        the tracks are the music playlist (otherwise the videos)
     * @param tracks       what plays, in order
     */
    public record Input(String streamName, String currentTitle, String brief, boolean phone, boolean forever, boolean shuffled,
                        boolean music, List<Track> tracks, Language language) {
    }

    /** The draft: up to three titles, a description (tracklist included) and 5–10 tags. */
    public record Draft(List<String> titles, String description, List<String> tags) {
    }

    // ------------------------------------------------------------------ prompt

    public static String prompt(Input in) {
        StringBuilder p = new StringBuilder();
        p.append("You write YouTube metadata for a creator. Reply with JSON only, in this shape: ")
                .append("{\"titles\": [\"...\", \"...\", \"...\"], \"description\": \"...\", \"tags\": [\"...\"]}.\n\n");

        p.append("Language: write the titles, description and tags in ").append(languageLine(in.language()))
                .append(" Keep names of songs, artists, deities and places as they are.\n\n");

        p.append("The stream:\n");
        p.append("- Name: ").append(quoted(in.streamName(), 100)).append('\n');
        if (notBlank(in.currentTitle())) {
            p.append("- The creator's current title: ").append(quoted(in.currentTitle(), 100)).append('\n');
        }
        if (notBlank(in.brief())) {
            p.append("- The creator's note: ").append(quoted(in.brief(), BRIEF_MAX)).append('\n');
        }
        if (in.phone()) {
            p.append("- The creator is really live, from their phone.\n");
        } else {
            p.append("- It plays videos that were recorded earlier")
                    .append(in.forever() ? ", around the clock, on repeat" : "")
                    .append(". Nobody is live on camera.\n");
        }
        List<String> names = in.tracks() == null ? List.of() : in.tracks().stream().map(t -> cleanFileName(t.title())).toList();
        if (!names.isEmpty()) {
            p.append("- ").append(in.music() ? "Music that plays" : "Videos that play").append(" (").append(names.size()).append("): ");
            p.append(String.join("; ", names.stream().limit(15).map(n -> quoted(n, 80)).toList()));
            if (names.size() > 15) {
                p.append("; and ").append(names.size() - 15).append(" more");
            }
            p.append('\n');
        }

        p.append("\nTitles: write 3 different titles.\n");
        p.append("1. Start with the words people would type into YouTube search to find exactly this, for example ")
                .append("\"lofi beats to study\", \"Hanuman Chalisa\" or \"rain sounds for sleep\".\n");
        p.append("2. Describe only what is really on the stream. No clickbait, no exaggeration, no promises, ")
                .append("no words in ALL CAPS, no emoji, no hashtags.\n");
        if (in.phone()) {
            p.append("3. The creator is really there, so saying it's live is fine, but no red dots or \"LIVE NOW\" in capitals.\n");
        } else {
            p.append("3. Never write \"LIVE\", \"live now\", \"streaming now\" or a red dot: the videos were recorded earlier. ")
                    .append(in.forever() ? "\"24/7\" and \"replay\" are fine." : "\"replay\" is fine.").append('\n');
        }
        p.append("4. Each title at most ").append(TITLE_MAX).append(" characters, ideally under 70.\n");

        p.append("\nDescription: 2 to 4 short sentences. The first says plainly what viewers will see and hear, using the ")
                .append("words people search for. Describe only what is really on the stream: no clickbait, no promises, ")
                .append("no lists of keywords, no links, no hashtags")
                .append(in.phone() ? "" : ", and don't call it live")
                .append(". Don't write a tracklist: it is added after your text.\n");

        p.append("\nTags: ").append(TAGS_MIN).append(" to ").append(TAGS_MAX)
                .append(" short phrases people would really search for that describe this stream. ")
                .append("No unrelated popular words such as viral, trending or a year")
                .append(in.phone() ? "" : ", and no \"live\"").append(".\n");
        return p.toString();
    }

    private static String languageLine(Language l) {
        return switch (l) {
            case EN -> "English.";
            case HI -> "Hindi (हिन्दी), in Devanagari script. Common English words people search for in Hindi, such as lofi, bhajan or 24/7, may stay.";
            default -> l.english + " (" + l.nativeName + "), in its own script. Common English words people search for, such as lofi or 24/7, may stay.";
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Quoted for the prompt: one line, no double quotes to break out with, at most {@code max} characters. */
    static String quoted(String s, int max) {
        String t = s == null ? "" : s.replace('"', '\'').replaceAll("\\s+", " ").strip();
        if (t.length() > max) {
            t = t.substring(0, max).strip();
        }
        return "\"" + t + "\"";
    }

    // ------------------------------------------------------------------ the model's answer

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The model's answer made safe to show, with the tracklist added; null when it can't be used. */
    public static Draft parse(String raw, Input in) {
        JsonNode root = readJson(raw);
        if (root == null) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : strings(root.get("titles"))) {
            String clean = cleanTitle(t, in.phone(), in.forever());
            if (clean != null && seen.add(clean.toLowerCase(Locale.ROOT)) && titles.size() < 3) {
                titles.add(clean);
            }
        }
        if (titles.isEmpty()) {
            return null;
        }
        // In Hindi, Tamil and the rest: when some titles are in the language's script, drop the ones that aren't
        // (a model sometimes slips back to English). When none are, keep them: a start beats nothing.
        if (titles.stream().anyMatch(t -> inScript(t, in.language()))) {
            titles.removeIf(t -> !inScript(t, in.language()));
        }
        JsonNode d = root.get("description");
        String intro = cleanIntro(d != null && d.isTextual() ? d.asText() : "", in.phone());
        List<String> tags = cleanTags(strings(root.get("tags")), in);
        return new Draft(titles, withTracklist(intro, in), tags);
    }

    /** Without a usable answer: the creator's own words, made honest, plus the tracklist. Never invented hooks. */
    public static Draft fallback(Input in) {
        String base = notBlank(in.currentTitle()) ? in.currentTitle() : in.streamName();
        List<String> titles = new ArrayList<>();
        String t = cleanTitle(base, in.phone(), in.forever());
        if (t != null) {
            titles.add(t);
            if (!in.phone() && in.forever() && !t.matches("(?is).*\\b24\\s*[/x]\\s*7\\b.*") && t.length() + 7 <= TITLE_MAX) {
                titles.add(t + " · 24/7");
            }
        }
        return new Draft(titles, withTracklist("", in), cleanTags(List.of(), in));
    }

    private static JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw.substring(start, end + 1));
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null) {
            return out;
        }
        if (n.isArray()) {
            n.forEach(x -> {
                if (x.isTextual()) {
                    out.add(x.asText());
                }
            });
        } else if (n.isTextual()) {
            out.addAll(Arrays.asList(n.asText().split("[,\\n]")));
        }
        return out;
    }

    /**
     * True when {@code text} is written in the language's script: English has no Indic letters; Hindi and
     * Marathi have Devanagari letters, Tamil Tamil letters and so on, and no letters of another Indic script.
     * Latin words people search for ("lofi", "24/7") may appear in any of them.
     */
    public static boolean inScript(String text, Language language) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Character.UnicodeScript want = script(language);
        boolean has = false;
        for (int cp : text.codePoints().toArray()) {
            if (!Character.isLetter(cp)) {
                continue;
            }
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (s == Character.UnicodeScript.LATIN || s == Character.UnicodeScript.COMMON) {
                continue;
            }
            if (s != want) {
                return false;
            }
            has = true;
        }
        return want == Character.UnicodeScript.LATIN || has;
    }

    static Character.UnicodeScript script(Language l) {
        return switch (l) {
            case EN -> Character.UnicodeScript.LATIN;
            case HI, MR -> Character.UnicodeScript.DEVANAGARI;
            case TA -> Character.UnicodeScript.TAMIL;
            case TE -> Character.UnicodeScript.TELUGU;
            case BN -> Character.UnicodeScript.BENGALI;
        };
    }

    /**
     * "Live" written in Indian scripts (Hindi/Marathi लाइव/लाईव्ह, Tamil லைவ், Telugu లైవ్, Bengali লাইভ), with
     * the "now" words that go with it. On a replay these mislead as much as an English "LIVE" does; GR-9's
     * English check doesn't read them, so the writer rewrites them itself.
     */
    static final Pattern NATIVE_LIVE = Pattern.compile(
            "(?<![\\p{L}\\p{M}])(?:लाइव्ह|लाईव्ह|लाइव|लाईव|லைவ்|లైవ్|লাইভ)(?![\\p{L}\\p{M}])");
    private static final Pattern NATIVE_NOW = Pattern.compile(
            "(?<![\\p{L}\\p{M}])(?:अभी|आता|இப்போது|ఇప్పుడు|এখন)(?![\\p{L}\\p{M}])");

    /**
     * {@code t} with native-script "live" words (and, when there was one, "now") taken out and the
     * punctuation they leave tidied; null when there were none.
     */
    static String withoutNativeLive(String t) {
        if (t == null || !NATIVE_LIVE.matcher(t).find()) {
            return null;
        }
        String s = NATIVE_NOW.matcher(NATIVE_LIVE.matcher(t).replaceAll(" ")).replaceAll(" ");
        s = s.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n");
        s = s.replaceAll(" +([,।.!])", "$1").replaceAll("([,।])\\1+", "$1").replaceAll(",([.।!])", "$1");
        return s.replaceAll("^[\\s,|:\\-–—•·]+", "").strip();
    }

    private static final Pattern HASHTAG = Pattern.compile("(?<![\\p{L}\\p{N}])#[\\p{L}\\p{M}\\p{N}_]+");
    /** Emoji and pictographs (not letters of any script). */
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");

    /**
     * One title made safe to show with the GR-9 rules: one line, no quotes or numbering, no hashtags or
     * emoji, no misleading "LIVE" on a replay (rewritten the way the one-tap fix does), within YouTube's
     * limit, and with a searchable phrase; null when it can't be used.
     */
    public static String cleanTitle(String raw, boolean phone, boolean forever) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        t = t.replaceAll("^(\\d{1,2}[.)]\\s+|[-•]\\s+)", "");
        t = t.replaceAll("^[\"'“”‘’*]+|[\"'“”‘’*]+$", "").strip();
        if (!phone) {
            String n = withoutNativeLive(t);
            if (n != null || GoLiveCheck.misleadingLive(t) != null) {
                t = GoLiveCheck.honestTitle(n != null ? n : t, forever);
            }
        }
        t = EMOJI.matcher(HASHTAG.matcher(t).replaceAll(" ")).replaceAll(" ").replaceAll("\\s+", " ").strip();
        t = t.replaceAll("[\\s|:\\-–—•·,]+$", "").strip();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > TITLE_MAX) {
            t = cutAtWord(t, TITLE_MAX);
        }
        return GoLiveCheck.hasSearchablePhrase(t) ? capitalize(t) : null;
    }

    /** The model's description: no "LIVE" on a replay, no hashtags or emoji, at most {@link #INTRO_MAX} characters. */
    static String cleanIntro(String raw, boolean phone) {
        String d = raw == null ? "" : raw.strip();
        if (!phone) {
            d = GoLiveCheck.withoutLiveWording(d);
            String n = withoutNativeLive(d);
            d = n == null ? d : n;
        }
        d = EMOJI.matcher(HASHTAG.matcher(d).replaceAll("")).replaceAll("");
        d = d.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (d.length() > INTRO_MAX) {
            String cut = d.substring(0, INTRO_MAX);
            int end = Math.max(cut.lastIndexOf(". "), Math.max(cut.lastIndexOf("।"), cut.lastIndexOf('\n')));
            d = (end > INTRO_MAX / 2 ? cut.substring(0, end + 1) : cutAtWord(d, INTRO_MAX)).strip();
        }
        return d;
    }

    /** Words that are never good tags: unrelated popular words (spam) and, on a replay, "live". */
    private static final Set<String> BANNED_TAGS = Set.of("viral", "trending", "trend", "fyp", "foryou", "shorts", "subscribe",
            "like", "views", "explore", "viral video", "trending video");
    private static final Set<String> LIVE_TAGS = Set.of("live", "live now", "live stream", "livestream", "streaming now", "live streaming");

    /** 5–10 tags: no hashtags, no spam words or years, no "live" on a replay, deduplicated, 500 characters in all. */
    static List<String> cleanTags(List<String> raw, Input in) {
        Map<String, String> tags = new LinkedHashMap<>();
        addTags(tags, raw, in);
        if (tags.size() < TAGS_MIN) {
            List<String> more = new ArrayList<>();
            for (String src : new String[] {in.currentTitle(), in.streamName(), in.brief()}) {
                if (notBlank(src)) {
                    String c = GoLiveCheck.withoutLiveWording(src);
                    more.addAll(Arrays.asList(AiService.fallbackTags(c).split(",")));
                }
            }
            addTags(tags, more, in);
        }
        List<String> out = new ArrayList<>();
        int total = 0;
        for (String t : tags.values()) {
            if (out.size() >= TAGS_MAX || total + t.length() + 1 > TAGS_MAX_TOTAL) {
                break;
            }
            out.add(t);
            total += t.length() + 1;
        }
        return out;
    }

    private static void addTags(Map<String, String> tags, List<String> raw, Input in) {
        for (String r : raw) {
            String t = r == null ? "" : r.replace("#", " ").replaceAll("[\"“”]", "").replaceAll("\\s+", " ").strip();
            t = EMOJI.matcher(t).replaceAll("").strip();
            String key = t.toLowerCase(Locale.ROOT);
            if (t.isEmpty() || t.length() > 40 || BANNED_TAGS.contains(key) || key.matches("(19|20)\\d\\d")
                    || (!in.phone() && (LIVE_TAGS.contains(key) || GoLiveCheck.misleadingLive(t) != null || withoutNativeLive(t) != null))
                    || !GoLiveCheck.hasSearchablePhrase(t) && !key.matches("24/?7")) {
                continue;
            }
            tags.putIfAbsent(key, t);
        }
    }

    private static String cutAtWord(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        String cut = s.substring(0, max);
        int space = cut.lastIndexOf(' ');
        return (space > max / 2 ? cut.substring(0, space) : cut).replaceAll("[\\s|:\\-–—•·,]+$", "").strip();
    }

    // ------------------------------------------------------------------ tracklist

    private static String withTracklist(String intro, Input in) {
        String list = tracklist(in.tracks(), in.shuffled(), in.language());
        String out = intro.isEmpty() ? list : list.isEmpty() ? intro : intro + "\n\n" + list;
        return out.length() > DESCRIPTION_MAX ? out.substring(0, DESCRIPTION_MAX) : out;
    }

    /**
     * The tracklist from the playlist: clean names with the time each one starts, counted from the start
     * of the playlist. In random order there are no timestamps (they'd be wrong), and after a track of
     * unknown length the rest are listed without them. Long playlists end with "+N".
     */
    public static String tracklist(List<Track> tracks, boolean shuffled, Language language) {
        if (tracks == null || tracks.isEmpty()) {
            return "";
        }
        double total = 0;
        boolean known = !shuffled;
        for (Track t : tracks) {
            if (t.seconds() == null || t.seconds() <= 0) {
                break;
            }
            total += t.seconds();
        }
        boolean hours = total >= 3600;
        StringBuilder out = new StringBuilder(language.tracklistHeading);
        double at = 0;
        int shown = 0;
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            String name = cleanFileName(t.title());
            if (name.isEmpty()) {
                name = "Track " + (i + 1);
            }
            String line = known ? timestamp(at, hours) + " " + name : "- " + name;
            if (shown >= TRACKLIST_MAX_LINES || out.length() + line.length() + 12 > TRACKLIST_MAX_CHARS) {
                out.append("\n+").append(tracks.size() - i);
                break;
            }
            out.append('\n').append(line);
            shown++;
            if (known) {
                if (t.seconds() == null || t.seconds() <= 0) {
                    known = false;
                } else {
                    at += t.seconds();
                }
            }
        }
        return out.toString();
    }

    /** 0:00-style for lists under an hour, 0:00:00 once any start is an hour or more in (YouTube reads both). */
    static String timestamp(double seconds, boolean hours) {
        long s = (long) Math.floor(seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return hours ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    private static final Pattern EXTENSION = Pattern.compile("\\.(mp4|mov|mkv|webm|avi|m4v|ts|flv|mp3|m4a|aac|wav|flac|ogg|opus)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JUNK = Pattern.compile(
            "(?i)[(\\[](\\d{1,3}|copy|final|official( music)? video|official audio|lyrics?|hd|hq|4k|1080p|720p|480p)[)\\]]"
                    + "|(?<![\\p{L}\\p{N}])(1080p|720p|480p|2160p|4k|x264|x265|h264|hevc|60fps|30fps)(?![\\p{L}\\p{N}])");

    /** A library title as a person would write it: no extension, track number, underscores or "(1)" copies. */
    public static String cleanFileName(String title) {
        if (title == null) {
            return "";
        }
        String t = EXTENSION.matcher(title.strip()).replaceAll("");
        t = JUNK.matcher(t).replaceAll(" ");
        t = t.replaceAll("^\\d{1,3}_+", "").replace('_', ' ');
        if (!t.contains(" ")) {
            t = t.replaceAll("(?<=[\\p{L}\\p{N}])[-.](?=[\\p{L}\\p{N}])", " ");
        }
        t = t.replaceAll("^\\s*\\d{1,3}\\s*[.\\-)]\\s+", "");
        t = t.replaceAll("\\s+", " ").replaceAll("^[\\s\\-–—.]+|[\\s\\-–—.]+$", "").strip();
        if (t.isEmpty()) {
            return "";
        }
        return capitalize(t);
    }

    private static String capitalize(String t) {
        int first = t.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + t.substring(Character.charCount(first));
    }
}
