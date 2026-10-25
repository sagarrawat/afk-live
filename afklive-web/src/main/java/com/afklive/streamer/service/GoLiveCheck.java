package com.afklive.streamer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GR-9: the go-live check's rules, kept free of storage and Spring so every rule is tested on its own.
 * It flags only what AFK Live can see for certain (the files, overlays, title and thumbnail the
 * creator set up here, and replies AFK Live itself posted). It never predicts Content ID claims or
 * ranking; music gets a general note instead.
 */
public final class GoLiveCheck {

    private GoLiveCheck() {
    }

    /** Flag ids (stable: dismissals are stored by id). */
    public static final String ONE_FILE_LOOP = "one-file-loop";
    public static final String STILL_PICTURE = "still-picture";
    public static final String REPEATED_MESSAGE = "repeated-message";
    public static final String TITLE_NO_PHRASE = "title-no-phrase";
    public static final String TITLE_LIVE = "title-live";
    public static final String THUMB_SMALL = "thumbnail-small";
    public static final String THUMB_SHAPE = "thumbnail-shape";
    public static final String THUMB_FLAT = "thumbnail-flat";

    public static final Set<String> IDS = Set.of(ONE_FILE_LOOP, STILL_PICTURE, REPEATED_MESSAGE, TITLE_NO_PHRASE, TITLE_LIVE,
            THUMB_SMALL, THUMB_SHAPE, THUMB_FLAT);

    /** Help articles (afklive-ui/site/help.py). */
    public static final String HELP_LOOPS = "/help/loops-and-youtube-rules";
    public static final String HELP_TITLES = "/help/titles-and-thumbnails";
    public static final String HELP_MUSIC = "/help/music-on-your-live";

    /**
     * What the one-tap fix does; the app maps each to a button or a link. NONE: nothing left to change in
     * AFK Live, the flag only explains (the app shows "Got it" instead of a fix).
     */
    public enum Fix { ADD_OVERLAYS, USE_TITLE, OPEN_PLAYLIST, EDIT_TITLE, EDIT_THUMBNAIL, NONE }

    /** POLICY: the inauthentic-content or spam patterns YouTube names. FOUND: being found in search and on phones. */
    public enum Kind { POLICY, FOUND }

    public record Flag(String id, Kind kind, String title, String detail, Fix fix, String fixLabel, String suggestion, String help) {
    }

    /** YouTube's minimum thumbnail width (it recommends 1280x720). */
    static final int THUMB_MIN_WIDTH = 640;
    /** RMS contrast (0..1) of the picture shrunk to phone size below which almost nothing stands out. */
    static final double THUMB_FLAT_CONTRAST = 0.05;

    /**
     * Everything the check reads about one stream.
     *
     * @param phone            the stream comes from the creator's phone (really live)
     * @param forever          a playlist that repeats without end (loop count 0)
     * @param passes           how many passes a finite playlist makes (0 when forever)
     * @param distinctVideos   different ready videos in the playlist
     * @param stillVideos      of those, how many were measured as one still picture
     * @param uncheckedVideos  of those, how many haven't been measured yet
     * @param distinctTracks   different ready tracks in the music playlist
     * @param liveOverlays     visible overlays on the Live scene
     * @param youtubeTitle     the title the creator typed in, or null
     * @param thumbnail        the thumbnail's measurements, or null when none is set (or it couldn't be read)
     * @param repeatedReply    a reply AFK Live posted three or more times this week, or null
     */
    public record Input(boolean phone, boolean forever, int passes, int distinctVideos, int stillVideos, int uncheckedVideos,
                        int distinctTracks, int liveOverlays, String youtubeTitle, Thumbnail thumbnail, RepeatedReply repeatedReply) {
    }

    public record Thumbnail(int width, int height, double contrast) {
    }

    public record RepeatedReply(String text, long times) {
    }

