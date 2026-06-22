package com.afklive.streamer.service;

import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.util.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileUploadService {

    private final FileStorageService storageService;
    private final ScheduledVideoRepository repository;
    private final UserService userService;
    private final com.afklive.streamer.repository.MediaAssetRepository mediaAssetRepository;
    private final VideoAnalysisService videoAnalysisService;

    public String handleFileUpload(MultipartFile file, String username) throws IOException {
        String originalName = file.getOriginalFilename();
        String fileName = generateProcessedFileName(originalName);

        // Ensure unique filename
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int counter = 1;
        while (repository.findByUserEmailAndTitle(username, fileName).isPresent()) {
            fileName = baseName + "-" + counter + extension;
            counter++;
        }

        long size = file.getSize();
        userService.checkStorageQuota(username, size);

        String s3Key = storageService.uploadFile(file.getInputStream(), fileName, size);
        userService.updateStorageUsage(username, size);

        // Create Media Asset
        com.afklive.streamer.model.User user = userService.getOrCreateUser(username);
        com.afklive.streamer.model.MediaAsset asset = new com.afklive.streamer.model.MediaAsset(
            user,
            fileName,
            s3Key,
            size,
            com.afklive.streamer.model.MediaAsset.ContentType.VIDEO
        );
        asset = mediaAssetRepository.save(asset);

        // Create DB Entry so it can be converted/listed
        ScheduledVideo video = new ScheduledVideo();
        video.setUser(user);
        video.setTitle(fileName);
        video.setMediaAsset(asset);
        video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
        video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
        repository.save(video);

        final Long assetId = asset.getId();
        java.util.concurrent.CompletableFuture.runAsync(() -> videoAnalysisService.analyzeVideo(assetId));

        return originalName;
    }

    private String generateProcessedFileName(String originalName) {
        if (originalName == null || !originalName.contains(".mp4")) {
            return originalName;
        }
        int dotIndex = originalName.lastIndexOf(".");
        return originalName.substring(0, dotIndex) + "_raw" + originalName.substring(dotIndex);
    }
}
