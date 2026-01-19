package com.afklive.streamer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class VideoConversionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VideoConversionService.class);

    private final ConcurrentHashMap<String, Integer> conversionProgress = new ConcurrentHashMap<>();

    @Async
    public void convertVideo(Path userDir, String username, String fileName) {
        try {
            fileName = fileName.replace(".mp4", "_raw.mp4");
            Path source = userDir.resolve(fileName);
            String targetFileName = fileName.replace("_raw", "");

            Path target = userDir.resolve(targetFileName);

            List<String> command = FFmpegCommandBuilder.buildConversionCommand(source, target);
            String progressKey = username + ":" + targetFileName;

            log.info("Starting conversion for {}: {}", username, targetFileName);
            conversionProgress.put(progressKey, 0);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Conversion completed successfully for {}: {}", username, fileName);
                conversionProgress.remove(progressKey);
                Files.delete(source);
            } else {
                log.error("Conversion failed for {}: {} (exit code {})", username, fileName, exitCode);
                conversionProgress.put(progressKey, -1);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Conversion error for {}: {} - {}", username, fileName, e.getMessage(), e);
            conversionProgress.put(username + ":" + fileName, -1);
            Thread.currentThread().interrupt();
        }
    }

    @Async
    public void convertToShort(Path userDir, String username, String fileName) {
        try {
            // Assume fileName exists
            Path source = userDir.resolve(fileName);
            String targetFileName = "short_" + fileName;
            Path target = userDir.resolve(targetFileName);

            List<String> command = FFmpegCommandBuilder.buildConvertToShortCommand(source, target);
            String progressKey = username + ":" + targetFileName;

            log.info("Starting Shorts conversion for {}: {}", username, targetFileName);
            conversionProgress.put(progressKey, 0);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(System.out); // Log output

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Shorts conversion completed: {}", targetFileName);
                conversionProgress.put(progressKey, 100);
            } else {
                log.error("Shorts conversion failed (code {})", exitCode);
                conversionProgress.put(progressKey, -1);
            }
        } catch (Exception e) {
            log.error("Shorts conversion error", e);
        }
    }

    @Async
    public void optimizeVideo(Path userDir, String username, String fileName) {
        try {
            Path source = userDir.resolve(fileName);
            if (!Files.exists(source)) {
                log.error("Source file not found for optimization: {}", source);
                return;
            }

            // Target: video_optimized.mp4
            String baseName = fileName.toLowerCase().endsWith(".mp4") ? fileName.substring(0, fileName.length() - 4) : fileName;
            String targetFileName = baseName + "_optimized.mp4";
            Path target = userDir.resolve(targetFileName);

            List<String> command = FFmpegCommandBuilder.buildOptimizeCommand(source, target);

            // Key for progress tracking. We use a prefix to distinguish from normal conversion if needed,
            // but for simplicity in getProgress, we might want to pass the target filename or a special key.
            // Let's use "optimize:" + fileName
            String progressKey = username + ":optimize:" + fileName;

            log.info("Starting optimization for {}: {}", username, fileName);
            conversionProgress.put(progressKey, 0);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(System.out); // Log output to avoid blocking

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Optimization completed: {}", targetFileName);
                conversionProgress.put(progressKey, 100);
            } else {
                log.error("Optimization failed (code {})", exitCode);
                conversionProgress.put(progressKey, -1);
            }
        } catch (Exception e) {
            log.error("Optimization error", e);
            conversionProgress.put(username + ":optimize:" + fileName, -1);
        }
    }

    public Optional<Integer> getProgress(String username, String fileName) {
        String progressKey = username + ":" + fileName;

        return Optional.ofNullable(this.conversionProgress.get(progressKey));
    }
}