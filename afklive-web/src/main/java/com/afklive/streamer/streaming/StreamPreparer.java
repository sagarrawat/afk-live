package com.afklive.streamer.streaming;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.AudioService;
import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.MediaProbe;
import com.afklive.streamer.util.SafePaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Gets a stream ready to run on this worker: downloads the video, music and watermark into a
 * per-job folder, probes the video, and builds the encoder and relay commands.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StreamPreparer {

    private final FileStorageService storageService;
    private final AudioService audioService;
    private final MediaProbe mediaProbe;
    private final ScheduledVideoRepository scheduledVideoRepository;

    @Value("${app.streaming.work-dir:data/worker}")
    private String workDirRoot = "data/worker";

    /** What a prepared job runs. {@code overlayFile} is the text file an overlay updater writes, or null. */
    public record Prepared(Path workDir, List<String> encoderCommand, List<List<String>> relayCommands, Path overlayFile) {
    }

    public Prepared prepare(long jobId, StreamSpec spec) throws IOException {
        Path workDir = SafePaths.resolveInside(Paths.get(workDirRoot), "job-" + jobId);
        Files.createDirectories(workDir);

        Path video = workDir.resolve("video");
        if (!Files.exists(video)) {
            storageService.downloadFileToPath(spec.videoKey(), video);
        }

        Path music = null;
        if (spec.musicKey() != null) {
            if (spec.musicKey().startsWith("stock:")) {
                music = audioService.getAudioPath(spec.musicKey().substring(6));
            } else {
                music = workDir.resolve("music");
                if (!Files.exists(music)) {
                    storageService.downloadFileToPath(spec.musicKey(), music);
                }
            }
        }

        Path watermark = null;
        if (spec.watermarkKey() != null) {
            watermark = workDir.resolve("watermark.png");
            if (!Files.exists(watermark)) {
                storageService.downloadFileToPath(spec.watermarkKey(), watermark);
            }
        }

        Path overlay = null;
        if (spec.overlayEnabled()) {
            overlay = workDir.resolve("overlay.txt");
            Files.writeString(overlay, "Subs: Loading...");
        }

        String mode = spec.streamMode() == null ? "original" : spec.streamMode();
        MediaProbe.MediaInfo info = mediaProbe.probe(video);

        // Copy (no re-encode) only for a file normalized at upload, in H.264, within the plan cap, with nothing drawn on it.
        boolean copy = false;
        if ("original".equals(mode) && watermark == null && music == null && overlay == null
                && "h264".equals(info.videoCodec()) && info.height() > 0 && info.height() <= spec.maxHeight()) {
            copy = scheduledVideoRepository.findFirstByUserEmailAndS3Key(spec.username(), spec.videoKey())
                    .map(v -> v.getOptimizationStatus() == ScheduledVideo.OptimizationStatus.COMPLETED)
                    .orElse(false);
        }
        int outputHeight = ("original".equals(mode) && info.height() > 0) ? Math.min(info.height(), spec.maxHeight()) : spec.maxHeight();

        List<String> encoder = FFmpegCommandBuilder.buildEncoderToPipeCommand(video, music, spec.musicVolume(),
                spec.loopCount(), watermark, spec.muteVideoAudio(), mode, outputHeight, copy, overlay, info.hasAudio());
        List<List<String>> relays = spec.destinations().stream()
                .map(d -> FFmpegCommandBuilder.buildRelayCommand(d.url()))
                .toList();
        log.info("Prepared job {}: {} destinations, copy={}, height={}", jobId, relays.size(), copy, outputHeight);
        return new Prepared(workDir, encoder, relays, overlay);
    }

    /** Removes the job's downloaded files. */
    public void cleanup(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(workDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("Cleanup of {} failed", workDir, e);
        }
    }
}
