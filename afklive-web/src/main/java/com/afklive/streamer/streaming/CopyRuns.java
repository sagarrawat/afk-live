package com.afklive.streamer.streaming;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.service.PlayoutCopies;
import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.streaming.studio.PlaylistFeed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * FO-2, the worker's side of copy mode. A studio stream that needs nothing drawn or mixed live (a
 * playlist, no music bed, no phone, on its LIVE scene, and only overlays that never change, such as the
 * Free plan's mark or a logo: {@link StaticOverlays}) can be played from profile copies by stream copy
 * instead of decoding and encoding every frame. Static overlays are baked into the copies, which are
 * kept per overlay set. This decides when that's possible,
 * builds the {@link CopyPlayer}, reports what's playing and saves the position like the studio does.
 * The worker hands a run between the studio and copy mode with {@link StreamRunner#handOff}, so
 * neither direction disconnects a destination.
 * <p>
 * Off unless {@code app.streaming.copy-mode=true}.
 */
@Component
@Slf4j
public class CopyRuns {

    private final PlayoutCopies copies;
    private final MediaCache cache;
    private final LiveStreamRepository streams;
    private final StreamJobRepository jobs;
    private final StreamPreparer preparer;
    private final boolean enabled;

    public CopyRuns(PlayoutCopies copies, MediaCache cache, LiveStreamRepository streams, StreamJobRepository jobs,
                    StreamPreparer preparer, @Value("${app.streaming.copy-mode:false}") boolean enabled) {
        this.copies = copies;
        this.cache = cache;
        this.streams = streams;
        this.jobs = jobs;
        this.preparer = preparer;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** A run in copy mode. */
    public static final class Handle {
        final CopyPlayer player;
        final Path workDir;
        final Long streamId;
        final StudioSpec spec;
        final List<String> pinned;
        /** The identity of the overlays its copies carry ('' for none). */
        final String overlaySet;
        long lastSaveNanos;
        long lastTouchNanos = System.nanoTime();
        String lastPosition;
        volatile boolean closed;

        Handle(CopyPlayer player, Path workDir, Long streamId, StudioSpec spec, List<String> pinned, String overlaySet) {
            this.player = player;
            this.workDir = workDir;
            this.streamId = streamId;
            this.spec = spec;
            this.pinned = pinned;
            this.overlaySet = overlaySet;
        }

        public String overlaySet() {
            return overlaySet;
        }

        public CopyPlayer player() {
            return player;
        }

        public StudioSpec spec() {
            return spec;
        }
    }

    /**
     * Why this run can't play by copy, in a few words; null when it can. Anything the studio draws or
     * mixes live rules it out: an overlay that changes (clock, countdown, ticker, text with a placeholder),
     * a music bed or changed video volume, the phone, another scene, or an end-after-this-pass request.
     * Static overlays don't: the copies carry them.
     */
    public static String ineligible(StudioSpec s, String scene) {
        if (s == null || !"PLAYLIST".equals(s.source()) || s.videos() == null || s.videos().isEmpty()) {
            return "not a playlist";
        }
        if (s.phone() != null) {
            return "phone input";
        }
        String dynamic = StaticOverlays.dynamicReason(s);
        if (dynamic != null) {
            return dynamic;
        }
        if (s.music() != null && !s.music().isEmpty() && !"KEEP".equals(s.audioMode())) {
            return "music bed";
        }
        if ("MUTE".equals(s.audioMode()) || Math.abs(s.videoVolume() - 1.0) > 0.01) {
            return "video volume changed";
        }
        String on = scene != null ? scene : s.initialScene();
        if (on != null && !StudioSpec.LIVE.equals(on)) {
            return "scene " + on;
        }
        if (s.finishAfterPass()) {
            return "ending after this pass";
        }
        for (StudioSpec.Media m : s.videos()) {
            if (m.duration() <= 0) {
                return "a video of unknown length";
            }
        }
        return null;
    }

    /**
     * Copy mode for this run, when it qualifies and every profile copy is ready; otherwise null (and the
     * missing copies are asked for, so a later check can switch). {@code resume} is where to start.
     */
    public Handle prepare(long jobId, Long streamId, StudioSpec spec, String scene, StudioSpec.Position resume) {
        if (!enabled) {
            return null;
        }
        String why = ineligible(spec, scene);
        if (why != null) {
            log.debug("Job {} stays in the studio: {}", jobId, why);
            return null;
        }
        Canvas canvas = Canvas.of(spec.height(), spec.vertical(), spec.fps());
        String streamFit = StudioBuilder.fitOf(spec.fit()).name();
        // The static overlays (the mark, a logo) are in the copies: copies for this exact set only.
        List<StudioSpec.OverlaySetting> overlays = StaticOverlays.staticSet(spec);
        String overlaySet = StaticOverlays.identity(overlays, canvas);
        String overlaySpec = overlaySet.isEmpty() ? null : StudioSpec.json(overlays);
        List<String> keys = new ArrayList<>();
        boolean ready = true;
        for (StudioSpec.Media m : spec.videos()) {
            String fit = m.fit() != null && !m.fit().isBlank() ? StudioBuilder.fitOf(m.fit()).name() : streamFit;
            String key = copies.profileKeyFor(m.key(), canvas.width(), canvas.height(), canvas.fps(), fit, m.gainDb(),
                    overlaySet, overlaySpec, true);
            if (key == null) {
                ready = false; // keep asking for the rest
            }
            keys.add(key);
        }
        if (!ready) {
            log.info("Job {} could play by copy once its files are prepared", jobId);
            return null;
        }
        List<String> pinned = new ArrayList<>();
        List<CopyPlayer.Entry> entries = new ArrayList<>();
        try {
            for (int i = 0; i < spec.videos().size(); i++) {
                StudioSpec.Media m = spec.videos().get(i);
                String key = keys.get(i);
                cache.pin(key);
                pinned.add(key);
                Path file = cache.fetch(key);
                entries.add(new CopyPlayer.Entry(m.id() + "|" + m.key(), m.title(), file, m.duration()));
            }
            Path workDir = preparer.newWorkDir(jobId);
            CopyPlayer player = new CopyPlayer(entries, StudioBuilder.order(spec.order()), spec.loops(), resume, workDir,
                    CopyPlayer.defaultFfmpeg(), new Random());
            return new Handle(player, workDir, streamId, spec, pinned, overlaySet);
        } catch (IOException | RuntimeException e) {
            pinned.forEach(cache::release);
            log.warn("Copy mode for job {} couldn't start, staying in the studio: {}", jobId, e.getMessage());
            return null;
        }
    }

    /** The saved position wins over the spec's (after a restart or takeover, continue where it was). */
    public StudioSpec.Position resumeFor(StreamSpec spec) {
        StudioSpec.Position resume = spec.studio() == null ? null : spec.studio().resume();
        if (spec.streamId() != null) {
            String saved = streams.findById(spec.streamId()).map(LiveStream::getResumePosition).orElse(null);
            if (saved != null) {
                try {
                    resume = StudioSpec.parse(saved, StudioSpec.Position.class);
                } catch (Exception e) {
                    log.debug("Unreadable saved position for stream {}", spec.streamId());
                }
            }
        }
        return resume;
    }

    /** Reports what's playing with the heartbeat and saves the position every 10 s, as the studio does. */
    public void report(long jobId, String workerId, Handle h) {
        if (h.closed) {
            return;
        }
        CopyPlayer.Now n = h.player.now();
        jobs.reportStudio(jobId, workerId, StudioSpec.LIVE, null, nowPlayingJson(n));
        if (h.streamId != null && System.nanoTime() - h.lastSaveNanos >= StudioRuns.SAVE_POSITION_MILLIS * 1_000_000) {
            savePosition(jobId, workerId, h);
        }
        if (System.nanoTime() - h.lastTouchNanos >= TOUCH_EVERY.toNanos()) {
            h.lastTouchNanos = System.nanoTime();
            try {
                copies.touch(h.pinned); // FO-6: still played, so the clean-up keeps them
            } catch (RuntimeException e) {
                log.warn("Couldn't record that job {} still plays its copies: {}", jobId, e.getMessage());
            }
        }
    }

    /** How often a run playing by copy records that its copies are still played (FO-6 keeps unplayed ones 30 days). */
    static final java.time.Duration TOUCH_EVERY = java.time.Duration.ofHours(6);

    /** Saves the position now: before handing over to the studio, which resumes from it. */
    public void savePosition(long jobId, String workerId, Handle h) {
        if (h.streamId == null) {
            return;
        }
        String json = StudioSpec.json(h.player.position());
        if (!json.equals(h.lastPosition)) {
            streams.saveResumePosition(h.streamId, json, jobId, workerId);
            h.lastPosition = json;
        }
        h.lastSaveNanos = System.nanoTime();
    }

    public void close(Handle h) {
        if (h.closed) {
            return;
        }
        h.closed = true;
        h.pinned.forEach(cache::release);
        preparer.cleanup(h.workDir);
    }

    static String nowPlayingJson(CopyPlayer.Now n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaId", StudioBuilder.mediaIdOf(n.entry().itemId()));
        m.put("title", n.entry().title());
        m.put("elapsed", Math.round(n.elapsedSeconds()));
        m.put("duration", Math.round(n.entry().durationSeconds()));
        List<Map<String, Object>> next = new ArrayList<>();
        for (CopyPlayer.Entry e : n.upNext()) {
            next.add(Map.of("mediaId", StudioBuilder.mediaIdOf(e.itemId()), "title", e.title() == null ? "" : e.title()));
        }
        m.put("next", next);
        m.put("mode", "copy");
        return StudioSpec.json(m);
    }

    /** The order type the player uses, for tests. */
    static PlaylistFeed.Order orderOf(StudioSpec s) {
        return StudioBuilder.order(s.order());
    }
}
