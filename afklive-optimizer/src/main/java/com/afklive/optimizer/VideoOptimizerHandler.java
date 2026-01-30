package com.afklive.optimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VideoOptimizerHandler implements RequestHandler<Map<String, String>, String> {

    // Using afklive-web configuration style (DO_SPACES_*)
    private static final String DO_SPACES_ENDPOINT = System.getenv("DO_SPACES_ENDPOINT");
    private static final String DO_SPACES_REGION = System.getenv("DO_SPACES_REGION");
    private static final String DO_SPACES_KEY = System.getenv("DO_SPACES_KEY");
    private static final String DO_SPACES_SECRET = System.getenv("DO_SPACES_SECRET");
    private static final String DO_SPACES_BUCKET = System.getenv("DO_SPACES_BUCKET");

    private final S3Client s3;
    private final String bucketName;

    public VideoOptimizerHandler() {
        this.bucketName = DO_SPACES_BUCKET;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(DO_SPACES_ENDPOINT))
                .region(Region.of(DO_SPACES_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(DO_SPACES_KEY, DO_SPACES_SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Override
    public String handleRequest(Map<String, String> event, Context context) {
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

            context.getLogger().log("Downloading: " + sourceKey);
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(sourceKey).build(), localInput.toPath());

            context.getLogger().log("Optimizing " + simpleName + " to " + targetTitle + " (" + mode + ", " + height + "p)");

            List<String> command = FFmpegCommandBuilder.buildOptimizeCommand(localInput.toPath(), localOutput.toPath(), mode, height);

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

            long fileSize = Files.size(localOutput.toPath());
            context.getLogger().log("Uploading: " + outputKey + " (" + fileSize + " bytes)");

            s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(outputKey).build(),
                    RequestBody.fromFile(localOutput));

            return String.format("{\"status\": \"success\", \"original_key\": \"%s\", \"optimized_key\": \"%s\", \"file_size\": %d}",
                    sourceKey, outputKey, fileSize);

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            e.printStackTrace();
            return String.format("{\"status\": \"error\", \"message\": \"%s\"}", e.getMessage());
        } finally {
            if (localInput != null && localInput.exists()) localInput.delete();
            if (localOutput != null && localOutput.exists()) localOutput.delete();
        }
    }
}
