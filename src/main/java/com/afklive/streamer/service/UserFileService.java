package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class UserFileService {

    private final Path baseUploadDir = Paths.get("uploads");
    private final VideoConversionService videoConversionService;
    private final ScheduledVideoRepository scheduledVideoRepository;

    @Autowired
    public UserFileService(@Lazy VideoConversionService videoConversionService, ScheduledVideoRepository scheduledVideoRepository) throws IOException {
        this.videoConversionService = videoConversionService;
        this.scheduledVideoRepository = scheduledVideoRepository;
        if (!Files.exists(baseUploadDir)) {
            log.info("Creating base upload directory: {}", baseUploadDir);
            Files.createDirectories(baseUploadDir);
        }
    }

    public Path getUserUploadDir(String username) throws IOException {
        Path userDir = baseUploadDir.resolve(username);
        if (!Files.exists(userDir)) {
            log.info("Creating user directory: {}", userDir);
            Files.createDirectories(userDir);
        }
        return userDir;
    }

    public List<String> listConvertedVideos(String username) throws IOException {
        // Query database instead of file system to support S3 and Local storage uniformly
        List<ScheduledVideo> videos = scheduledVideoRepository.findByUsername(username);

        return videos.stream()
                .filter(v -> v.getStatus() == ScheduledVideo.VideoStatus.LIBRARY)
                .map(ScheduledVideo::getTitle)
                .filter(title -> title != null && !title.isEmpty())
                .filter(title -> !title.contains("_raw"))
                .filter(this::isVideoFile)
                // Filter out videos currently being processed unless they are completed
                .filter(title -> videoConversionService.getProgress(username, title)
                        .map(progress -> progress == 100)
                        .orElse(true))
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isVideoFile(String filename) {
        return filename.toLowerCase().endsWith(".mp4");
    }

    public List<ScheduledVideo> listAudioFiles(String username) {
        List<ScheduledVideo> videos = scheduledVideoRepository.findByUsername(username);
        return videos.stream()
                .filter(v -> {
                    if (v.getTitle() == null) return false;
                    String lower = v.getTitle().toLowerCase();
                    return lower.endsWith(".mp3") || lower.endsWith(".wav");
                })
                .collect(Collectors.toList());
    }
}
