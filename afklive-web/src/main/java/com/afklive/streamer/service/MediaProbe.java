package com.afklive.streamer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads a media file's basic properties with ffprobe, so streaming can decide whether a file
 * can be sent as-is (stream copy) and whether a silent audio track must be added.
 */
@Service
@Slf4j
public class MediaProbe {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What we need to know about a file. {@code height}/{@code fps} are 0 when unknown. */
    public record MediaInfo(String videoCodec, int width, int height, double fps, boolean hasAudio) {
        public static final MediaInfo UNKNOWN = new MediaInfo(null, 0, 0, 0, true);
    }

    public MediaInfo probe(Path file) {
        List<String> command = List.of(ffprobe(), "-v", "error", "-print_format", "json", "-show_streams", file.toString());
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            byte[] out;
            try (InputStream in = process.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return MediaInfo.UNKNOWN;
            }
            if (process.exitValue() != 0) {
                log.warn("ffprobe failed for {} (exit {})", file, process.exitValue());
                return MediaInfo.UNKNOWN;
            }
            return parse(out);
        } catch (IOException e) {
            log.warn("ffprobe could not run for {}", file, e);
            return MediaInfo.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MediaInfo.UNKNOWN;
        }
    }

    static MediaInfo parse(byte[] ffprobeJson) throws IOException {
        JsonNode streams = JSON.readTree(ffprobeJson).path("streams");
        String codec = null;
        int width = 0;
        int height = 0;
        double fps = 0;
        boolean hasAudio = false;
        for (JsonNode s : streams) {
            String type = s.path("codec_type").asText();
            if ("video".equals(type) && codec == null && !"mjpeg".equals(s.path("codec_name").asText())) {
                codec = s.path("codec_name").asText(null);
                width = s.path("width").asInt(0);
                height = s.path("height").asInt(0);
                fps = parseRate(s.path("avg_frame_rate").asText("0/0"));
                if (fps == 0) {
                    fps = parseRate(s.path("r_frame_rate").asText("0/0"));
                }
            } else if ("audio".equals(type)) {
                hasAudio = true;
            }
        }
        return new MediaInfo(codec, width, height, fps, hasAudio);
    }

    static double parseRate(String rate) {
        String[] parts = rate.split("/");
        try {
            double num = Double.parseDouble(parts[0]);
            double den = parts.length > 1 ? Double.parseDouble(parts[1]) : 1;
            return den == 0 ? 0 : num / den;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String ffprobe() {
        java.io.File local = new java.io.File("bin/ffprobe");
        return local.exists() ? local.getAbsolutePath() : "ffprobe";
    }
}
