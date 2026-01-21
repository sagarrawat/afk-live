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
import java.util.concurrent.atomic.AtomicReference;
import java.util.Comparator;
import java.util.stream.Stream;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.util.AppConstants;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoConversionService {

    private final FileStorageService storageService;
    private final ConcurrentHashMap<String, Integer> conversionProgress = new ConcurrentHashMap<>();
    private final UserService userService;
    private final ScheduledVideoRepository repository;

    @Async
    public void convertVideo(Path userDir, String username, String fileName) {
        ScheduledVideo scheduledVideo = repository.findByUsernameAndTitle(username, fileName)
                .orElse(null);

        if (scheduledVideo == null) {
            log.error("Video not found for conversion: {}", fileName);
            return;
        }

        scheduledVideo.setOptimizationStatus(ScheduledVideo.OptimizationStatus.IN_PROGRESS);
        repository.save(scheduledVideo);

        Path tempDir = null;
        String progressKey = username + ":" + fileName;

        try {
            tempDir = Files.createTempDirectory("convert_" + username + "_");
            String sourceKey = scheduledVideo.getS3Key();
            long oldSize = scheduledVideo.getFileSize() != null ? scheduledVideo.getFileSize() : 0;

            // Updated Log Message to confirm new code running
            log.info("Starting S3-compatible conversion for {}: sourceKey={}", username, sourceKey);
            conversionProgress.put(progressKey, 0);

            // Download source
            Path sourcePath = tempDir.resolve("source.mp4");
            storageService.downloadFileToPath(sourceKey, sourcePath);

            Path targetPath = tempDir.resolve("optimized.mp4");

            // Conversion
            List<String> command = FFmpegCommandBuilder.buildConversionCommand(sourcePath, targetPath);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // process.getInputStream().transferTo(System.out); // Optional logging

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Conversion completed successfully for {}: {}", username, fileName);

                // Upload optimized file
                long newSize = Files.size(targetPath);

                String newKey = storageService.uploadFile(Files.newInputStream(targetPath), fileName, newSize);

                // Delete old file
                try {
                    storageService.deleteFile(sourceKey);
                } catch (Exception e) {
                    log.warn("Failed to delete old file {}: {}", sourceKey, e.getMessage());
                }

                conversionProgress.remove(progressKey);

                // Update DB
                scheduledVideo.setS3Key(newKey);
                scheduledVideo.setFileSize(newSize);
                scheduledVideo.setOptimizationStatus(ScheduledVideo.OptimizationStatus.COMPLETED);

                userService.updateStorageUsage(username, newSize - oldSize);

                repository.save(scheduledVideo);

            } else {
                log.error("Conversion failed for {}: {} (exit code {})", username, fileName, exitCode);
                conversionProgress.put(progressKey, -1);
                scheduledVideo.setOptimizationStatus(ScheduledVideo.OptimizationStatus.FAILED);
                repository.save(scheduledVideo);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Conversion error for {}: {} - {}", username, fileName, e.getMessage(), e);
            conversionProgress.put(progressKey, -1);
            scheduledVideo.setOptimizationStatus(ScheduledVideo.OptimizationStatus.FAILED);
            repository.save(scheduledVideo);
            Thread.currentThread().interrupt();
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    @Async
    public void convertToShort(Path userDir, String username, String fileName) {
        ScheduledVideo scheduledVideo = repository.findByUsernameAndTitle(username, fileName)
                .orElse(null);

        if (scheduledVideo == null) {
            log.error("Video not found for shorts conversion: {}", fileName);
            return;
        }

        String targetFileName = "short_" + fileName;
        String progressKey = username + ":" + targetFileName;
        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("shorts_" + username + "_");
            String sourceKey = scheduledVideo.getS3Key();

            log.info("Starting Shorts conversion for {}: sourceKey={}", username, sourceKey);
            conversionProgress.put(progressKey, 0);

            // Download
            Path sourcePath = tempDir.resolve("source.mp4");
            storageService.downloadFileToPath(sourceKey, sourcePath);

            Path targetPath = tempDir.resolve("short.mp4");

            List<String> command = FFmpegCommandBuilder.buildConvertToShortCommand(sourcePath, targetPath);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(System.out); // Log output

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Shorts conversion completed: {}", targetFileName);

                long newSize = Files.size(targetPath);
                userService.checkStorageQuota(username, newSize);

                String newKey = storageService.uploadFile(Files.newInputStream(targetPath), targetFileName, newSize);
                userService.updateStorageUsage(username, newSize);

                // Create new DB entry
                ScheduledVideo newVideo = new ScheduledVideo();
                newVideo.setUsername(username);
                newVideo.setTitle(targetFileName);
                newVideo.setS3Key(newKey);
                newVideo.setFileSize(newSize);
                newVideo.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
                newVideo.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);

                repository.save(newVideo);

                conversionProgress.put(progressKey, 100);
            } else {
                log.error("Shorts conversion failed (code {})", exitCode);
                conversionProgress.put(progressKey, -1);
            }
        } catch (Exception e) {
            log.error("Shorts conversion error", e);
            conversionProgress.put(progressKey, -1);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    @Async
    public void optimizeVideo(Path userDir, String username, String fileName) {
        // Prevent path traversal
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
             log.error("Invalid filename for optimization: {}", fileName);
             return;
        }

        try {
            Path source = userDir.resolve(fileName);
            if (!Files.exists(source)) {
                // Check for _raw variant (common upload pattern)
                String rawName = fileName.replace(".mp4", "_raw.mp4");
                Path rawSource = userDir.resolve(rawName);
                if (Files.exists(rawSource)) {
                    log.info("Found raw source file: {}", rawSource);
                    source = rawSource;
                } else {
                    log.warn("Source file not found locally: {}. Attempting download from storage.", source.toAbsolutePath());
                    try {
                        storageService.downloadFileToPath(fileName, source);
                        log.info("Downloaded file from storage: {}", fileName);
                    } catch (Exception e) {
                        log.error("Failed to find file locally or in storage: {}", fileName, e);
                        return;
                    }
                }
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

    @Async
    public void mergeVideosAsync(List<ScheduledVideo> sourceVideos, String username, String outputName) {
        Path tempDir = null;
        String progressKey = username + ":" + outputName;
        conversionProgress.put(progressKey, 0);

        try {
            tempDir = Files.createTempDirectory("merge_");
            List<Path> inputs = new java.util.ArrayList<>();

            // Download
            for (ScheduledVideo v : sourceVideos) {
                 Path tempFile = tempDir.resolve(v.getId() + "_" + v.getTitle().replaceAll("[^a-zA-Z0-9.-]", "_"));
                 try (InputStream is = storageService.downloadFile(v.getS3Key())) {
                     Files.copy(is, tempFile);
                 }
                 inputs.add(tempFile);
            }

            Path output = tempDir.resolve("merged.mp4");
            List<String> command = FFmpegCommandBuilder.buildMergeCommand(inputs, output);

            log.info("Starting merge for {}: {} -> {}", username, inputs.size(), outputName);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(System.out); // Log

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                 log.error("Merge failed (code {})", exitCode);
                 conversionProgress.put(progressKey, -1);
                 return;
            }

            // Upload
            long size = Files.size(output);
            userService.checkStorageQuota(username, size);
            String s3Key = storageService.uploadFile(Files.newInputStream(output), outputName, size);
            userService.updateStorageUsage(username, size);

            // DB
            ScheduledVideo video = new ScheduledVideo();
            video.setUsername(username);
            video.setTitle(outputName);
            video.setS3Key(s3Key);
            video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
            video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
            video.setFileSize(size);
            repository.save(video);

            log.info("Merge completed: {}", outputName);
            conversionProgress.put(progressKey, 100);

        } catch (Exception e) {
            log.error("Merge failed", e);
            conversionProgress.put(progressKey, -1);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir != null) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
    }
}
