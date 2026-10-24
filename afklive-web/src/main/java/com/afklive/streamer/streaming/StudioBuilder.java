package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.streaming.studio.DecoderSource;
import com.afklive.streamer.streaming.studio.Feed;
import com.afklive.streamer.streaming.studio.LiveFeed;
import com.afklive.streamer.streaming.studio.Overlay;
import com.afklive.streamer.streaming.studio.PlaylistFeed;
import com.afklive.streamer.streaming.studio.StillFeed;
import com.afklive.streamer.streaming.studio.Studio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Turns a {@link StudioSpec} into a running-ready {@link Studio} on this worker: playlists whose
 * files come from the {@link MediaCache} (fetched ahead of time), scene backgrounds, music, the
 * phone input with its BRB fallback, and overlays. Runs on the worker's prepare thread (it may
 * download scene images); the playlists themselves never block the clock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StudioBuilder {

    private final MediaCache cache;
    private final IngestReader ingestReader;
    private final com.afklive.streamer.service.MediaProbe probe;
    /** Smaller copies of library videos, prepared once at this stream's size and frame rate. */
    private final com.afklive.streamer.service.PlayoutCopies playoutCopies;
    /** The live encoder's x264 preset and threads (app.streaming.x264-*). */
    private final com.afklive.streamer.service.EncoderSettings encoderSettings;
    /** Whether each file has an audio track, when the library knows (from upload) or a probe found out. */
    /**
     * A built studio plus what's needed to change it while live: the factory and canvas for new
     * feeds, and the media lists that drive prefetching (updated by live playlist edits).
     */
    public final class Built {
        final Studio studio;
        final PlaylistFeed playlist;
        volatile PlaylistFeed music;
        final Canvas canvas;
        final String ffmpeg;
        final Random random = new Random();
        final PlaylistFeed.SourceFactory factory;
        /** The lists prefetching follows; live playlist edits replace them. */
        java.util.concurrent.atomic.AtomicReference<List<StudioSpec.Media>> videoRefs;
        java.util.concurrent.atomic.AtomicReference<List<StudioSpec.Media>> musicRefs;
        final Map<String, StudioSpec.SceneSetting> sceneSettings = new java.util.concurrent.ConcurrentHashMap<>();
        /** PL-6: the stream's fit for items without their own; a live change applies from the next item. */
        java.util.concurrent.atomic.AtomicReference<DecoderSource.Fit> streamFit;
        volatile double crossfadeSeconds;

        private Built(Studio studio, PlaylistFeed playlist, PlaylistFeed music, Canvas canvas, String ffmpeg,
                      PlaylistFeed.SourceFactory factory) {
            this.studio = studio;
            this.playlist = playlist;
            this.music = music;
            this.canvas = canvas;
            this.ffmpeg = ffmpeg;
            this.factory = factory;
        }

        public Studio studio() {
            return studio;
        }

        public PlaylistFeed playlist() {
            return playlist;
        }

        public PlaylistFeed music() {
            return music;
        }

        /** A music bed for a live change that adds music to a run that started without it. */
        PlaylistFeed newMusic(List<StudioSpec.Media> media, String musicOrder) {
            musicRefs.set(List.copyOf(media));
            if (!media.isEmpty()) {
                cache.prefetch(media.getFirst().key());
            }
            PlaylistFeed feed = new PlaylistFeed(canvas, items(media), order(musicOrder), 0, null, prefetching(canvas, factory, musicRefs), true, random);
            feed.setCrossfadeSeconds(crossfadeSeconds);
            return feed;
        }

        Feed background(StudioSpec.SceneSetting setting) throws IOException {
            return StudioBuilder.this.background(canvas, setting, factory, ffmpeg, random);
        }
    }

    public Built build(long jobId, StudioSpec spec, Path workDir, StudioSpec.Position resume, String initialScene,
                       Studio.Listener listener) throws IOException {
        Canvas canvas = Canvas.of(spec.height(), spec.vertical(), spec.fps());
        String ffmpeg = FFmpegCommandBuilder.ffmpegBinary();
        Map<String, Boolean> audioKnown = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, Double> durationKnown = new java.util.concurrent.ConcurrentHashMap<>();
        Random random = new Random();

        java.util.concurrent.atomic.AtomicReference<DecoderSource.Fit> streamFit = new java.util.concurrent.atomic.AtomicReference<>(fitOf(spec.fit()));
        PlaylistFeed.SourceFactory factory = (item, start, audioOnly) -> {
            String key = keyOf(item);
            cache.pin(key);
            DecoderSource source = new DecoderSource(canvas,
                    (audioOnly ? DecoderSource.Options.audioFile(item.path(), start)
                            : DecoderSource.Options.file(item.path(), item.hasAudio(), start)
                                    .withFit(item.fit() != null ? item.fit() : streamFit.get())).withGain(item.gainDb()),
                    workDir, ffmpeg, "job" + jobId + "-" + item.id());
            java.util.concurrent.atomic.AtomicReference<String> copyPinned = new java.util.concurrent.atomic.AtomicReference<>();
            return source.resolveInputWith(() -> {
                        // Videos play from a playout copy at this canvas when one is ready (asked for otherwise).
                        String play = audioOnly ? key : playoutCopies.keyFor(key, canvas.width(), canvas.height(), canvas.fps());
                        Path file;
                        if (play.equals(key)) {
                            file = cache.fetch(key);
                        } else {
                            String before = copyPinned.getAndSet(play);
                            if (before == null) {
                                cache.pin(play);
                            }
                            file = fetchCopyOrOriginal(play, key);
                        }
                        Boolean hasAudio = audioOnly ? Boolean.TRUE : audioKnown.get(key);
                        Double duration = item.durationSeconds() > 0 ? null : durationKnown.get(key);
                        if (hasAudio == null || (item.durationSeconds() <= 0 && duration == null)) {
                            // Older library files weren't probed at upload: check now, once per file per run.
                            com.afklive.streamer.service.MediaProbe.MediaInfo info = probe.probe(file);
                            if (info.hasAudio() != null) {
                                audioKnown.put(key, info.hasAudio());
                                if (hasAudio == null) {
                                    hasAudio = info.hasAudio();
                                }
                            }
                            if (info.durationSeconds() > 0) {
                                durationKnown.put(key, info.durationSeconds());
                                duration = info.durationSeconds();
                            }
                        }
                        return new DecoderSource.Resolved(List.of("-i", file.toString()), hasAudio, duration);
                    })
                    .whenClosed(() -> {
                        cache.release(key);
                        String copy = copyPinned.get();
                        if (copy != null) {
                            cache.release(copy);
                        }
                    });
        };

        for (List<StudioSpec.Media> list : java.util.Arrays.asList(spec.videos(), spec.music())) {
            if (list != null) {
                list.stream().filter(m -> m.hasAudio() != null).forEach(m -> audioKnown.put(m.key(), m.hasAudio()));
            }
        }
        java.util.concurrent.atomic.AtomicReference<List<StudioSpec.Media>> videoRefs =
                new java.util.concurrent.atomic.AtomicReference<>(spec.videos() == null ? List.of() : List.copyOf(spec.videos()));
        java.util.concurrent.atomic.AtomicReference<List<StudioSpec.Media>> musicRefs =
                new java.util.concurrent.atomic.AtomicReference<>(spec.music() == null ? List.of() : List.copyOf(spec.music()));

        // Video playlist (PLAYLIST source). Fetch the first items now so the start is quick.
        PlaylistFeed playlist = null;
        if ("PLAYLIST".equals(spec.source())) {
            for (int i = 0; i < Math.min(2, spec.videos().size()); i++) {
                cache.prefetch(spec.videos().get(i).key());
            }
            playlist = new PlaylistFeed(canvas, items(spec.videos()), order(spec.order()), spec.loops(), position(resume),
                    prefetching(canvas, factory, videoRefs), false, random);
            if (spec.finishAfterPass()) {
                playlist.endAfterThisPass(); // asked for before a restart or takeover: still applies
            }
        }

        // Music bed: plays under any scene that asks for it; KEEP means the videos' own sound only.
        PlaylistFeed music = null;
        boolean musicOn = !"KEEP".equals(spec.audioMode()) && spec.music() != null && !spec.music().isEmpty();
        if (musicOn) {
            cache.prefetch(spec.music().getFirst().key());
            music = new PlaylistFeed(canvas, items(spec.music()), order(spec.musicOrder()), 0, null,
                    prefetching(canvas, factory, musicRefs), true, random);
        }
        double videoVolume = "MUTE".equals(spec.audioMode()) ? 0 : clampVolume(spec.videoVolume());
        double musicVolume = clampVolume(spec.musicVolume());

        // Scenes. LIVE is the playlist or the phone; the others are a background with optional music.
        Map<String, StudioSpec.SceneSetting> settings = new LinkedHashMap<>();
        for (StudioSpec.SceneSetting s : spec.scenes() == null ? List.<StudioSpec.SceneSetting>of() : spec.scenes()) {
            settings.put(s.name(), s);
        }
        settings.putIfAbsent(StudioSpec.BRB, new StudioSpec.SceneSetting(StudioSpec.BRB, null, null, "COLOR", "#101820", true));

        Map<String, List<Overlay>> sceneOverlays = new LinkedHashMap<>();
        List<Overlay> global = new ArrayList<>();
        splitOverlays(spec, global, sceneOverlays);

        List<Studio.Scene> scenes = new ArrayList<>();
        Map<String, Feed> backgrounds = new LinkedHashMap<>();
        for (StudioSpec.SceneSetting s : settings.values()) {
            if (StudioSpec.LIVE.equals(s.name())) {
                continue;
            }
            Feed bg = background(canvas, s, factory, ffmpeg, random);
            backgrounds.put(s.name(), bg);
            scenes.add(new Studio.Scene(s.name(), bg, s.music() ? music : null, 0, musicVolume,
                    sceneOverlays.getOrDefault(s.name(), List.of()), null));
        }

        StudioSpec.SceneSetting liveSetting = settings.get(StudioSpec.LIVE);
        boolean liveMusic = liveSetting == null || liveSetting.music();
        Feed liveVideo;
        if ("PHONE".equals(spec.source())) {
            String readUrl = spec.phone().ingestUrl() != null ? spec.phone().ingestUrl() : ingestReader.readUrl(spec.phone().ingestKey());
            DecoderSource input = new DecoderSource(canvas, DecoderSource.Options.live(readUrl).withFit(fitOf(spec.fit())),
                    workDir, ffmpeg, "job" + jobId + "-phone").start();
            liveVideo = new LiveFeed(canvas, input, backgrounds.get(StudioSpec.BRB), LiveFeed.Settings.defaults(), null);
        } else {
            liveVideo = playlist;
        }
        scenes.add(new Studio.Scene(StudioSpec.LIVE, liveVideo, liveMusic ? music : null, videoVolume, musicVolume,
                sceneOverlays.getOrDefault(StudioSpec.LIVE, List.of()), StudioSpec.BRB));

        String wanted = initialScene != null ? initialScene : spec.initialScene();
        String first = wanted != null && scenes.stream().anyMatch(sc -> sc.name().equals(wanted)) ? wanted : StudioSpec.LIVE;
        long grace = spec.phone() != null ? spec.phone().graceSeconds() * 1000L : 0;
        Studio studio = new Studio(canvas, workDir, ffmpeg, encoderSettings, scenes, first, global, grace, listener);
        Built built = new Built(studio, playlist, music, canvas, ffmpeg, factory);
        built.streamFit = streamFit;
        built.crossfadeSeconds = spec.crossfadeSeconds();
        if (music != null) {
            music.setCrossfadeSeconds(spec.crossfadeSeconds());
        }
        built.videoRefs = videoRefs;
        built.musicRefs = musicRefs;
        built.sceneSettings.putAll(settings);
        return built;
    }

    /** Overlays for live updates (RELOAD): global ones, and per scene. */
    public void splitOverlays(StudioSpec spec, List<Overlay> global, Map<String, List<Overlay>> perScene) throws IOException {
        if (spec.overlays() == null) {
            return;
        }
        for (StudioSpec.OverlaySetting o : spec.overlays()) {
            Overlay overlay;
            try {
                overlay = toOverlay(o);
            } catch (IOException e) {
                log.warn("Overlay {} skipped: its image couldn't be fetched ({})", o.id(), e.getMessage());
                continue;
            }
            if (overlay == null) {
                continue;
            }
            if (o.scene() == null || o.scene().isBlank()) {
                global.add(overlay);
            } else {
                perScene.computeIfAbsent(o.scene(), k -> new ArrayList<>()).add(overlay);
            }
        }
    }

    public static List<PlaylistFeed.Item> items(List<StudioSpec.Media> media) {
        List<PlaylistFeed.Item> items = new ArrayList<>();
        if (media != null) {
            for (StudioSpec.Media m : media) {
                // The path is a placeholder; the real file is resolved from the cache when the decoder starts.
                items.add(new PlaylistFeed.Item(m.id() + "|" + m.key(), m.title(), Path.of(m.id()),
                        m.hasAudio() == null || m.hasAudio(), m.duration(), DecoderSource.Fit.parse(m.fit()), m.gainDb()));
            }
        }
        return items;
    }

    /** The stream's fit: FIT unless set (older specs have none). */
    static DecoderSource.Fit fitOf(String value) {
        DecoderSource.Fit f = DecoderSource.Fit.parse(value);
        return f == null ? DecoderSource.Fit.FIT : f;
    }

    /** Item ids carry the storage key ("mediaId|key") so the factory can fetch the file. */
    static String keyOf(PlaylistFeed.Item item) {
        int bar = item.id().indexOf('|');
        return bar < 0 ? item.id() : item.id().substring(bar + 1);
    }

    /** The media id part of an item id, for now-playing and resume. */
    public static String mediaIdOf(String itemId) {
        if (itemId == null) {
            return null;
        }
        int bar = itemId.indexOf('|');
        return bar < 0 ? itemId : itemId.substring(0, bar);
    }

    public static PlaylistFeed.Order order(String order) {
        return "SHUFFLE".equals(order) ? PlaylistFeed.Order.SHUFFLE : PlaylistFeed.Order.IN_ORDER;
    }

    /** A ready playout copy from the cache; the original if the copy can't be fetched (deleted meanwhile). */
    private Path fetchCopyOrOriginal(String copy, String original) throws IOException {
        try {
            return cache.fetch(copy);
        } catch (IOException | RuntimeException e) {
            log.warn("Playout copy {} unavailable, playing the original: {}", copy, e.getMessage());
            return cache.fetch(original);
        }
    }

    /** When an item starts, fetch the two after it so they're on disk before they're needed. */
    private PlaylistFeed.SourceFactory prefetching(Canvas canvas, PlaylistFeed.SourceFactory inner,
                                                   java.util.concurrent.atomic.AtomicReference<List<StudioSpec.Media>> refs) {
        java.util.function.UnaryOperator<String> playoutKey =
                k -> playoutCopies.knownKeyFor(k, canvas.width(), canvas.height(), canvas.fps());
        return (item, start, audioOnly) -> {
            String key = keyOf(item);
            List<StudioSpec.Media> media = refs.get();
            for (int i = 0; i < media.size(); i++) {
                if (media.get(i).key().equals(key)) {
                    cache.prefetch(playoutKey.apply(media.get((i + 1) % media.size()).key()));
                    cache.prefetch(playoutKey.apply(media.get((i + 2) % media.size()).key()));
                    break;
                }
            }
            return inner.open(item, start, audioOnly);
        };
    }

    private PlaylistFeed.Position position(StudioSpec.Position p) {
        if (p == null || p.itemId() == null) {
            return null;
        }
        return new PlaylistFeed.Position(p.itemId(), p.offsetSeconds(), p.loopsCompleted());
    }

    Feed background(Canvas canvas, StudioSpec.SceneSetting s, PlaylistFeed.SourceFactory factory,
                            String ffmpeg, Random random) throws IOException {
        if (s.backgroundKey() != null && "IMAGE".equals(s.backgroundType())) {
            try {
                return StillFeed.image(canvas, cache.fetch(s.backgroundKey()), ffmpeg);
            } catch (IOException e) {
                log.warn("Scene {} background unusable, using a colour: {}", s.name(), e.getMessage());
            }
        }
        if (s.backgroundKey() != null && "VIDEO".equals(s.backgroundType())) {
            cache.prefetch(s.backgroundKey());
            PlaylistFeed.Item item = new PlaylistFeed.Item(s.backgroundMediaId() + "|" + s.backgroundKey(),
                    s.name(), Path.of("bg"), true, 0);
            return new PlaylistFeed(canvas, List.of(item), PlaylistFeed.Order.IN_ORDER, 0, null, factory, false, random);
        }
        return StillFeed.color(canvas, rgb(s.color(), 0x101820));
    }

    private Overlay toOverlay(StudioSpec.OverlaySetting o) throws IOException {
        Overlay.Type type;
        try {
            type = Overlay.Type.valueOf(o.type());
        } catch (Exception e) {
            return null;
        }
        Path image = null;
        if (type == Overlay.Type.IMAGE) {
            if (o.imageKey() == null) {
                return null;
            }
            image = cache.fetch(o.imageKey());
        }
        Instant target = null;
        if (o.target() != null && !o.target().isBlank()) {
            try {
                target = Instant.parse(o.target());
            } catch (Exception ignored) {
                // no countdown target
            }
        }
        return new Overlay(o.id(), type, o.visible(), o.x(), o.y(), o.width(), o.size() > 0 ? o.size() : 0.045,
                o.text(), image, rgb(o.color(), 0xffffff), argb(o.background(), 0x99000000),
                o.opacity() > 0 ? o.opacity() : 1.0, o.timeZone(), target, o.speed());
    }

    static int rgb(String hex, int fallback) {
        long v = parseHex(hex);
        return v < 0 ? fallback : (int) (v & 0xffffff);
    }

    static int argb(String hex, int fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        long v = parseHex(hex);
        if (v < 0) {
            return fallback;
        }
        return h.length() <= 6 ? (int) (0xff000000L | v) : (int) v;
    }

    private static long parseHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return -1;
        }
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!h.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
            return -1;
        }
        return Long.parseLong(h, 16);
    }

    private static double clampVolume(double v) {
        return Double.isNaN(v) ? 1.0 : Math.max(0, Math.min(2, v));
    }
}
