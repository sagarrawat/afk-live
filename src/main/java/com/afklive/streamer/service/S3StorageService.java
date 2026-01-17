package com.afklive.streamer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3StorageService implements FileStorageService {

    private final S3Client s3Client;
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
    }

    public String uploadFile(InputStream inputStream, String originalFilename, long contentLength) {
        String key = UUID.randomUUID().toString() + "_" + originalFilename;

        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.putObject(putOb, RequestBody.fromInputStream(inputStream, contentLength));
        return key;
    }

    @Override
    public void storeFile(InputStream inputStream, String key, long contentLength) {
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.putObject(putOb, RequestBody.fromInputStream(inputStream, contentLength));
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
}
