package com.afklive.streamer.service;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.repository.MediaAssetRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ML-2: checks every new library file and records what playout needs. Videos and audio are probed
 * with ffprobe straight from storage (ranged reads, no full download); a file that can't be played is
 * marked Failed with a plain-language reason. Videos get a thumbnail from a frame a few seconds in;
 * images must be readable by the overlay renderer and get a scaled thumbnail.
 * <p>
 * Files are not re-encoded here: the studio decodes each file and encodes one output at a fixed frame
 * rate (see StudioBuilder), so variable frame rates and odd codecs are normalised at playout and a
 * second, stored copy would only cost storage and CPU.
 * <p>
 * Work is claimed with a conditional update, so any number of web instances can run this; a claim
 * older than {@link #STALE_CLAIM} (the instance died mid-way) is taken over by the sweeper.
 */
@Service
@Slf4j
public class MediaProcessor {

    static final Duration STALE_CLAIM = Duration.ofMinutes(10);
    static final int THUMB_WIDTH = 320;
    static final int MAX_HEIGHT = 4320;
    static final double MAX_DURATION_SECONDS = 24 * 3600;

    private final MediaAssetRepository assets;
    private final FileStorageService storage;
    private final MediaProbe probe;
    private final String role;
    private final boolean autoProcess;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "media-processor");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "media-sweeper");
        t.setDaemon(true);
        return t;
    });

    public MediaProcessor(MediaAssetRepository assets, FileStorageService storage, MediaProbe probe,
                          @Value("${app.streaming.role:all}") String role,
                          @Value("${app.media.auto-process:true}") boolean autoProcess) {
        this.autoProcess = autoProcess;
        this.assets = assets;
        this.storage = storage;
        this.probe = probe;
        this.role = role;
    }

    /** Processes the file soon: after the current transaction commits, or right away outside one. */
    public void enqueue(Long assetId) {
        if (!autoProcess) {
            return; // tests call process()/sweep() themselves
        }
        Runnable submit = () -> pool.submit(() -> {
            try {
                process(assetId);
            } catch (Exception e) {
                log.error("Processing media {} failed", assetId, e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSweeper() {
        if ("worker".equalsIgnoreCase(role) || !autoProcess) {
            return;
        }
        sweeper.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        sweeper.shutdownNow();
        pool.shutdownNow();
    }

    /** Picks up files left in Processing (never claimed, or claimed by an instance that died). */
    public void sweep() {
        try {
            ZonedDateTime stale = now().minus(STALE_CLAIM);
            for (Long id : assets.findProcessable(stale)) {
                process(id);
            }
            backfillLoudness();
            backfillStill();
        } catch (Exception e) {
            log.error("Media sweep failed", e);
        }
    }

    /** Claims and processes one file; returns false when another instance has it or it's done. */
    public boolean process(Long assetId) {
        ZonedDateTime now = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); // compared again on finish
        if (assets.claimProcessing(assetId, now, now.minus(STALE_CLAIM)) != 1) {
            return false;
        }
        MediaAsset a = assets.findById(assetId).orElse(null);
        if (a == null) {
            return false;
        }
        Result r;
        try {
            r = switch (a.getContentType()) {
                case VIDEO -> video(a);
                case AUDIO -> audio(a);
                case IMAGE -> image(a);
            };
        } catch (Exception e) {
            log.warn("Could not process media {} ({})", assetId, a.getS3Key(), e);
            r = Result.failed("We couldn't read this file. Try uploading it again.");
        }
        int saved = assets.finishProcessing(assetId, now, r.status, r.reason, r.duration, r.width, r.height, r.codec, r.hasAudio,
                r.fps, r.thumbnailKey);
        if (saved != 1) {
            // Taken over or deleted meanwhile: the other run's result stands.
            if (r.thumbnailKey != null) {
                deleteQuietly(r.thumbnailKey);
            }
            return false;
        }
        log.info("Media {} processed: {}{}", assetId, r.status, r.reason == null ? "" : " (" + r.reason + ")");
        if (r.status == MediaStatus.READY && Boolean.TRUE.equals(r.hasAudio)) {
            measureLoudness(assetId, a.getS3Key());
        }
        if (r.status == MediaStatus.READY && a.getContentType() == MediaAsset.ContentType.VIDEO && r.duration != null) {
            measureStill(assetId, a.getS3Key(), r.duration);
        }
        return true;
    }

    /** AU-5: how loud the file is, so playout can bring every track to the same level. */
    void measureLoudness(Long assetId, String key) {
        Double lufs = null;
        try {
            lufs = probe.loudness(storage.readLocation(key));
        } catch (Exception e) {
            log.warn("Loudness of media {} couldn't be measured: {}", assetId, e.getMessage());
        }
        assets.setLoudness(assetId, lufs);
    }

    /** AU-5: files uploaded before loudness was measured, a couple per sweep. */
    void backfillLoudness() {
        for (Long id : assets.findNeedingLoudness(org.springframework.data.domain.PageRequest.of(0, 2))) {
            assets.findById(id).ifPresent(a -> measureLoudness(id, a.getS3Key()));
        }
    }

    /** GR-9: whether the video is one still picture start to end (null when it couldn't be measured). */
    void measureStill(Long assetId, String key, double duration) {
        Boolean still = null;
        try {
            still = probe.stillPicture(storage.readLocation(key), duration);
        } catch (Exception e) {
            log.warn("Media {} couldn't be checked for a still picture: {}", assetId, e.getMessage());
        }
        assets.setStillPicture(assetId, still);
    }

    /** GR-9: videos processed before the still check, a couple per sweep (videos in a playlist first). */
    void backfillStill() {
        for (Long id : assets.findNeedingStillCheck(org.springframework.data.domain.PageRequest.of(0, 2))) {
            assets.findById(id).ifPresent(a -> {
                if (a.getDurationSeconds() == null || a.getDurationSeconds() <= 0) {
                    assets.setStillPicture(id, null);
                } else {
                    measureStill(id, a.getS3Key(), a.getDurationSeconds());
                }
            });
        }
    }

    record Result(MediaStatus status, String reason, Double duration, Integer width, Integer height, String codec,
                  Boolean hasAudio, Double fps, String thumbnailKey) {
        static Result failed(String reason) {
            return new Result(MediaStatus.FAILED, reason, null, null, null, null, null, null, null);
        }
    }

    private Result video(MediaAsset a) {
        String where = storage.readLocation(a.getS3Key());
        MediaProbe.MediaInfo info = withMeasuredDuration(probe.probe(where), where);
        String problem = videoProblem(info);
        if (problem != null) {
            return Result.failed(problem);
        }
        String thumb = null;
        try {
            byte[] jpeg = videoFrame(where, Math.min(3.0, info.durationSeconds() / 2));
            if (jpeg != null) {
                thumb = storage.uploadFile(new ByteArrayInputStream(jpeg), "thumb.jpg", jpeg.length);
            }
        } catch (Exception e) {
            log.warn("No thumbnail for media {}", a.getId(), e);
        }
        return new Result(MediaStatus.READY, null, info.durationSeconds(), info.width(), info.height(), info.videoCodec(),
                info.hasAudio(), info.fps() > 0 ? info.fps() : null, thumb);
    }

    /** Why a probed video can't be streamed, in words a creator understands; null when it's fine. */
    static String videoProblem(MediaProbe.MediaInfo info) {
        if (info == MediaProbe.MediaInfo.UNKNOWN || info.hasAudio() == null) {
            return "This file couldn't be read as a video. It may be damaged or in a format we don't support; MP4 (H.264) works best.";
        }
        if (info.videoCodec() == null || info.width() <= 0 || info.height() <= 0) {
            return info.hasAudio() ? "This file has sound but no picture. Upload it as audio, or add a video track."
                    : "This file has no video track.";
        }
        if (info.height() > MAX_HEIGHT || info.width() > MAX_HEIGHT * 2) {
            return "This video is larger than 8K (" + info.width() + "x" + info.height() + "). Export it at 4K or lower.";
        }
        if (info.durationSeconds() <= 0.2) {
            return "This video is empty or too short to stream.";
        }
        if (info.durationSeconds() > MAX_DURATION_SECONDS) {
            return "This video is longer than 24 hours. Split it into shorter files; a playlist plays them back to back.";
        }
        return null;
    }

    /** Recordings from browsers and some apps don't write their length in the header: measure it. */
    private MediaProbe.MediaInfo withMeasuredDuration(MediaProbe.MediaInfo info, String where) {
        if (info == MediaProbe.MediaInfo.UNKNOWN || info.durationSeconds() > 0.2) {
            return info;
        }
        double measured = probe.measureDuration(where);
        return measured <= 0 ? info : new MediaProbe.MediaInfo(info.videoCodec(), info.width(), info.height(), info.fps(),
                info.hasAudio(), measured);
    }

    private Result audio(MediaAsset a) {
        String where = storage.readLocation(a.getS3Key());
        MediaProbe.MediaInfo info = withMeasuredDuration(probe.probe(where), where);
        if (info == MediaProbe.MediaInfo.UNKNOWN || !Boolean.TRUE.equals(info.hasAudio())) {
            return Result.failed("This file has no sound we can read. MP3, M4A, WAV, OGG and FLAC work.");
        }
        if (info.durationSeconds() <= 0.2) {
            return Result.failed("This audio file is empty.");
        }
        return new Result(MediaStatus.READY, null, info.durationSeconds(), null, null, null, true, null, null);
    }

    private Result image(MediaAsset a) throws IOException {
        BufferedImage img;
        try (InputStream in = storage.downloadFile(a.getS3Key())) {
            img = ImageIO.read(in);
        }
        if (img == null) {
            return Result.failed("This image format isn't supported. Use JPG, PNG or GIF.");
        }
        if (img.getWidth() > 8192 || img.getHeight() > 8192) {
            return Result.failed("This image is too large (" + img.getWidth() + "x" + img.getHeight() + "). Use 4096 px or smaller.");
        }
        byte[] jpeg = thumbnail(img);
        String thumb = storage.uploadFile(new ByteArrayInputStream(jpeg), "thumb.jpg", jpeg.length);
        return new Result(MediaStatus.READY, null, null, img.getWidth(), img.getHeight(), null, null, null, thumb);
    }

    static byte[] thumbnail(BufferedImage src) throws IOException {
        int w = Math.min(THUMB_WIDTH, src.getWidth());
        int h = Math.max(1, (int) Math.round(src.getHeight() * (w / (double) src.getWidth())));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, java.awt.Color.BLACK, null);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bytes);
        return bytes.toByteArray();
    }

    /** One scaled frame as JPEG, or null if FFmpeg couldn't produce one in time. */
    private static byte[] videoFrame(String input, double atSeconds) throws IOException, InterruptedException {
        Path out = Files.createTempFile("thumb-", ".jpg");
        try {
            List<String> cmd = new ArrayList<>(List.of(FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-loglevel", "error", "-y",
                    "-ss", String.format(Locale.ROOT, "%.2f", Math.max(0, atSeconds)), "-i", input,
                    "-frames:v", "1", "-vf", "scale=" + THUMB_WIDTH + ":-2", "-q:v", "4", out.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || Files.size(out) == 0) {
                return null;
            }
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private void deleteQuietly(String key) {
        try {
            storage.deleteFile(key);
        } catch (Exception e) {
            log.warn("Could not delete {}", key, e);
        }
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }
}
