package com.afklive.streamer.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.afklive.streamer.repository.ScheduledVideoRepository;

@Service
public class UserFileService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserFileService.class);

    private final Path baseUploadDir = Paths.get("data/storage");
    private final VideoConversionService videoConversionService;
    private final ScheduledVideoRepository scheduledVideoRepository;

    @Autowired
    public UserFileService(VideoConversionService videoConversionService, ScheduledVideoRepository scheduledVideoRepository) throws IOException {
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
        try (Stream<Path> stream = Files.list(getUserUploadDir(username))) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(fileName -> !fileName.contains("_raw"))
                    .filter(this::isVideoFile)
                    .filter(fileName -> videoConversionService.getProgress(username, fileName)
                            .map(progress -> progress == 100)
                            .orElse(true))
                    .collect(Collectors.toList());
        }
    }

    private boolean isVideoFile(String filename) {
        return filename.toLowerCase().endsWith(".mp4");
    }

    public List<Map<String, Object>> listAudioFiles(String username) {
        return scheduledVideoRepository.findByUsername(username).stream()
                .filter(v -> v.getTitle() != null && v.getS3Key() != null)
                .filter(v -> isAudioFile(v.getTitle())) // Assuming title has extension or we check s3Key
                .map(v -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", v.getId());
                    map.put("title", v.getTitle());
                    map.put("filename", v.getS3Key());
                    return map;
                })
                .collect(Collectors.toList());
    }

    private boolean isAudioFile(String filename) {
        String n = filename.toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".wav");
    }
}
