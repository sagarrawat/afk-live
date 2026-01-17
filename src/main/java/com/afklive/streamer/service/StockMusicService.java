package com.afklive.streamer.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class StockMusicService {

    @Autowired
    private FileStorageService storageService;

    // Define stock music inventory
    private final List<Map<String, String>> stockInventory = List.of(
            Map.of("name", "Lofi Chill", "key", "stock/lofi_chill.mp3"),
            Map.of("name", "Upbeat Pop", "key", "stock/upbeat_pop.mp3"),
            Map.of("name", "Ambient Focus", "key", "stock/ambient_focus.mp3"),
            Map.of("name", "Jazz Lounge", "key", "stock/jazz_lounge.mp3")
    );

    @PostConstruct
    public void initStockMusic() {
        log.info("Initializing Stock Music Library...");
        for (Map<String, String> item : stockInventory) {
            String key = item.get("key");
            ensureStockFileExists(key);
        }
    }

    public List<Map<String, String>> getAvailableStockMusic() {
        return stockInventory;
    }

    private void ensureStockFileExists(String key) {
        try {
            // Check if file exists by trying to download metadata (or just download byte 0-1)
            // Since our interface only has downloadFile, we'll try that.
            // Ideally we'd have an exists() method, but this works for now.
            try (var is = storageService.downloadFile(key)) {
                // It exists
                return;
            }
        } catch (Exception e) {
            // If it throws, it likely doesn't exist
            log.info("Stock music '{}' missing. Generating...", key);
            generateAndUploadStockMusic(key);
        }
    }

    private void generateAndUploadStockMusic(String key) {
        // Generate a dummy silent MP3 (or fake content if ffmpeg is missing)
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("stock_gen");
            Path tempFile = tempDir.resolve("temp.mp3");

            boolean ffmpegAvailable = isFfmpegAvailable();

            if (ffmpegAvailable) {
                // ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t 30 -q:a 9 -acodec libmp3lame output.mp3
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-f", "lavfi",
                        "-i", "anullsrc=r=44100:cl=stereo",
                        "-t", "30", // 30 seconds duration
                        "-q:a", "9",
                        "-acodec", "libmp3lame",
                        tempFile.toAbsolutePath().toString()
                );

                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    log.error("Failed to generate MP3 via ffmpeg for {}. Falling back to dummy bytes.", key);
                    createDummyFile(tempFile);
                }
            } else {
                log.warn("ffmpeg not found. creating dummy file for {}", key);
                createDummyFile(tempFile);
            }

            // Upload to storage
            try (FileInputStream fis = new FileInputStream(tempFile.toFile())) {
                storageService.storeFile(fis, key, Files.size(tempFile));
                log.info("Stock music '{}' generated and stored.", key);
            }

        } catch (Exception e) {
            log.error("Error generating stock music", e);
        } finally {
            // Cleanup
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try { Files.delete(p); } catch (IOException ignored) {}
                            });
                } catch (IOException ignored) {}
            }
        }
    }

    private boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void createDummyFile(Path path) throws IOException {
        // Create a file with some dummy bytes
        byte[] dummyData = new byte[1024]; // 1KB of zeros
        Files.write(path, dummyData);
    }
}
