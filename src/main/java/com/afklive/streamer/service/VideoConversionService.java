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

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.util.AppConstants;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class VideoConversionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VideoConversionService.class);

    private final ConcurrentHashMap<String, Integer> conversionProgress = new ConcurrentHashMap<>();
    private final FileStorageService storageService;
    private final UserService userService;
    private final ScheduledVideoRepository repository;

    @Async
    public void convertVideo(Path userDir, String username, String fileName) {
        try {
            // Replicate FileUploadService logic to find the source file path
            String sourceFileName = fileName;
            if (fileName != null && fileName.contains(".mp4")) {
                int dotIndex = fileName.lastIndexOf(".");
                sourceFileName = fileName.substring(0, dotIndex) + "_raw" + fileName.substring(dotIndex);
            }

            Path source = userDir.resolve(sourceFileName);
            Path target = userDir.resolve(fileName); // Target is the original requested name

            List<String> command = FFmpegCommandBuilder.buildConversionCommand(source, target);
            String progressKey = username + ":" + fileName;

            log.info("Starting conversion for {}: source={} target={}", username, sourceFileName, fileName);
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
                 Path tempFile = tempDir.resolve(v.getTitle());
                 try (InputStream is = storageService.downloadFile(v.getS3Key())) {
                     Files.copy(is, tempFile);
                 }
                 inputs.add(tempFile);
            }

            Path output = tempDir.resolve(outputName);
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
            if (tempDir != null) {
                try {
                    Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.delete(p); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }
        }
    }
}