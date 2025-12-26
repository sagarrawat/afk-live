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

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoConversionService {

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
                conversionProgress.put(progressKey, 100);
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

    public Optional<Integer> getProgress(String username, String fileName) {
        String progressKey = username + ":" + fileName;

        return Optional.ofNullable(this.conversionProgress.get(progressKey));
    }
}