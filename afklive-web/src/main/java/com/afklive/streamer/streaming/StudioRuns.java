package com.afklive.streamer.streaming;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.StreamCommand;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.streaming.studio.Feed;
import com.afklive.streamer.streaming.studio.Overlay;
import com.afklive.streamer.streaming.studio.PlaylistFeed;
import com.afklive.streamer.streaming.studio.Studio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The worker's side of studio streams: builds the studio for a job, reports what's on screen and
 * playing, saves the playlist position (so a restart resumes where it was), and applies live
 * commands from the web app between frames.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StudioRuns {

    /** Saved every this often, as the spec requires (resume within 10 s of where it was). */
    static final long SAVE_POSITION_MILLIS = 10_000;

    private final StudioBuilder builder;
    private final LiveStreamRepository streams;
    private final StreamJobRepository jobs;
    private final StreamPreparer preparer;

    /** One running studio. */
    public static final class Handle {
        final StudioBuilder.Built built;
        final Path workDir;
        final Long streamId;
        volatile StudioSpec spec;
        long lastSaveNanos;
        String lastPosition;
        boolean closed;

        Handle(StudioBuilder.Built built, Path workDir, Long streamId, StudioSpec spec) {
            this.built = built;
            this.workDir = workDir;
            this.streamId = streamId;
            this.spec = spec;
        }

        public Studio studio() {
            return built.studio();
        }
    }

    public Handle start(long jobId, StreamSpec spec, String scene, Studio.Listener listener) throws IOException {
        Path workDir = preparer.newWorkDir(jobId);
        StudioSpec.Position resume = spec.studio().resume();
        if (spec.streamId() != null) {
            // The latest saved position wins: after a restart or takeover, continue where it was.
            String saved = streams.findById(spec.streamId()).map(LiveStream::getResumePosition).orElse(null);
            if (saved != null) {
                try {
                    resume = StudioSpec.parse(saved, StudioSpec.Position.class);
                } catch (Exception e) {
                    log.debug("Unreadable saved position for stream {}", spec.streamId());
                }
            }
        }
        try {
            StudioBuilder.Built built = builder.build(jobId, spec.studio(), workDir, resume, scene, listener);
            return new Handle(built, workDir, spec.streamId(), spec.studio());
        } catch (IOException | RuntimeException e) {
            preparer.cleanup(workDir);
            throw e;
        }
    }

    /** Reports studio state with the heartbeat; saves the playlist position every 10 s. */
    public void report(long jobId, String workerId, Handle h) {
        if (h.closed) {
            return;
        }
        Studio.Status st = h.studio().status();
        jobs.reportStudio(jobId, workerId, st.scene(), st.inputLive(),
                nowPlayingJson(st.nowPlaying(), h.built.playlist() == null ? List.of() : h.built.playlist().failedItems()));
        PlaylistFeed playlist = h.built.playlist();
        if (h.streamId != null && playlist != null && System.nanoTime() - h.lastSaveNanos >= SAVE_POSITION_MILLIS * 1_000_000) {
            PlaylistFeed.Position p = playlist.position();
            if (p.itemId() != null) {
                String json = StudioSpec.json(new StudioSpec.Position(p.itemId(), p.offsetSeconds(), p.loopsCompleted()));
                if (!json.equals(h.lastPosition)) {
                    streams.saveResumePosition(h.streamId, json, jobId, workerId); // only while this worker owns the run
                    h.lastPosition = json;
                }
            }
            h.lastSaveNanos = System.nanoTime();
        }
    }

    /** Applies one command from the web app. Runs on the worker's command thread; the studio applies it between frames. */
    public void apply(Handle h, StreamCommand c) {
        synchronized (h) {
            if (!h.closed) {
                applyLocked(h, c);
            }
        }
    }

    private void applyLocked(Handle h, StreamCommand c) {
        Studio studio = h.studio();
        switch (c.getType()) {
            case SCENE -> studio.switchScene(c.getPayload());
            case SKIP -> studio.skip();
            case OVERLAY -> {
                Map<String, Object> m = StudioSpec.parse(c.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                studio.setOverlayVisible(String.valueOf(m.get("id")), Boolean.TRUE.equals(m.get("visible")));
            }
            case FINISH_PASS -> {
                if (h.built.playlist() != null) {
                    h.built.playlist().endAfterThisPass();
                }
            }
            case RELOAD -> reload(h, StudioSpec.parse(c.getPayload(), StudioSpec.class));
            case TARGETS, RECONNECT -> {
                // destinations belong to the runner, not the studio: StreamWorker applies these
            }
        }
    }

    /** New playlist, music, volumes, scenes or overlays while live: applied in place, the output never restarts. */
    private void reload(Handle h, StudioSpec next) {
        Studio studio = h.studio();
        StudioBuilder.Built built = h.built;
        PlaylistFeed playlist = built.playlist();
        if (built.streamFit != null) {
            built.streamFit.set(StudioBuilder.fitOf(next.fit()));
        }
        built.crossfadeSeconds = next.crossfadeSeconds();
        if (built.music() != null) {
            built.music().setCrossfadeSeconds(next.crossfadeSeconds());
        }
        if (playlist != null && next.videos() != null) {
            built.videoRefs.set(List.copyOf(next.videos()));
            playlist.setItems(StudioBuilder.items(next.videos()), StudioBuilder.order(next.order()), next.loops());
            if (next.finishAfterPass()) {
                playlist.endAfterThisPass();
            }
        }

        // Music: KEEP or an empty list means none; otherwise update the bed, creating it if the run had none.
        boolean wantMusic = !"KEEP".equals(next.audioMode()) && next.music() != null && !next.music().isEmpty();
        PlaylistFeed music = built.music();
        if (wantMusic && music == null) {
            music = built.newMusic(next.music(), next.musicOrder());
            built.music = music;
        } else if (wantMusic) {
            built.musicRefs.set(List.copyOf(next.music()));
            music.setItems(StudioBuilder.items(next.music()), StudioBuilder.order(next.musicOrder()), 0);
        }
        PlaylistFeed musicNow = wantMusic ? music : null;

        List<Overlay> global = new ArrayList<>();
        Map<String, List<Overlay>> perScene = new LinkedHashMap<>();
        try {
            builder.splitOverlays(next, global, perScene);
        } catch (IOException e) {
            log.warn("Overlays for a live update couldn't be prepared: {}", e.getMessage());
        }
        studio.setGlobalOverlays(global);

        Map<String, StudioSpec.SceneSetting> settings = new LinkedHashMap<>();
        if (next.scenes() != null) {
            next.scenes().forEach(sc -> settings.put(sc.name(), sc));
        }
        double videoVolume = "MUTE".equals(next.audioMode()) ? 0 : Math.max(0, Math.min(2, next.videoVolume()));
        double musicVolume = Math.max(0, Math.min(2, next.musicVolume()));
        for (String name : List.of(StudioSpec.BRB, StudioSpec.STARTING, StudioSpec.ENDING, StudioSpec.LIVE)) {
            Studio.Scene old = studio.scene(name);
            if (old == null) {
                continue;
            }
            boolean live = StudioSpec.LIVE.equals(name);
            StudioSpec.SceneSetting setting = settings.get(name);
            StudioSpec.SceneSetting before = built.sceneSettings.get(name);
            boolean sceneMusic = setting == null ? (before == null || before.music()) : setting.music();
            Feed video = old.video();
            if (!live && setting != null && before != null && !sameBackground(setting, before)) {
                try {
                    video = built.background(setting); // the old background is closed when the scene is replaced
                    Studio.Scene liveScene = studio.scene(StudioSpec.LIVE);
                    if (StudioSpec.BRB.equals(name) && liveScene != null
                            && liveScene.video() instanceof com.afklive.streamer.streaming.studio.LiveFeed lf) {
                        lf.setFallback(video); // the phone's never-drop fallback follows the new BRB look
                    }
                } catch (IOException e) {
                    log.warn("Scene {} background couldn't be changed live: {}", name, e.getMessage());
                }
            }
            if (setting != null) {
                built.sceneSettings.put(name, setting);
            }
            studio.putScene(new Studio.Scene(name, video, sceneMusic ? musicNow : null, live ? videoVolume : 0, musicVolume,
                    perScene.getOrDefault(name, List.of()), old.fallbackScene()));
        }
        h.spec = next;
    }

    private static boolean sameBackground(StudioSpec.SceneSetting a, StudioSpec.SceneSetting b) {
        return java.util.Objects.equals(a.backgroundKey(), b.backgroundKey())
                && java.util.Objects.equals(a.backgroundType(), b.backgroundType())
                && java.util.Objects.equals(a.color(), b.color());
    }

    /** The scene on air (FO-2: copy mode only plays the LIVE scene). */
    public String scene(Handle h) {
        return h.studio().status().scene();
    }

    /** The playlist position now, in the form copy mode and resume use; null without a playlist. */
    public StudioSpec.Position position(Handle h) {
        PlaylistFeed playlist = h.built.playlist();
        if (playlist == null) {
            return null;
        }
        PlaylistFeed.Position p = playlist.position();
        return p.itemId() == null ? null : new StudioSpec.Position(p.itemId(), p.offsetSeconds(), p.loopsCompleted());
    }

    /** The run's settings as they are now, live changes included. */
    public StudioSpec spec(Handle h) {
        return h.spec;
    }

    public void close(Handle h) {
        synchronized (h) {
            h.closed = true;
        }
        try {
            h.studio().close();
        } finally {
            preparer.cleanup(h.workDir);
        }
    }

    static String nowPlayingJson(PlaylistFeed.NowPlaying np) {
        return nowPlayingJson(np, List.of());
    }

    /** What's playing, plus (PL-7) the items skipped because they couldn't be played, for the web app to alert on. */
    static String nowPlayingJson(PlaylistFeed.NowPlaying np, List<PlaylistFeed.Failed> failed) {
        List<Map<String, Object>> failures = new ArrayList<>();
        for (PlaylistFeed.Failed f : failed) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("mediaId", StudioBuilder.mediaIdOf(f.item().id()));
            x.put("title", f.item().title() == null ? "" : f.item().title());
            x.put("reason", f.reason() == null ? "" : f.reason().length() > 200 ? f.reason().substring(0, 200) : f.reason());
            failures.add(x);
        }
        if (np == null || np.item() == null) {
            return failures.isEmpty() ? null : StudioSpec.json(Map.of("failed", failures));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaId", StudioBuilder.mediaIdOf(np.item().id()));
        m.put("title", np.item().title());
        m.put("elapsed", Math.round(np.elapsedSeconds()));
        m.put("duration", Math.round(np.item().durationSeconds()));
        List<Map<String, Object>> next = new ArrayList<>();
        for (PlaylistFeed.Item i : np.upNext()) {
            next.add(Map.of("mediaId", StudioBuilder.mediaIdOf(i.id()), "title", i.title() == null ? "" : i.title()));
        }
        m.put("next", next);
        if (!failures.isEmpty()) {
            m.put("failed", failures);
        }
        return StudioSpec.json(m);
    }
}