    public static List<Flag> run(Input in) {
        List<Flag> flags = new ArrayList<>();
        boolean loops = in.forever() || in.passes() >= 2;
        if (!in.phone() && loops && in.distinctVideos() == 1 && in.distinctTracks() <= 1) {
            flags.add(new Flag(ONE_FILE_LOOP, Kind.POLICY, "One video plays over and over",
                    (in.distinctTracks() == 1 ? "Your stream repeats one video and one song. " : "Your stream repeats one video. ")
                            + "YouTube's rules for earning exclude repetitive content, so a live that only repeats one thing "
                            + "can lose its ads. Add more videos or music.",
                    Fix.OPEN_PLAYLIST, "Add videos", null, HELP_LOOPS));
        }
        if (!in.phone() && in.distinctVideos() > 0 && in.uncheckedVideos() == 0 && in.stillVideos() == in.distinctVideos()
                && in.liveOverlays() == 0) {
            flags.add(new Flag(STILL_PICTURE, Kind.POLICY, "Viewers see one still picture",
                    (in.distinctVideos() == 1 ? "Your video is" : "Your videos are") + " one picture that doesn't change, and "
                            + "nothing on screen changes. YouTube's rules for earning name still images with little else as "
                            + "low-effort content. A clock and the song that's playing give viewers something that changes.",
                    Fix.ADD_OVERLAYS, "Add a clock and now playing", null, HELP_LOOPS));
        }
        if (in.repeatedReply() != null && in.repeatedReply().times() >= 3) {
            // History only: AFK Live no longer posts replies by itself (AI-3), so there is nothing to switch off.
            flags.add(new Flag(REPEATED_MESSAGE, Kind.POLICY, "The same reply was posted " + in.repeatedReply().times() + " times",
                    "AFK Live's old automatic replies posted “" + shorten(in.repeatedReply().text(), 80) + "” "
                            + in.repeatedReply().times() + " times this week. YouTube counts repeated identical messages as "
                            + "spam. Automatic replies are gone for good, so this won't happen again. You can delete those "
                            + "replies in YouTube Studio.",
                    Fix.NONE, "Got it", null, HELP_LOOPS));
        }
        String title = in.youtubeTitle() == null ? "" : in.youtubeTitle().strip();
        if (!title.isEmpty()) {
            if (!hasSearchablePhrase(title)) {
                flags.add(new Flag(TITLE_NO_PHRASE, Kind.FOUND, "Your title has nothing people search for",
                        "“" + shorten(title, 60) + "” has no words that say what's on the stream. Start with what "
                                + "people type into search, like “lofi beats to study” or “Hanuman Chalisa”.",
                        Fix.EDIT_TITLE, "Edit the title", null, HELP_TITLES));
            }
            String live = misleadingLive(title);
            if (!in.phone() && live != null) {
                String better = honestTitle(title, in.forever());
                flags.add(new Flag(TITLE_LIVE, Kind.FOUND, "Your title says “" + live + "” over a replay",
                        "This stream plays videos you uploaded, so “" + live + "” can read as misleading, and YouTube's "
                                + "spam rules cover misleading titles. If you're really there live, keep it.",
                        Fix.USE_TITLE, "Use “" + shorten(better, 50) + "”", better, HELP_TITLES));
            }
        }
        Thumbnail t = in.thumbnail();
        if (t != null && t.width() > 0 && t.height() > 0) {
            if (t.width() < THUMB_MIN_WIDTH) {
                flags.add(new Flag(THUMB_SMALL, Kind.FOUND, "Your thumbnail is too small",
                        "It's " + t.width() + "x" + t.height() + ". YouTube needs at least 640 pixels wide and recommends "
                                + "1280x720, or it looks blurry.",
                        Fix.EDIT_THUMBNAIL, "Choose another picture", null, HELP_TITLES));
            }
            double ratio = t.width() / (double) t.height();
            if (Math.abs(ratio - 16.0 / 9) > 16.0 / 9 * 0.05) {
                flags.add(new Flag(THUMB_SHAPE, Kind.FOUND, "Your thumbnail isn't 16:9",
                        "It's " + t.width() + "x" + t.height() + ". YouTube shows thumbnails at 16:9, so the edges get cut off "
                                + "or bars are added. 1280x720 fits exactly.",
                        Fix.EDIT_THUMBNAIL, "Choose another picture", null, HELP_TITLES));
            }
            if (t.contrast() >= 0 && t.contrast() < THUMB_FLAT_CONTRAST) {
                flags.add(new Flag(THUMB_FLAT, Kind.FOUND, "Your thumbnail is hard to see on a phone",
                        "Shrunk to phone size, it's almost one flat colour, so text or faces on it won't stand out. "
                                + "Use bigger text and stronger contrast between it and the background.",
                        Fix.EDIT_THUMBNAIL, "Choose another picture", null, HELP_TITLES));
            }
        }
        return flags;
    }

