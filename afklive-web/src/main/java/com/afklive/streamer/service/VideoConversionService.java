package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.util.AppConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Service
@Slf4j
public class VideoConversionService {

    private final FileStorageService storageService;
    private final UserService userService;
    private final ScheduledVideoRepository repository;
    private final Cache<String, Integer> conversionProgress = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();
    private final Set<String> activeOptimizations = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final LambdaClient lambdaClient;
    private final String lambdaFunctionName;

    public VideoConversionService(
            FileStorageService storageService,
            UserService userService,
            ScheduledVideoRepository repository,
            @Value("${app.aws.access-key:}") String awsAccessKey,
            @Value("${app.aws.secret-key:}") String awsSecretKey,
            @Value("${app.aws.region:us-east-1}") String awsRegion,
            @Value("${app.aws.function-name:}") String lambdaFunctionName) {
        this.storageService = storageService;
        this.userService = userService;
        this.repository = repository;
        this.lambdaFunctionName = lambdaFunctionName;

        if (awsAccessKey != null && !awsAccessKey.isEmpty() && awsSecretKey != null && !awsSecretKey.isEmpty()) {
            this.lambdaClient = LambdaClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(awsAccessKey, awsSecretKey)))
                    .httpClientBuilder(ApacheHttpClient.builder()
                            .socketTimeout(Duration.ofMinutes(10))     // Wait 10 mins for data
                            .connectionTimeout(Duration.ofSeconds(10)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofMinutes(10))        // Total time allowed for the whole operation
                            .apiCallAttemptTimeout(Duration.ofMinutes(10)) // Time allowed for a single try
                            .build())
                    .build();
        } else {
            this.lambdaClient = null;
        }
    }

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

        // Try Lambda Optimization first
        if (lambdaClient != null && lambdaFunctionName != null && !lambdaFunctionName.isEmpty()) {
            try {
                log.info("Attempting optimization via Lambda for {}: {}", username, fileName);

                String finalS3Key = UUID.randomUUID() + "_" + targetTitle;

                Map<String, String> payload = new HashMap<>();
                payload.put("file_name", scheduledVideo.getS3Key());
                payload.put("mode", mode);
                payload.put("height", String.valueOf(height));
                payload.put("username", username);
                payload.put("output_key", finalS3Key);

                String jsonPayload = objectMapper.writeValueAsString(payload);

                InvokeRequest request = InvokeRequest.builder()
                        .functionName(lambdaFunctionName)
                        .payload(SdkBytes.fromUtf8String(jsonPayload))
                        .build();

                InvokeResponse response = lambdaClient.invoke(request);
                String responseString = response.payload().asUtf8String();

                // Parse response
                JsonNode responseNode = objectMapper.readTree(responseString);

                if (responseNode.has("status") && "success".equals(responseNode.get("status").asText())) {
                    String optimizedKey = responseNode.get("optimizedKey").asText();
                    long fileSize = responseNode.get("fileSize").asLong();

                    log.info("Lambda optimization successful. New key: {}", optimizedKey);

                    userService.checkStorageQuota(username, fileSize);
                    userService.updateStorageUsage(username, fileSize);

                    // Create NEW DB Entry
                    ScheduledVideo newVideo = new ScheduledVideo();
                    newVideo.setUsername(username);
                    newVideo.setTitle(targetTitle);
                    newVideo.setS3Key(optimizedKey);
                    newVideo.setFileSize(fileSize);
                    newVideo.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
                    newVideo.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
                    newVideo.setOptimizationStatus(ScheduledVideo.OptimizationStatus.COMPLETED);
                    newVideo.setCategoryId(scheduledVideo.getCategoryId());
                    newVideo.setTags(scheduledVideo.getTags());

                    repository.save(newVideo);

                    conversionProgress.put(progressKey, 100);
                    activeOptimizations.remove(lockKey);
                    return;
                } else {
                    log.warn("Lambda optimization returned error or non-success status: {}", responseString);
                    // Fallback to local processing
                }

            } catch (Exception e) {
                log.error("Lambda invocation failed, falling back to local optimization", e);
                // Fallback to local processing
            }
        } else {
            log.info("Lambda not configured, using local optimization for {}", fileName);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("optimize_" + username + "_");
            String sourceKey = scheduledVideo.getS3Key();

            log.info("Starting local optimization for {}: {} -> {} ({}p)", username, fileName, mode, height);

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
        return Optional.ofNullable(this.conversionProgress.getIfPresent(progressKey));
    }

    @Async
    public void checkOptimizationRequirement(Long videoId) {
        log.info("Checking optimization requirement for video ID: {}", videoId);
        ScheduledVideo video = repository.findById(videoId).orElse(null);
        if (video == null) {
            log.error("Video not found for optimization check: {}", videoId);
            return;
        }

        if (video.getS3Key() == null) {
            log.warn("Video has no S3 key, skipping check: {}", videoId);
            return;
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("check_" + videoId + "_");
            Path tempFile = tempDir.resolve("check.mp4");

            // Download from S3
            storageService.downloadFileToPath(video.getS3Key(), tempFile);

            List<String> command = FFmpegCommandBuilder.buildProbeCommand(tempFile);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

            String output = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine(); // We expect just the codec name
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output != null) {
                String codec = output.trim();
                log.info("Detected codec for video {}: {}", videoId, codec);

                if ("h264".equalsIgnoreCase(codec)) {
                    log.info("Video {} is already H.264. Marking as optimized.", videoId);
                    video.setOptimizationStatus(ScheduledVideo.OptimizationStatus.COMPLETED);
                    repository.save(video);
                } else {
                    log.info("Video {} is {}, optimization required.", videoId, codec);
                    // Leave as NOT_OPTIMIZED (default)
                }
            } else {
                log.error("Failed to probe video {}. Exit code: {}", videoId, exitCode);
            }

        } catch (Exception e) {
            log.error("Error checking optimization requirement for video {}", videoId, e);
        } finally {
            cleanupTempDir(tempDir);
        }
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
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }
}
