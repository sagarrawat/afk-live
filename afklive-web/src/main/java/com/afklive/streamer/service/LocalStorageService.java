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

import com.afklive.streamer.util.SafePaths;

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
        String key = UUID.randomUUID().toString() + "_" + SafePaths.sanitizeFilename(originalFilename);
        Path destinationFile = resolveKey(key);
        try {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(destinationFile); // don't leave a partial file behind
            } catch (IOException ignored) {
                // best effort
            }
            throw new RuntimeException("Failed to store file", e);
        }
    }

    @Override
    public InputStream downloadFile(String key) {
        try {
            Path file = resolveKey(key);
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
            Path source = resolveKey(key);
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
        Path file = resolveKey(key);
        if (!Files.exists(file)) {
            throw new RuntimeException("File not found: " + key);
        }
        return new FileSystemResource(file);
    }

    @Override
    public void deleteFile(String key) {
        try {
            Path file = resolveKey(key);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    /** Resolves a storage key inside the storage root; rejects keys that would escape it. */
    private Path resolveKey(String key) {
        return SafePaths.resolveInside(rootLocation, key);
    }

    @Override
    public Optional<String> generatePresignedUrl(String key) {
        // Local storage does not support presigned URLs for external access directly
        return Optional.empty();
    }

    // ------------------------------------------------------------------ resumable uploads
    // Parts are kept under data/storage/.uploads/<id>/ and joined on completion. This suits one web
    // instance (dev, small installs); production uses S3, where parts go straight to the bucket.

    private Path uploadDir(String uploadId) {
        if (!uploadId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Bad upload id");
        }
        return rootLocation.resolve(".uploads").resolve(uploadId);
    }

    @Override
    public String newKey(String filename) {
        return UUID.randomUUID() + "_" + SafePaths.sanitizeFilename(filename);
    }

    @Override
    public String beginMultipart(String key) {
        String id = UUID.randomUUID().toString();
        try {
            Files.createDirectories(uploadDir(id));
        } catch (IOException e) {
            throw new RuntimeException("Could not start the upload", e);
        }
        return id;
    }

    @Override
    public Optional<String> presignPart(String key, String uploadId, int partNumber, long contentLength) {
        return Optional.empty();
    }

    @Override
    public String writePart(String key, String uploadId, int partNumber, InputStream data, long contentLength) {
        Path dir = uploadDir(uploadId);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Upload not found");
        }
        Path tmp = dir.resolve(partNumber + ".tmp-" + UUID.randomUUID());
        try {
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            long written;
            try (InputStream in = new java.security.DigestInputStream(data, md5)) {
                written = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (contentLength >= 0 && written != contentLength) {
                Files.deleteIfExists(tmp);
                throw new IllegalArgumentException("Part was cut short (" + written + " of " + contentLength + " bytes)");
            }
            Files.move(tmp, dir.resolve(partNumber + ".part"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return "\"" + java.util.HexFormat.of().formatHex(md5.digest()) + "\"";
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new RuntimeException("Could not store the part", e);
        }
    }

    @Override
    public java.util.List<Part> listParts(String key, String uploadId) {
        Path dir = uploadDir(uploadId);
        if (!Files.isDirectory(dir)) {
            return java.util.List.of();
        }
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".part"))
                    .map(f -> {
                        String name = f.getFileName().toString();
                        try {
                            return new Part(Integer.parseInt(name.substring(0, name.length() - 5)), Files.size(f), null);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .sorted(java.util.Comparator.comparingInt(Part::number))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Could not list parts", e);
        }
    }

    @Override
    public void completeMultipart(String key, String uploadId, java.util.List<Part> parts) {
        Path dir = uploadDir(uploadId);
        Path target = resolveKey(key);
        Path tmp = target.resolveSibling(target.getFileName() + ".joining");
        try (var out = Files.newOutputStream(tmp)) {
            for (Part p : parts.stream().sorted(java.util.Comparator.comparingInt(Part::number)).toList()) {
                Files.copy(dir.resolve(p.number() + ".part"), out);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not join the parts", e);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Could not store the file", e);
        }
        abortMultipart(key, uploadId);
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        Path dir = uploadDir(uploadId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new RuntimeException("Could not remove the upload", e);
        }
    }

    @Override
    public String readLocation(String key) {
        return resolveKey(key).toAbsolutePath().toString();
    }

    @Override
    public long size(String key) {
        try {
            return Files.size(resolveKey(key));
        } catch (IOException e) {
            throw new RuntimeException("File not found: " + key, e);
        }
    }
}
