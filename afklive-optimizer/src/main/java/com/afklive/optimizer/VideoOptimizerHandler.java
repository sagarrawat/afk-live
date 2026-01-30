package com.afklive.optimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VideoOptimizerHandler implements RequestHandler<Map<String, String>, String> {

    private final S3Client s3;
    private final String bucketName;

    // Default constructor for AWS Lambda
    public VideoOptimizerHandler() {
        String endpoint = System.getenv("DO_SPACES_ENDPOINT");
        String region = System.getenv("DO_SPACES_REGION");
        String key = System.getenv("DO_SPACES_KEY");
        String secret = System.getenv("DO_SPACES_SECRET");
        this.bucketName = System.getenv("DO_SPACES_BUCKET");

        if (endpoint != null && region != null && key != null && secret != null) {
            this.s3 = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .checksumValidationEnabled(false) // Disable checksum validation for DO Spaces compatibility
                            .build())
                    .build();
        } else {
            // Fallback or initialization for test environment where env vars might be missing
            // This prevents NPE during class instantiation in tests if we use a different constructor
            this.s3 = null;
        }
    }

    // Constructor for testing
    public VideoOptimizerHandler(S3Client s3, String bucketName) {
        this.s3 = s3;
        this.bucketName = bucketName;
    }

    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        if (s3 == null) {
            return "{\"status\": \"error\", \"message\": \"S3 Client not initialized\"}";
        }

        String sourceKey = event.get("file_name");
        String mode = event.getOrDefault("mode", "landscape");
        String heightStr = event.getOrDefault("height", "1080");
        int height = Integer.parseInt(heightStr);
        String username = event.getOrDefault("username", "unknown");

        String simpleName = new File(sourceKey).getName();
        String baseTitle = simpleName.lastIndexOf('.') > 0 ? simpleName.substring(0, simpleName.lastIndexOf('.')) : simpleName;
        String targetSuffix = String.format("_%s_%dp", mode, height);
        String targetTitle = baseTitle + targetSuffix + ".mp4";

        String outputKey = UUID.randomUUID().toString() + "_" + targetTitle;

        File localInput = null;
        File localOutput = null;

        try {
            localInput = File.createTempFile("source_", ".mp4");
            localOutput = File.createTempFile("target_", ".mp4");

            context.getLogger().log("Downloading: " + sourceKey + " to " + localInput.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(localInput)) {
                s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(sourceKey).build(),
                        ResponseTransformer.toOutputStream(fos));
            }

            context.getLogger().log("Optimizing " + simpleName + " to " + targetTitle + " (" + mode + ", " + height + "p)");

            List<String> command = FFmpegCommandBuilder.buildOptimizeCommand(localInput.toPath(), localOutput.toPath(), mode, height);

            executeCommand(command, localOutput);

            long fileSize = Files.size(localOutput.toPath());
            context.getLogger().log("Uploading: " + outputKey + " (" + fileSize + " bytes)");

            s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(outputKey).build(),
                    RequestBody.fromFile(localOutput));

            return String.format("{\"status\": \"success\", \"original_key\": \"%s\", \"optimized_key\": \"%s\", \"file_size\": %d}",
                    sourceKey, outputKey, fileSize);

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            e.printStackTrace();
            // Return valid JSON string for error case
            String safeMessage = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown error";
            return String.format("{\"status\": \"error\", \"message\": \"%s\"}", safeMessage);
        } finally {
            if (localInput != null && localInput.exists()) localInput.delete();
            if (localOutput != null && localOutput.exists()) localOutput.delete();
        }
    }

    // Protected method for mocking process execution in tests
    protected void executeCommand(List<String> command, File output) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg failed with code " + exitCode);
        }
    }
}
