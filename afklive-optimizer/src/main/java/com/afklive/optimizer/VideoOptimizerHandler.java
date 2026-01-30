package com.afklive.optimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.io.File;
import java.net.URI;
import java.util.Map;

public class VideoOptimizerHandler implements RequestHandler<Map<String, String>, String> {

    private static final String R2_ACCESS_KEY = System.getenv("R2_ACCESS_KEY");
    private static final String R2_SECRET_KEY = System.getenv("R2_SECRET_KEY");
    private static final String R2_ACCOUNT_ID = System.getenv("R2_ACCOUNT_ID");
    private static final String BUCKET_NAME = System.getenv("BUCKET_NAME");

    private final S3Client s3;

    public VideoOptimizerHandler() {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create("https://" + R2_ACCOUNT_ID + ".r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(R2_ACCESS_KEY, R2_SECRET_KEY)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        String fileName = event.get("file_name");
        String outputName = "optimized_" + fileName;
        File localInput = new File("/tmp/" + fileName);
        File localOutput = new File("/tmp/" + outputName);

        try {
            context.getLogger().log("Downloading: " + fileName);
            s3.getObject(GetObjectRequest.builder().bucket(BUCKET_NAME).key(fileName).build(), localInput.toPath());

            context.getLogger().log("Optimizing with FFmpeg...");
            ProcessBuilder pb = new ProcessBuilder(
                    "/opt/bin/ffmpeg", "-y", "-i", localInput.getAbsolutePath(),
                    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", localOutput.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor() != 0) throw new RuntimeException("FFmpeg failed");

            context.getLogger().log("Uploading: " + outputName);
            s3.putObject(PutObjectRequest.builder().bucket(BUCKET_NAME).key(outputName).build(), localOutput.toPath());

            return "Success: " + outputName;
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return "Error: " + e.getMessage();
        } finally {
            if (localInput.exists()) localInput.delete();
            if (localOutput.exists()) localOutput.delete();
        }
    }
}
