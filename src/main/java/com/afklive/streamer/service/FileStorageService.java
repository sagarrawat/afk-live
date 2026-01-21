package com.afklive.streamer.service;

import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.core.io.Resource;
import java.util.Optional;

public interface FileStorageService {
    String uploadFile(InputStream inputStream, String originalFilename, long contentLength);
    InputStream downloadFile(String key);
    void downloadFileToPath(String key, Path destination);
    Resource loadFileAsResource(String key);
    void deleteFile(String key);
    Optional<String> generatePresignedUrl(String key);
}