    /** The general note on music: never a verdict on any track. */
    public static final String MUSIC_NOTE = "We can't tell whether YouTube will claim a song, and no tool can promise that. "
            + "Music you don't own or license can get your live muted, blocked or ended, even a 24/7 one. Use your own "
            + "music, music you've licensed, or a royalty-free library such as YouTube's Audio Library.";

    // ------------------------------------------------------------------ titles

    /** Words that say nothing about what's on the stream. */
    private static final Set<String> GENERIC = Set.of(
            "live", "lives", "stream", "streams", "streaming", "streamed", "broadcast", "now", "new", "test", "testing", "untitled",
            "video", "videos", "channel", "official", "full", "part", "episode", "day", "today", "tonight", "the", "and", "for",
            "with", "from", "this", "that", "you", "your", "our", "my", "nonstop", "non", "stop", "replay", "watch", "here",
            "come", "join", "please", "subscribe", "like", "share", "hd", "uhd", "4k", "1080p", "720p", "24x7", "247", "hours",
            "hour", "always", "online", "on", "air", "go", "going", "right", "happening");

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");

    /** True when the title has at least one word of three or more characters that says what's on (any script). */
    public static boolean hasSearchablePhrase(String title) {
        Matcher m = WORD.matcher(title == null ? "" : title);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (w.codePointCount(0, w.length()) >= 3 && w.codePoints().anyMatch(Character::isLetter) && !GENERIC.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern LIVE_NOW = Pattern.compile("(?i)\\b(live now|streaming now|happening now|live right now)\\b");
    private static final Pattern LIVE_CAPS = Pattern.compile("(?<![\\p{L}])LIVE(?![\\p{L}])");
    private static final String RED_DOT = "🔴";

    /** The words in the title that present a replay as happening now, or null. */
    public static String misleadingLive(String title) {
        if (title == null) {
            return null;
        }
        Matcher now = LIVE_NOW.matcher(title);
        if (now.find()) {
            return now.group(1);
        }
        if (title.contains(RED_DOT)) {
            Matcher caps = LIVE_CAPS.matcher(title);
            return caps.find() ? RED_DOT + " LIVE" : RED_DOT;
        }
        return LIVE_CAPS.matcher(title).find() ? "LIVE" : null;
    }

    /**
     * The text with the words that present a replay as happening now ("LIVE", "live now", a red dot) taken
     * out and spaces tidied; line breaks are kept, so it works on descriptions too.
     */
    public static String withoutLiveWording(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace(RED_DOT, " ");
        t = LIVE_NOW.matcher(t).replaceAll(" ");
        t = LIVE_CAPS.matcher(t).replaceAll(" ");
        return t.replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll(" *\\n *", "\n").strip();
    }

    /** The title without the "live now" wording, saying it's a replay instead. At most 100 characters (YouTube's limit). */
    public static String honestTitle(String title, boolean forever) {
        String t = withoutLiveWording(title);
        t = t.replaceAll("^[\\s|:\\-–—•·,!]+", "").replaceAll("[\\s|:\\-–—•·,]+$", "").strip();
        boolean says247 = t.matches("(?is).*\\b24\\s*[/x]\\s*7\\b.*");
        String tail = forever && !says247 ? "24/7 replay" : "replay";
        String out = t.isEmpty() ? (forever ? "24/7 replay" : "Replay") : t + " · " + tail;
        if (out.length() > 100) {
            out = t.substring(0, Math.max(0, 100 - tail.length() - 3)).strip() + " · " + tail;
        }
        return out;
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }
}
