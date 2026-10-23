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
    private static final Duration REMEMBER_READY = Duration.ofMinutes(10);
    private static final Duration REMEMBER_OTHER = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final FileStorageService storage;
    private final String role;
    private final boolean auto;
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();
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
            } catch (Exception e) {
                log.error("Preparing playout copies failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        preparer.shutdownNow();
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
                "SELECT status, storage_key FROM media_playout_copies WHERE media_id = ? AND canvas_width = ? AND canvas_height = ? AND fps = ?",
                mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
        if (copies.isEmpty()) {
            jdbc.update("INSERT INTO media_playout_copies (media_id, canvas_width, canvas_height, fps, status) VALUES (?, ?, ?, ?, 'PENDING') "
                    + "ON CONFLICT ON CONSTRAINT uq_media_playout_copies DO NOTHING", mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
            log.info("Asked for a playout copy of media {} at {}x{} {} fps", mediaId, t.canvasWidth(), t.canvasHeight(), t.fps());
            return originalKey;
        }
        Map<String, Object> c = copies.getFirst();
        return "READY".equals(c.get("status")) && c.get("storage_key") != null ? (String) c.get("storage_key") : originalKey;
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    // ------------------------------------------------------------------ making (web side)

    /** Claims and makes one requested copy; false when there's nothing to do. Public so tests can drive it. */
    public boolean prepareNext() {
        Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<Map<String, Object>> claimed = jdbc.queryForList(
                "UPDATE media_playout_copies SET status = 'WORKING', claimed_at = ? WHERE id = ("
                        + " SELECT id FROM media_playout_copies WHERE status = 'PENDING' OR (status = 'WORKING' AND claimed_at < ?)"
                        + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, media_id, canvas_width, canvas_height, fps",
                Timestamp.from(claimedAt), Timestamp.from(claimedAt.minus(STALE_CLAIM)));
        if (claimed.isEmpty()) {
            return false;
        }
        Map<String, Object> c = claimed.getFirst();
        long id = ((Number) c.get("id")).longValue();
        List<Map<String, Object>> media = jdbc.queryForList("SELECT s3_key, fps FROM media_assets WHERE id = ?", c.get("media_id"));
        if (media.isEmpty()) {
            jdbc.update("DELETE FROM media_playout_copies WHERE id = ?", id);
            return true;
        }
        String original = (String) media.getFirst().get("s3_key");
        Double sourceFps = media.getFirst().get("fps") == null ? null : ((Number) media.getFirst().get("fps")).doubleValue();
        int w = ((Number) c.get("canvas_width")).intValue();
        int h = ((Number) c.get("canvas_height")).intValue();
        int fps = ((Number) c.get("fps")).intValue();

        String key = null;
        String reason = null;
        long started = System.nanoTime();
        Path out = null;
        try {
            out = Files.createTempFile("playout-", ".mp4");
            List<String> cmd = command(storage.readLocation(original), out, w, h, fps, sourceFps);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0 || Files.size(out) == 0) {
                reason = log.isBlank() ? "FFmpeg exited with " + p.exitValue() : last(log, 500);
            } else {
                try (InputStream in = Files.newInputStream(out)) {
                    key = storage.uploadFile(in, "playout-" + w + "x" + h + "-" + fps + ".mp4", Files.size(out));
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
        log.info("Playout copy {} of media {} at {}x{} {} fps: {} in {} s{}", id, c.get("media_id"), w, h, fps,
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
