package com.afklive.streamer.service;

import java.io.InputStream;
import java.nio.file.Path;

public interface FileStorageService {
    String uploadFile(InputStream inputStream, String originalFilename, long contentLength);
    void storeFile(InputStream inputStream, String key, long contentLength);
    InputStream downloadFile(String key);
    void downloadFileToPath(String key, Path destination);
}
