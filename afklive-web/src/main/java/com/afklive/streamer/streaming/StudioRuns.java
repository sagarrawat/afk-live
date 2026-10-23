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
        Studio.Status st = h.studio().status();
        jobs.reportStudio(jobId, workerId, st.scene(), st.inputLive(), nowPlayingJson(st.nowPlaying()));
        PlaylistFeed playlist = h.built.playlist();
        if (h.streamId != null && playlist != null && System.nanoTime() - h.lastSaveNanos >= SAVE_POSITION_MILLIS * 1_000_000) {
            PlaylistFeed.Position p = playlist.position();
            if (p.itemId() != null) {
                String json = StudioSpec.json(new StudioSpec.Position(p.itemId(), p.offsetSeconds(), p.loopsCompleted()));
                if (!json.equals(h.lastPosition)) {
                    streams.saveResumePosition(h.streamId, json);
                    h.lastPosition = json;
                }
            }
            h.lastSaveNanos = System.nanoTime();
        }
    }

    /** Applies one command from the web app. Runs on the worker's command thread; the studio applies it between frames. */
    public void apply(Handle h, StreamCommand c) {
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
        }
    }

    /** New playlist, music, volumes or overlays while live: applied in place, the output never restarts. */
    private void reload(Handle h, StudioSpec next) {
        Studio studio = h.studio();
        PlaylistFeed playlist = h.built.playlist();
        if (playlist != null && next.videos() != null) {
            playlist.setItems(StudioBuilder.items(next.videos()), StudioBuilder.order(next.order()), next.loops());
        }
        PlaylistFeed music = h.built.music();
        if (music != null && next.music() != null && !next.music().isEmpty()) {
            music.setItems(StudioBuilder.items(next.music()), StudioBuilder.order(next.musicOrder()), 0);
        }
        List<Overlay> global = new ArrayList<>();
        Map<String, List<Overlay>> perScene = new LinkedHashMap<>();
        try {
            builder.splitOverlays(next, global, perScene);
        } catch (IOException e) {
            log.warn("Overlay images for a live update couldn't be fetched: {}", e.getMessage());
        }
        studio.setGlobalOverlays(global);
        double videoVolume = "MUTE".equals(next.audioMode()) ? 0 : Math.max(0, Math.min(2, next.videoVolume()));
        double musicVolume = Math.max(0, Math.min(2, next.musicVolume()));
        for (String name : List.of(StudioSpec.LIVE, StudioSpec.BRB, StudioSpec.STARTING, StudioSpec.ENDING)) {
            Studio.Scene old = studio.scene(name);
            if (old == null) {
                continue;
            }
            boolean live = StudioSpec.LIVE.equals(name);
            Feed music1 = old.music();
            if ("KEEP".equals(next.audioMode()) && live) {
                music1 = null;
            } else if (live && music1 == null && h.built.music() != null) {
                music1 = h.built.music();
            }
            studio.putScene(new Studio.Scene(name, old.video(), music1, live ? videoVolume : 0, musicVolume,
                    perScene.getOrDefault(name, List.of()), old.fallbackScene()));
        }
        h.spec = next;
    }

    public void close(Handle h) {
        try {
            h.studio().close();
        } finally {
            preparer.cleanup(h.workDir);
        }
    }

    static String nowPlayingJson(PlaylistFeed.NowPlaying np) {
        if (np == null || np.item() == null) {
            return null;
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
        return StudioSpec.json(m);
    }
}
