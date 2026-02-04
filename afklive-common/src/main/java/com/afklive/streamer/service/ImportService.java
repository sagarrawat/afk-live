package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.util.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private final FileStorageService storageService;
    private final ScheduledVideoRepository repository;
    private final UserService userService;

    @Async
    public CompletableFuture<Boolean> downloadFromYouTube(String url, String username) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("yt_import_");
            String uniqueId = UUID.randomUUID().toString();
            // Output template: temp_dir/uuid_title.ext
            String outputTemplate = tempDir.resolve(uniqueId + "_%(title)s.%(ext)s").toString();

            // Determine yt-dlp path
            String ytDlpPath = new File("bin/yt-dlp").getAbsolutePath();
            boolean localExists = new File(ytDlpPath).exists();
            if (!localExists) {
                ytDlpPath = "yt-dlp"; // Try system path
            }

            log.info("Using yt-dlp at: {}", ytDlpPath);
            log.info("Working Directory: {}", System.getProperty("user.dir"));

            ProcessBuilder pb;
            String ffmpegPath = new File("bin/ffmpeg").getAbsolutePath();
            boolean hasLocalFfmpeg = new File(ffmpegPath).exists();

            if (localExists) {
                // If using local script, invoke with python3 explicitly
                if (hasLocalFfmpeg) {
                    pb = new ProcessBuilder(
                            "python3",
                            ytDlpPath,
                            "--ffmpeg-location", ffmpegPath,
                            "--no-playlist",
                            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                            "-o", outputTemplate,
                            url
                    );
                } else {
                    pb = new ProcessBuilder(
                            "python3",
                            ytDlpPath,
                            "--no-playlist",
                            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                            "-o", outputTemplate,
                            url
                    );
                }
            } else {
                // System command
                if (hasLocalFfmpeg) {
                    pb = new ProcessBuilder(
                            ytDlpPath,
                            "--ffmpeg-location", ffmpegPath,
                            "--no-playlist",
                            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                            "-o", outputTemplate,
                            url
                    );
                } else {
                    pb = new ProcessBuilder(
                            ytDlpPath,
                            "--no-playlist",
                            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                            "-o", outputTemplate,
                            url
                    );
                }
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read output to prevent blocking
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("yt-dlp: {}", line);
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                log.error("yt-dlp failed with exit code {}", exitCode);
                return CompletableFuture.completedFuture(false);
            }

            // Find the downloaded file
            File downloadedFile = null;
            File dir = tempDir.toFile();
            File[] files = dir.listFiles((d, name) -> name.startsWith(uniqueId));
            if (files != null && files.length > 0) {
                downloadedFile = files[0];
            }

            if (downloadedFile == null) {
                log.error("Could not find downloaded file in {}", tempDir);
                return CompletableFuture.completedFuture(false);
            }

            // Cleanup filename (remove UUID prefix for display)
            String originalName = downloadedFile.getName().substring(uniqueId.length() + 1); // +1 for underscore
            long size = downloadedFile.length();

            // Check quota
            userService.checkStorageQuota(username, size);

            // Upload
            String s3Key = storageService.uploadFile(Files.newInputStream(downloadedFile.toPath()), originalName, size);
            userService.updateStorageUsage(username, size);

            // Save to DB
            ScheduledVideo video = new ScheduledVideo();
            video.setUsername(username);
            video.setTitle(originalName);
            video.setS3Key(s3Key);
            video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
            video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
            video.setFileSize(size);
            repository.save(video);

            log.info("Successfully imported video from YouTube: {}", originalName);
            return CompletableFuture.completedFuture(true);

        } catch (Exception e) {
            log.error("Error importing from YouTube", e);
            return CompletableFuture.completedFuture(false);
        } finally {
            // Cleanup
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted((a, b) -> b.compareTo(a)) // Delete contents first
                            .forEach(path -> {
                                try { Files.delete(path); } catch (Exception ignored) {}
                            });
                } catch (Exception ignored) {}
            }
        }
    }
}
