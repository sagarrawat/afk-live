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

    public String handleFileUpload(MultipartFile file, String username) throws IOException {
        String originalName = file.getOriginalFilename();
        String fileName = generateProcessedFileName(originalName);

        long size = file.getSize();
        userService.checkStorageQuota(username, size);

        String s3Key = storageService.uploadFile(file.getInputStream(), fileName, size);
        userService.updateStorageUsage(username, size);

        // Create DB Entry so it can be converted/listed
        ScheduledVideo video = new ScheduledVideo();
        video.setUsername(username);
        video.setTitle(fileName);
        video.setS3Key(s3Key);
        video.setStatus(ScheduledVideo.VideoStatus.LIBRARY);
        video.setPrivacyStatus(AppConstants.PRIVACY_PRIVATE);
        video.setFileSize(size);
        repository.save(video);

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
