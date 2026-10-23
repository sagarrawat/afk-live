package com.afklive.streamer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3StorageService implements FileStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    public S3StorageService(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey) {

        this.bucketName = bucket;
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();

        this.s3Presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    public String uploadFile(InputStream inputStream, String originalFilename, long contentLength) {
        String key = UUID.randomUUID().toString() + "_" + com.afklive.streamer.util.SafePaths.sanitizeFilename(originalFilename);

        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        if (contentLength >= 0) {
            s3Client.putObject(putOb, RequestBody.fromInputStream(inputStream, contentLength));
            return key;
        }
        // Unknown length (e.g. a zip entry): spool to a temp file first, since S3 needs the size up front.
        Path temp = null;
        try {
            temp = java.nio.file.Files.createTempFile("upload-", ".tmp");
            java.nio.file.Files.copy(inputStream, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            s3Client.putObject(putOb, RequestBody.fromFile(temp));
            return key;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to store file", e);
        } finally {
            if (temp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temp);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
            }
        }
    }

    public InputStream downloadFile(String key) {
        GetObjectRequest getOb = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return s3Client.getObject(getOb);
    }

    @Override
    public void downloadFileToPath(String key, Path destination) {
        GetObjectRequest getOb = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        s3Client.getObject(getOb, software.amazon.awssdk.core.sync.ResponseTransformer.toFile(destination));
    }

    @Override
    public Resource loadFileAsResource(String key) {
        return new InputStreamResource(downloadFile(key));
    }

    @Override
    public void deleteFile(String key) {
        DeleteObjectRequest deleteOb = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        s3Client.deleteObject(deleteOb);
    }

    @Override
    public Optional<String> generatePresignedUrl(String key) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60)) // 1 hour validity
                    .getObjectRequest(getObjectRequest)
                    .build();

            String url = s3Presigner.presignGetObject(presignRequest).url().toString();
            return Optional.of(url);
        } catch (Exception e) {
            // Log error or return empty
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ resumable uploads

    @Override
    public String newKey(String filename) {
        return UUID.randomUUID() + "_" + com.afklive.streamer.util.SafePaths.sanitizeFilename(filename);
    }

    @Override
    public String beginMultipart(String key) {
        return s3Client.createMultipartUpload(software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).build()).uploadId();
    }

    @Override
    public Optional<String> presignPart(String key, String uploadId, int partNumber, long contentLength) {
        var part = software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId).partNumber(partNumber).contentLength(contentLength).build();
        var presign = software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1)).uploadPartRequest(part).build();
        return Optional.of(s3Presigner.presignUploadPart(presign).url().toString());
    }

    @Override
    public String writePart(String key, String uploadId, int partNumber, InputStream data, long contentLength) {
        return s3Client.uploadPart(software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
                        .bucket(bucketName).key(key).uploadId(uploadId).partNumber(partNumber).contentLength(contentLength).build(),
                RequestBody.fromInputStream(data, contentLength)).eTag();
    }

    @Override
    public java.util.List<Part> listParts(String key, String uploadId) {
        java.util.List<Part> out = new java.util.ArrayList<>();
        Integer marker = null;
        while (true) {
            var req = software.amazon.awssdk.services.s3.model.ListPartsRequest.builder()
                    .bucket(bucketName).key(key).uploadId(uploadId);
            if (marker != null) {
                req.partNumberMarker(marker);
            }
            var res = s3Client.listParts(req.build());
            for (var p : res.parts()) {
                out.add(new Part(p.partNumber(), p.size(), p.eTag()));
            }
            if (!Boolean.TRUE.equals(res.isTruncated())) {
                return out;
            }
            marker = res.nextPartNumberMarker();
        }
    }

    @Override
    public void completeMultipart(String key, String uploadId, java.util.List<Part> parts) {
        var completed = parts.stream()
                .sorted(java.util.Comparator.comparingInt(Part::number))
                .map(p -> software.amazon.awssdk.services.s3.model.CompletedPart.builder().partNumber(p.number()).eTag(p.etag()).build())
                .toList();
        s3Client.completeMultipartUpload(software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId)
                .multipartUpload(software.amazon.awssdk.services.s3.model.CompletedMultipartUpload.builder().parts(completed).build())
                .build());
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        s3Client.abortMultipartUpload(software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId).build());
    }

    @Override
    public String readLocation(String key) {
        return generatePresignedUrl(key).orElseThrow(() -> new IllegalStateException("Could not sign a read URL"));
    }

    @Override
    public long size(String key) {
        return s3Client.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                .bucket(bucketName).key(key).build()).contentLength();
    }
}
