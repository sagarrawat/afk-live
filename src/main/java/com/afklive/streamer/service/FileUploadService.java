package com.afklive.streamer.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

@Service
public class FileUploadService {
    private final UserFileService userFileService;

    public FileUploadService(UserFileService userFileService) {
        this.userFileService = userFileService;
    }

    public String handleFileUpload(MultipartFile file, String username) throws IOException {
        String originalName = file.getOriginalFilename();
        String fileName = generateProcessedFileName(originalName);
        Path targetPath = userFileService.getUserUploadDir(username).resolve(fileName);
        file.transferTo(targetPath);
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