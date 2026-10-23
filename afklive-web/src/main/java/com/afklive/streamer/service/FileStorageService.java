package com.afklive.streamer.service;

import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.Optional;

public interface FileStorageService {
    String uploadFile(InputStream inputStream, String originalFilename, long contentLength);
    InputStream downloadFile(String key);
    void downloadFileToPath(String key, Path destination);
    Resource loadFileAsResource(String key);
    void deleteFile(String key);
    Optional<String> generatePresignedUrl(String key);

    // ------------------------------------------------------------------ resumable uploads (ML-1)

    /** A new storage key for a file called {@code filename} (not yet written). */
    String newKey(String filename);

    /** Starts a multipart upload to {@code key}; returns its id. */
    String beginMultipart(String key);

    /**
     * Where the browser sends part {@code partNumber}: a presigned URL straight to object storage, or
     * empty when parts must come through the app ({@link #writePart}).
     */
    Optional<String> presignPart(String key, String uploadId, int partNumber, long contentLength);

    /** Stores a part that came through the app; returns its ETag. */
    String writePart(String key, String uploadId, int partNumber, InputStream data, long contentLength);

    /** Parts received so far, by part number. */
    List<Part> listParts(String key, String uploadId);

    /** Joins the parts into the final object. */
    void completeMultipart(String key, String uploadId, List<Part> parts);

    void abortMultipart(String key, String uploadId);

    /** Something ffprobe/ffmpeg can open to read the file (a local path or a short-lived URL). */
    String readLocation(String key);

    /** Size of a stored object in bytes. */
    long size(String key);

    record Part(int number, long size, String etag) {
    }
}
