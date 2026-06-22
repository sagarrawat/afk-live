package com.afklive.streamer.service;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoAnalysisService {

    private final MediaAssetRepository mediaAssetRepo;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    /**
     * Asynchronously analyzes a video asset using ffprobe directly against its S3 presigned URL.
     * Determines whether the video can be streamed as-is, or if it requires eager optimization.
     */
    public void analyzeVideo(Long mediaAssetId) {
        MediaAsset asset = mediaAssetRepo.findById(mediaAssetId).orElseThrow(() -> new IllegalArgumentException("Asset not found"));
        
        Optional<String> urlOpt = fileStorageService.generatePresignedUrl(asset.getS3Key());
        if (urlOpt.isEmpty()) {
            log.error("Could not generate presigned URL for analysis of asset {}", mediaAssetId);
            asset.setStreamReadiness(MediaAsset.StreamReadiness.OPTIMIZATION_FAILED);
            mediaAssetRepo.save(asset);
            return;
        }

        String url = urlOpt.get();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe",
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    url
            );

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("ffprobe timed out");
            }

            String jsonOutput = output.toString();
            asset.setProbeData(jsonOutput);
            
            JsonNode root = objectMapper.readTree(jsonOutput);
            JsonNode format = root.path("format");
            JsonNode streams = root.path("streams");

            if (format.isMissingNode() || streams.isMissingNode() || streams.isEmpty()) {
                throw new RuntimeException("Invalid ffprobe output");
            }

            asset.setDurationSeconds(format.path("duration").asInt());
            asset.setBitrateKbps(format.path("bit_rate").asInt() / 1000);

            // Find video stream
            JsonNode videoStream = null;
            JsonNode audioStream = null;
            for (JsonNode stream : streams) {
                String codecType = stream.path("codec_type").asText();
                if ("video".equals(codecType) && videoStream == null) {
                    videoStream = stream;
                } else if ("audio".equals(codecType) && audioStream == null) {
                    audioStream = stream;
                }
            }

            if (videoStream != null) {
                asset.setVideoCodec(videoStream.path("codec_name").asText());
                int width = videoStream.path("width").asInt();
                int height = videoStream.path("height").asInt();
                asset.setResolution(width + "x" + height);

                String rFrameRate = videoStream.path("r_frame_rate").asText();
                if (rFrameRate.contains("/")) {
                    String[] parts = rFrameRate.split("/");
                    if (parts.length == 2 && !parts[1].equals("0")) {
                        double fr = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
                        asset.setFrameRate(Math.round(fr * 100.0) / 100.0);
                    }
                }
            }

            if (audioStream != null) {
                asset.setAudioCodec(audioStream.path("codec_name").asText());
            }

            // Determine Stream Readiness
            // As per requirements, we kill the optimization pipeline and allow users to stream immediately.
            // If the video is not H.264, the worker will either handle it on the fly or it will fail.
            asset.setStreamReadiness(MediaAsset.StreamReadiness.STREAM_READY);

            mediaAssetRepo.save(asset);
            log.info("Finished analysis for asset {}. Status: {}", mediaAssetId, asset.getStreamReadiness());

        } catch (Exception e) {
            log.error("Failed to analyze video for asset {}: {}", mediaAssetId, e.getMessage());
            asset.setStreamReadiness(MediaAsset.StreamReadiness.OPTIMIZATION_FAILED);
            mediaAssetRepo.save(asset);
        }
    }
}
