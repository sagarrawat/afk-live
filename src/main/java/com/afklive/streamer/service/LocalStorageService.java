package com.afklive.streamer.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements FileStorageService {

    private final Path rootLocation = Paths.get("data/storage");

    public LocalStorageService() throws IOException {
        Files.createDirectories(rootLocation.toAbsolutePath());
    }

    @Override
    public String uploadFile(InputStream inputStream, String originalFilename, long contentLength) {
        String key = UUID.randomUUID().toString() + "_" + originalFilename;
        try {
            Path destinationFile = this.rootLocation.resolve(key).normalize().toAbsolutePath();
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    @Override
    public InputStream downloadFile(String key) {
        try {
            Path file = rootLocation.resolve(key);
            if (!Files.exists(file)) {
                 throw new RuntimeException("File not found: " + key);
            }
            return new FileInputStream(file.toFile());
        } catch (FileNotFoundException e) {
             throw new RuntimeException("File not found", e);
        }
    }

    @Override
    public void downloadFileToPath(String key, Path destination) {
        try {
            Path source = rootLocation.resolve(key);
            if (!Files.exists(source)) {
                 throw new RuntimeException("File not found: " + key);
            }
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file", e);
        }
    }

    @Override
    public Resource loadFileAsResource(String key) {
        Path file = rootLocation.resolve(key);
        if (!Files.exists(file)) {
            throw new RuntimeException("File not found: " + key);
        }
        return new FileSystemResource(file);
    }

    @Override
    public void deleteFile(String key) {
        try {
            Path file = rootLocation.resolve(key);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    @Override
    public Optional<String> generatePresignedUrl(String key) {
        // Local storage does not support presigned URLs for external access directly
        return Optional.empty();
    }
}
