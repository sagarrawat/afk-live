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
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStreamReader;

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
    private final Set<String> activeOptimizations = ConcurrentHashMap.newKeySet();
    private final UserService userService;
    private final ScheduledVideoRepository repository;

    @Async
    public void convertVideo(Path userDir, String username, String fileName) {
        // ... kept for legacy, redirects to optimize default
        optimizeVideo(userDir, username, fileName, "landscape", 1080);
    }

    @Async
    public void convertToShort(Path userDir, String username, String fileName) {
        // Reuse new optimize logic with portrait settings
        optimizeVideo(userDir, username, fileName, "portrait", 1920);
    }

    @Async
    public void optimizeVideo(Path userDir, String username, String fileName, String mode, int height) {
        String lockKey = username + ":" + fileName;
        if (!activeOptimizations.add(lockKey)) {
            log.warn("Optimization already in progress for {}: {}", username, fileName);
            return;
        }

        ScheduledVideo scheduledVideo = repository.findByUsernameAndTitle(username, fileName)
                .orElse(null);

        if (scheduledVideo == null) {
            log.error("Video not found for optimization: {}", fileName);
            activeOptimizations.remove(lockKey);
            return;
        }

        String targetSuffix = String.format("_%s_%dp", mode, height);
        // Clean up title if it already has extension
        String baseTitle = fileName.lastIndexOf('.') > 0 ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String targetTitle = baseTitle + targetSuffix + ".mp4";

        String progressKey = username + ":" + fileName;
        conversionProgress.put(progressKey, 0);

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("optimize_" + username + "_");
            String sourceKey = scheduledVideo.getS3Key();

            log.info("Starting optimization for {}: {} -> {} ({}p)", username, fileName, mode, height);

            Path sourcePath = tempDir.resolve("source.mp4");
            storageService.downloadFileToPath(sourceKey, sourcePath);

            Path targetPath = tempDir.resolve("optimized.mp4");

            List<String> command = FFmpegCommandBuilder.buildOptimizeCommand(sourcePath, targetPath, mode, height);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[FFmpeg Optimize] {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Optimization completed for {}", fileName);

                long newSize = Files.size(targetPath);
                userService.checkStorageQuota(username, newSize);

                String newKey = storageService.uploadFile(Files.newInputStream(targetPath), targetTitle, newSize);

                userService.updateStorageUsage(username, newSize);

                // Create NEW DB Entry
                ScheduledVideo newVideo = new ScheduledVideo();
                newVideo.setUsername(username);
                newVideo.setTitle(targetTitle);
                newVideo.setS3Key(newKey);
                newVideo.setFileSize(newSize);
                newVideo.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
                newVideo.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
                newVideo.setOptimizationStatus(ScheduledVideo.OptimizationStatus.COMPLETED);
                // Inherit category/tags? Maybe
                newVideo.setCategoryId(scheduledVideo.getCategoryId());
                newVideo.setTags(scheduledVideo.getTags());

                repository.save(newVideo);

                conversionProgress.put(progressKey, 100);
            } else {
                log.error("Optimization failed (code {})", exitCode);
                conversionProgress.put(progressKey, -1);
            }

        } catch (Exception e) {
            log.error("Optimization error", e);
            conversionProgress.put(progressKey, -1);
        } finally {
            cleanupTempDir(tempDir);
            activeOptimizations.remove(lockKey);
        }
    }

    public Optional<Integer> getProgress(String username, String fileName) {
        String progressKey = username + ":" + fileName;
        // Also check if optimized version exists? No, just track progress
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

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[FFmpeg Merge] {}", line);
                }
            }

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
