package com.afklive.streamer.service;

import com.afklive.streamer.streaming.StudioSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GR-8: starter templates per niche. Each one is a set of overlay layers (now playing, clock, next
 * item, scene titles), scene colours, playback settings that keep a 24/7 stream varied (growth rule 4:
 * shuffle and a now-playing layer by default) and optional schedule presets. Applying one only uses
 * what a creator could set by hand: overlays and scenes through LiveStreamService, schedules through
 * StreamScheduleService. Labels come in English or Hindi; the overlay renderer draws every major Indian
 * script, so creators can retype them in their own language.
 */
public final class NicheTemplates {

    private NicheTemplates() {
    }

    /** Overlays a template adds carry this id prefix, so applying another template replaces them and keeps the creator's own. */
    public static final String PREFIX = "tpl-";

    public enum Source { PLAYLIST, PHONE, ANY }

    /** A daily slot in the stream's time zone ("HH:mm"); the creator edits it after. */
    public record Slot(String start, String end) {
    }

    public record SchedulePreset(String id, String name, String description, List<Slot> slots) {
    }

    /**
     * @param shuffle    shuffle the videos (false keeps the creator's order, e.g. an aarti sequence)
     * @param crossfade  seconds the music overlaps from one track into the next
     * @param palette    colour for the Starting soon, Be right back and Ending scenes (when they have no background)
     */
    public record Template(String id, String name, String description, Source source, boolean shuffle, int crossfade,
                           String palette, List<SchedulePreset> schedules) {
    }

    /** The labels a template draws, per language. */
    record Labels(String nowPlaying, String upNext, String startingSoon, String brb, String thanks) {
    }

    static final Map<String, Labels> LABELS = Map.of(
            "en", new Labels("Now playing", "Up next", "Starting soon", "Be right back", "Thanks for watching"),
            "hi", new Labels("अभी चल रहा है", "अगला", "जल्द शुरू हो रहा है", "थोड़ी देर में लौटते हैं", "देखने के लिए धन्यवाद"));

    public static final List<String> LANGUAGES = List.of("en", "hi");

    static final SchedulePreset AARTI = new SchedulePreset("aarti", "Morning and evening aarti",
            "Live every day for the morning and evening aarti.", List.of(new Slot("05:30", "07:00"), new Slot("18:30", "20:00")));
    static final SchedulePreset BHAJANS = new SchedulePreset("bhajans", "All-day bhajans with a midday pause",
            "Live from early morning, a break in the afternoon, then the evening.", List.of(new Slot("05:00", "13:00"), new Slot("16:00", "22:00")));
    static final SchedulePreset STUDY_HOURS = new SchedulePreset("study-hours", "Study hours",
            "Live every day from 6 am to 11 pm, when people study.", List.of(new Slot("06:00", "23:00")));
    static final SchedulePreset NIGHTS = new SchedulePreset("nights", "Every night",
            "Live from 9 pm to 7 am, while people sleep.", List.of(new Slot("21:00", "07:00")));
    static final SchedulePreset EVENING_SHOW = new SchedulePreset("evening-show", "Evening session",
            "Live every day from 8 pm to 10 pm.", List.of(new Slot("20:00", "22:00")));

    public static final List<Template> ALL = List.of(
            new Template("lofi", "Lofi and chill", "Now playing in the corner, a small clock, songs shuffled and blended.",
                    Source.PLAYLIST, true, 4, "#1E1530", List.of()),
            new Template("devotional", "Bhajans and aarti", "Now playing and what's next in Hindi or English, the clock in your time zone, your order kept.",
                    Source.PLAYLIST, false, 2, "#3A1C05", List.of(AARTI, BHAJANS)),
            new Template("study", "Study with me", "A big clock to study by, now playing, and calm music shuffled.",
                    Source.PLAYLIST, true, 3, "#102A2A", List.of(STUDY_HOURS)),
            new Template("sleep", "Sleep and ambience", "A dim clock and soft titles that don't light up a dark room; long blends.",
                    Source.PLAYLIST, true, 8, "#0A1020", List.of(NIGHTS)),
            new Template("phone-gaming", "Phone gaming", "Starting soon, be right back and thanks-for-watching screens, and a clock while you play.",
                    Source.PHONE, false, 0, "#141A2E", List.of(EVENING_SHOW)));

    public static Optional<Template> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private static StudioSpec.OverlaySetting text(String id, String text, double x, double y, double size, double opacity, String scene) {
        return new StudioSpec.OverlaySetting(PREFIX + id, "TEXT", true, x, y, 0, size, text, null, null, "#FFFFFF", "#99000000",
                opacity, null, null, 0, scene);
    }

    private static StudioSpec.OverlaySetting clock(double x, double y, double size, double opacity, String timeZone) {
        return new StudioSpec.OverlaySetting(PREFIX + "clock", "CLOCK", true, x, y, 0, size, null, null, null, "#FFFFFF", "#99000000",
                opacity, timeZone, null, 0, null);
    }

    /** The template's layers for this stream: {@code hasMusic} picks the track or the video as "now playing". */
    public static List<StudioSpec.OverlaySetting> overlays(Template t, String language, String timeZone, boolean hasMusic) {
        Labels l = LABELS.getOrDefault(language, LABELS.get("en"));
        String playing = l.nowPlaying() + ": " + (hasMusic ? "{track}" : "{title}");
        List<StudioSpec.OverlaySetting> list = new ArrayList<>();
        switch (t.id()) {
            case "lofi" -> {
                list.add(text("now-playing", playing, 0.05, 0.88, 0.04, 1, StudioSpec.LIVE));
                list.add(clock(0.85, 0.05, 0.04, 0.9, timeZone));
            }
            case "devotional" -> {
                list.add(text("now-playing", l.nowPlaying() + ": {title}", 0.05, 0.84, 0.045, 1, StudioSpec.LIVE));
                list.add(text("up-next", l.upNext() + ": {next}", 0.05, 0.91, 0.035, 0.9, StudioSpec.LIVE));
                list.add(clock(0.85, 0.05, 0.045, 1, timeZone));
            }
            case "study" -> {
                list.add(clock(0.42, 0.06, 0.09, 1, timeZone));
                list.add(text("now-playing", playing, 0.05, 0.9, 0.035, 0.9, StudioSpec.LIVE));
            }
            case "sleep" -> {
                list.add(clock(0.86, 0.06, 0.035, 0.45, timeZone));
                list.add(text("now-playing", hasMusic ? "{track}" : "{title}", 0.05, 0.92, 0.03, 0.45, StudioSpec.LIVE));
            }
            case "phone-gaming" -> {
                list.add(clock(0.85, 0.05, 0.035, 0.8, timeZone));
            }
            default -> throw new IllegalArgumentException("Unknown template.");
        }
        // Every template gives the other scenes words, so viewers never see a blank colour.
        list.add(text("starting", l.startingSoon(), 0.3, 0.44, 0.07, 1, StudioSpec.STARTING));
        list.add(text("brb", l.brb(), 0.3, 0.44, 0.07, 1, StudioSpec.BRB));
        list.add(text("ending", l.thanks(), 0.25, 0.44, 0.07, 1, StudioSpec.ENDING));
        return list;
    }
}
