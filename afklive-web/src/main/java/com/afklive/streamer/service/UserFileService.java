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
    private final ScheduledVideoRepository scheduledVideoRepository;
    private final com.afklive.streamer.repository.MediaAssetRepository mediaAssetRepository;

    @Autowired
    public UserFileService(ScheduledVideoRepository scheduledVideoRepository, com.afklive.streamer.repository.MediaAssetRepository mediaAssetRepository) throws IOException {
        this.scheduledVideoRepository = scheduledVideoRepository;
        this.mediaAssetRepository = mediaAssetRepository;
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

    public List<com.afklive.streamer.model.MediaAsset> listConvertedVideos(String username) throws IOException {
        // Query Media Assets instead of ScheduledVideos
        List<com.afklive.streamer.model.MediaAsset> assets = mediaAssetRepository.findByUserEmail(username);

        return assets.stream()
                .filter(a -> a.getContentType() == com.afklive.streamer.model.MediaAsset.ContentType.VIDEO)
                .collect(Collectors.toList());
    }



    public List<com.afklive.streamer.model.MediaAsset> listAudioFiles(String username) {
        List<com.afklive.streamer.model.MediaAsset> assets = mediaAssetRepository.findByUserEmail(username);
        return assets.stream()
                .filter(a -> a.getContentType() == com.afklive.streamer.model.MediaAsset.ContentType.AUDIO)
                .collect(Collectors.toList());
    }
}
