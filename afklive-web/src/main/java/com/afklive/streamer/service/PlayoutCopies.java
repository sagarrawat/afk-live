package com.afklive.streamer.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Playout copies: a library video prepared once at the size and frame rate a stream actually uses.
 * <p>
 * The studio decodes every file live and shrinks it to the stream's canvas. For a 1080x1920 60 fps phone
 * or gameplay clip on a 720p 30 fps stream that's most of the stream's CPU, every second, forever: the
 * decoder reads twice the frames it needs at more than twice the pixels. A copy at 720x1280 30 fps
 * decodes about six times cheaper, so the copy (a one-off job of roughly the video's length on one core)
 * pays for itself within minutes of streaming.
 * <p>
 * Workers ask for a copy when they play a file that would benefit ({@link #keyFor}) and keep using the
 * original until it's ready; the web side makes copies in the background at low priority
 * ({@link #prepareNext}). Copies are our cost, not the creator's: they don't count toward storage.
 * Rows live in {@code media_playout_copies}; claims are conditional updates so any number of web
 * instances can share the work.
 */
@Service
@Slf4j
public class PlayoutCopies {

    /** A copy is only worth making when it at least this much smaller (per side) or lower in frame rate. */
    static final double WORTH_IT = 0.9;
    /** Longer videos stream from the original: a copy would take hours of background CPU. */
    static final double MAX_SECONDS = 4 * 3600;
    /** A claim this old belongs to an instance that died mid-way. */
    static final Duration STALE_CLAIM = Duration.ofHours(8);
    /** FO-6: a copy nobody has played for this long is deleted; it's made again if a stream needs it. */
    static final Duration KEEP_UNPLAYED = Duration.ofDays(30);
    private static final Duration REMEMBER_READY = Duration.ofMinutes(10);
    private static final Duration REMEMBER_OTHER = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final FileStorageService storage;
    private final String role;
    private final boolean auto;
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();
    /** The copy this instance is making now, so stopping can hand it back (OPS-4: every deploy stops a web slot). */
    private final AtomicReference<Working> working = new AtomicReference<>();
    private volatile boolean stopping;
    private final ScheduledExecutorService preparer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "playout-copies");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public PlayoutCopies(JdbcTemplate jdbc, FileStorageService storage,
                         @Value("${app.streaming.role:all}") String role,
                         @Value("${app.media.auto-process:true}") boolean auto) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.role = role;
        this.auto = auto;
    }

    private record Remembered(String key, Instant until) {
    }

    /** The size and frame rate a copy is made at; null when the original is already cheap to play. */
    public record Target(int canvasWidth, int canvasHeight, int fps) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("worker".equalsIgnoreCase(role) || !auto) {
            return;
        }
        preparer.scheduleWithFixedDelay(() -> {
            try {
                while (prepareNext()) {
                    // one at a time, back to back
                }
                deleteUnplayed();
            } catch (Exception e) {
                log.error("Preparing playout copies failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Hands the copy in progress back (PENDING) before this instance exits, so the other web slot picks it
     * up within a minute instead of after the 8-hour stale-claim timeout; then stops its FFmpeg. Fenced on
     * the claim time, so a copy another instance has taken over meanwhile is left alone.
     */
    @PreDestroy
    void stop() {
        stopping = true;
        preparer.shutdownNow();
        Working w = working.getAndSet(null);
        if (w == null) {
            return;
        }
        try {
            if (jdbc.update("UPDATE media_playout_copies SET status = 'PENDING', claimed_at = NULL WHERE id = ? AND status = 'WORKING' AND claimed_at = ?",
                    w.id(), Timestamp.from(w.claimedAt())) == 1) {
                log.info("Stopping: copy {} handed back for another instance to make", w.id());
            }
        } catch (Exception e) {
            log.warn("Couldn't hand copy {} back on stop: {}", w.id(), e.getMessage());
        }
        Process p = w.process().get();
        if (p != null) {
            p.destroyForcibly();
        }
    }

    private record Working(long id, Instant claimedAt, AtomicReference<Process> process) {
    }

    // ------------------------------------------------------------------ deciding

    /**
     * Whether a copy for this canvas would save real work: the source is noticeably bigger than what the
     * fullest fit mode (fill) needs, or has noticeably more frames per second. This only decides whether
     * to bother; the copy itself is sized by FFmpeg from the picture as displayed ({@link #command}), so a
     * phone video whose probed size is before rotation still gets a correct, never-too-small copy.
     */
    static Target worthCopying(Integer width, Integer height, Double fps, double duration,
                               int canvasWidth, int canvasHeight, int canvasFps) {
        if (width == null || height == null || width <= 0 || height <= 0 || duration > MAX_SECONDS) {
            return null;
        }
        double scale = Math.min(1, scaleFor(width, height, canvasWidth, canvasHeight));
        boolean smaller = scale < WORTH_IT;
        boolean fewerFrames = fps != null && fps > canvasFps / WORTH_IT;
        return smaller || fewerFrames ? new Target(canvasWidth, canvasHeight, canvasFps) : null;
    }

    /** The scale at which a w x h picture fills a W x H canvas (the "fill" fit; "fit" needs less). */
    private static double scaleFor(int w, int h, int canvasWidth, int canvasHeight) {
        return Math.max(canvasWidth / (double) w, canvasHeight / (double) h);
    }

    // ------------------------------------------------------------------ playing (worker side)

    /**
     * The storage key a stream on this canvas should play for {@code originalKey}: a ready copy, or the
     * original (asking for a copy when one would help). Called on a decoder thread just before a file
     * starts; answers are remembered briefly so a looping playlist doesn't ask the database every time.
     */
    public String keyFor(String originalKey, int canvasWidth, int canvasHeight, int canvasFps) {
        String memo = originalKey + "@" + canvasWidth + "x" + canvasHeight + "@" + canvasFps;
        Remembered r = remembered.get(memo);
        Instant now = Instant.now();
        if (r != null && r.until().isAfter(now)) {
            return r.key();
        }
        String key = originalKey;
        try {
            key = lookUp(originalKey, canvasWidth, canvasHeight, canvasFps);
        } catch (Exception e) {
            log.debug("Playout copy lookup for {} failed: {}", originalKey, e.getMessage());
        }
        remembered.put(memo, new Remembered(key, now.plus(key.equals(originalKey) ? REMEMBER_OTHER : REMEMBER_READY)));
        return key;
    }

    /** For tests: forget remembered answers. */
    public void forget() {
        remembered.clear();
    }

    /** What {@link #keyFor} last answered, without asking the database (for prefetching). */
    public String knownKeyFor(String originalKey, int canvasWidth, int canvasHeight, int canvasFps) {
        Remembered r = remembered.get(originalKey + "@" + canvasWidth + "x" + canvasHeight + "@" + canvasFps);
        return r == null ? originalKey : r.key();
    }

    private String lookUp(String originalKey, int canvasWidth, int canvasHeight, int canvasFps) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, width, height, fps, duration_seconds FROM media_assets WHERE s3_key = ? AND content_type = 'VIDEO' LIMIT 1",
                originalKey);
        if (rows.isEmpty()) {
            return originalKey;
        }
        Map<String, Object> a = rows.getFirst();
        Number duration = (Number) a.get("duration_seconds");
        Target t = worthCopying(intOrNull(a.get("width")), intOrNull(a.get("height")),
                a.get("fps") == null ? null : ((Number) a.get("fps")).doubleValue(),
                duration == null ? 0 : duration.doubleValue(), canvasWidth, canvasHeight, canvasFps);
        if (t == null) {
            return originalKey;
        }
        long mediaId = ((Number) a.get("id")).longValue();
        List<Map<String, Object>> copies = jdbc.queryForList(
                "SELECT status, storage_key FROM media_playout_copies WHERE media_id = ? AND kind = 'PLAYOUT' AND canvas_width = ? AND canvas_height = ? AND fps = ?",
                mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
        if (copies.isEmpty()) {
            jdbc.update("INSERT INTO media_playout_copies (media_id, canvas_width, canvas_height, fps, status) VALUES (?, ?, ?, ?, 'PENDING') "
                    + "ON CONFLICT ON CONSTRAINT uq_media_playout_copies DO NOTHING", mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
            log.info("Asked for a playout copy of media {} at {}x{} {} fps", mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
            return originalKey;
        }
        Map<String, Object> c = copies.getFirst();
        if (!"READY".equals(c.get("status")) || c.get("storage_key") == null) {
            return originalKey;
        }
        // Remembered for 10 minutes, so this write happens at most that often per stream and file.
        jdbc.update("UPDATE media_playout_copies SET last_played_at = now() WHERE media_id = ? AND kind = 'PLAYOUT' AND canvas_width = ? AND canvas_height = ? AND fps = ?",
                mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
        return (String) c.get("storage_key");
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    // ------------------------------------------------------------------ making (web side)

    /** Claims and makes one requested copy; false when there's nothing to do. Public so tests can drive it. */
    public boolean prepareNext() {
        if (stopping) {
            return false;
        }
        Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<Map<String, Object>> claimed = jdbc.queryForList(
                "UPDATE media_playout_copies SET status = 'WORKING', claimed_at = ? WHERE id = ("
                        + " SELECT id FROM media_playout_copies WHERE status = 'PENDING' OR (status = 'WORKING' AND claimed_at < ?)"
                        + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, media_id, kind, canvas_width, canvas_height, fps, fit, gain_tenths",
                Timestamp.from(claimedAt), Timestamp.from(claimedAt.minus(STALE_CLAIM)));
        if (claimed.isEmpty()) {
            return false;
        }
        Map<String, Object> c = claimed.getFirst();
        long id = ((Number) c.get("id")).longValue();
        Working current = new Working(id, claimedAt, new AtomicReference<>());
        working.set(current);
        try {
            return make(c, id, claimedAt, current);
        } finally {
            working.compareAndSet(current, null);
        }
    }

    private boolean make(Map<String, Object> c, long id, Instant claimedAt, Working current) {
        List<Map<String, Object>> media = jdbc.queryForList("SELECT s3_key, fps, has_audio FROM media_assets WHERE id = ?", c.get("media_id"));
        if (media.isEmpty()) {
            jdbc.update("DELETE FROM media_playout_copies WHERE id = ?", id);
            return true;
        }
        String original = (String) media.getFirst().get("s3_key");
        Double sourceFps = media.getFirst().get("fps") == null ? null : ((Number) media.getFirst().get("fps")).doubleValue();
        int w = ((Number) c.get("canvas_width")).intValue();
        int h = ((Number) c.get("canvas_height")).intValue();
        int fps = ((Number) c.get("fps")).intValue();
        boolean profile = "PROFILE".equals(c.get("kind"));
        String fit = (String) c.get("fit");
        double gainDb = ((Number) c.get("gain_tenths")).intValue() / 10.0;
        boolean hasAudio = !Boolean.FALSE.equals(media.getFirst().get("has_audio"));

        String key = null;
        String reason = null;
        long started = System.nanoTime();
        Path out = null;
        try {
            out = Files.createTempFile("playout-", profile ? ".ts" : ".mp4");
            List<String> cmd = profile
                    ? profileCommand(storage.readLocation(original), out, w, h, fps, fit, gainDb, hasAudio)
                    : command(storage.readLocation(original), out, w, h, fps, sourceFps);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            current.process().set(p);
            if (stopping) {
                p.destroyForcibly(); // stop() ran between the claim and the start
            }
            String log = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0 || Files.size(out) == 0) {
                reason = log.isBlank() ? "FFmpeg exited with " + p.exitValue() : last(log, 500);
            } else {
                try (InputStream in = Files.newInputStream(out)) {
                    key = storage.uploadFile(in, (profile ? "profile-" : "playout-") + w + "x" + h + "-" + fps
                            + (profile ? ".ts" : ".mp4"), Files.size(out));
                }
            }
        } catch (Exception e) {
            reason = e.getMessage();
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (Exception ignored) {
                    // temp file
                }
            }
        }
        int saved = jdbc.update("UPDATE media_playout_copies SET status = ?, storage_key = ?, reason = ?, finished_at = now() "
                        + "WHERE id = ? AND status = 'WORKING' AND claimed_at = ?",
                key != null ? "READY" : "FAILED", key, reason, id, Timestamp.from(claimedAt));
        if (saved != 1 && key != null) {
            deleteQuietly(key); // deleted or taken over meanwhile
        }
        log.info("{} copy {} of media {} at {}x{} {} fps: {} in {} s{}", profile ? "Profile" : "Playout", id, c.get("media_id"), w, h, fps,
                key != null ? "ready" : "failed", (System.nanoTime() - started) / 1_000_000_000L, reason == null ? "" : " (" + reason + ")");
        return true;
    }

    /**
     * The copy: the stream's frame rate (never more than the source's), scaled so it still fills the
     * canvas in any fit mode, never enlarged. H.264 at high quality (it's encoded again live), keyframes
     * every 2 s, AAC stereo. One thread at the lowest priority so live streams on the machine come first.
     */
    static List<String> command(String input, Path output, int canvasWidth, int canvasHeight, int fps, Double sourceFps) {
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(Path.of("/usr/bin/nice"))) {
            cmd.addAll(List.of("/usr/bin/nice", "-n", "19"));
        }
        String scale = String.format(Locale.ROOT, "min(1\\,max(%d/iw\\,%d/ih))", canvasWidth, canvasHeight);
        String rate = sourceFps != null && sourceFps > 0 && sourceFps <= fps ? "" : "fps=" + fps + ",";
        cmd.addAll(List.of(FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", input, "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn",
                "-vf", rate + "scale=trunc(iw*" + scale + "/2)*2:trunc(ih*" + scale + "/2)*2,setsar=1,format=yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-g", String.valueOf(2 * fps), "-threads", "1",
                "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-movflags", "+faststart", output.toString()));
        return cmd;
    }

    /**
     * FO-6: deletes copies nobody has played for {@link #KEEP_UNPLAYED} (a batch at a time). Safe: a
     * worker that still had one in mind falls back to the original (StudioBuilder) and asks again.
     */
    public int deleteUnplayed() {
        Instant cutoff = Instant.now().minus(KEEP_UNPLAYED);
        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT id, storage_key FROM media_playout_copies WHERE status IN ('READY', 'FAILED') "
                        + "AND COALESCE(last_played_at, finished_at, created_at) < ? ORDER BY id LIMIT 50", Timestamp.from(cutoff));
        int deleted = 0;
        for (Map<String, Object> row : old) {
            // Conditional: a play recorded since the select keeps the copy.
            if (jdbc.update("DELETE FROM media_playout_copies WHERE id = ? AND COALESCE(last_played_at, finished_at, created_at) < ?",
                    row.get("id"), Timestamp.from(cutoff)) == 1) {
                deleted++;
                if (row.get("storage_key") != null) {
                    deleteQuietly((String) row.get("storage_key"));
                }
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} playout copies nobody played for {} days", deleted, KEEP_UNPLAYED.toDays());
        }
        return deleted;
    }

    // ------------------------------------------------------------------ profile copies (FO-2)

    /**
     * The profile copy of a video for copy mode: the file encoded exactly as a stream on this canvas
     * sends it, with the item's fit and loudness correction baked in. Returns its storage key when ready;
     * otherwise null, after asking for it when {@code request} is set.
     */
    public String profileKeyFor(String originalKey, int canvasWidth, int canvasHeight, int fps, String fit, double gainDb,
                                boolean request) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM media_assets WHERE s3_key = ? AND content_type = 'VIDEO' LIMIT 1",
                Long.class, originalKey);
        if (ids.isEmpty()) {
            return null;
        }
        long mediaId = ids.getFirst();
        String f = fit == null ? "FIT" : fit.toUpperCase(Locale.ROOT);
        int gain = (int) Math.round(gainDb * 10);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, storage_key FROM media_playout_copies WHERE media_id = ? "
                + "AND kind = 'PROFILE' AND canvas_width = ? AND canvas_height = ? AND fps = ? AND fit = ? AND gain_tenths = ?",
                mediaId, canvasWidth, canvasHeight, fps, f, gain);
        if (rows.isEmpty()) {
            if (request) {
                jdbc.update("INSERT INTO media_playout_copies (media_id, kind, canvas_width, canvas_height, fps, fit, gain_tenths, status) "
                        + "VALUES (?, 'PROFILE', ?, ?, ?, ?, ?, 'PENDING') ON CONFLICT ON CONSTRAINT uq_media_playout_copies DO NOTHING",
                        mediaId, canvasWidth, canvasHeight, fps, f, gain);
                log.info("Asked for a profile copy of media {} at {}x{} {} fps ({})", mediaId, canvasWidth, canvasHeight, fps, f);
            }
            return null;
        }
        Map<String, Object> c = rows.getFirst();
        if (!"READY".equals(c.get("status")) || c.get("storage_key") == null) {
            return null;
        }
        jdbc.update("UPDATE media_playout_copies SET last_played_at = now() WHERE media_id = ? AND kind = 'PROFILE' "
                + "AND canvas_width = ? AND canvas_height = ? AND fps = ? AND fit = ? AND gain_tenths = ? "
                + "AND (last_played_at IS NULL OR last_played_at < now() - interval '10 minutes')",
                mediaId, canvasWidth, canvasHeight, fps, f, gain);
        return (String) c.get("storage_key");
    }

    /**
     * The profile copy: decoded and placed on the canvas exactly as the studio does it (same filters),
     * then encoded with the live encoder's settings, so copy mode and the studio send the same kind of
     * stream and can hand over to each other mid-stream. A file without sound gets silence, so every
     * copy has the same tracks. MPEG-TS, like the stream itself.
     */
    public static List<String> profileCommand(String input, Path output, int canvasWidth, int canvasHeight, int fps, String fit,
                                       double gainDb, boolean hasAudio) {
        com.afklive.streamer.streaming.studio.Canvas canvas = new com.afklive.streamer.streaming.studio.Canvas(canvasWidth, canvasHeight, fps, 44100);
        com.afklive.streamer.streaming.studio.DecoderSource.Fit parsed = com.afklive.streamer.streaming.studio.DecoderSource.Fit.parse(fit);
        com.afklive.streamer.streaming.studio.DecoderSource.Fit f = parsed == null ? com.afklive.streamer.streaming.studio.DecoderSource.Fit.FIT : parsed;
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(Path.of("/usr/bin/nice"))) {
            cmd.addAll(List.of("/usr/bin/nice", "-n", "19"));
        }
        cmd.addAll(List.of(FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", input));
        String graph = com.afklive.streamer.streaming.studio.DecoderSource.videoChain(canvas, f, false);
        if (hasAudio) {
            graph += ";[0:a:0]" + com.afklive.streamer.streaming.studio.DecoderSource.audioChain(canvas, gainDb, false) + "[a]";
        } else {
            cmd.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo"));
            graph += ";[1:a]anull[a]";
        }
        cmd.addAll(List.of("-filter_complex", graph, "-map", "[v]", "-map", "[a]"));
        if (!hasAudio) {
            cmd.add("-shortest");
        }
        cmd.addAll(FFmpegCommandBuilder.liveEncodeArgs(Math.min(canvasWidth, canvasHeight), fps));
        cmd.addAll(List.of("-threads", "1", "-f", "mpegts", "-mpegts_flags", "+resend_headers", "-muxdelay", "0", output.toString()));
        return cmd;
    }

    /** Storage keys of a file's copies, to delete with it. */
    public List<String> keysOf(long mediaId) {
        return jdbc.queryForList("SELECT storage_key FROM media_playout_copies WHERE media_id = ? AND storage_key IS NOT NULL",
                String.class, mediaId);
    }

    private void deleteQuietly(String key) {
        try {
            storage.deleteFile(key);
        } catch (Exception e) {
            log.warn("Could not delete {}", key, e);
        }
    }

    private static String last(String s, int n) {
        String t = s.strip();
        return t.length() <= n ? t : t.substring(t.length() - n);
    }
}
