package com.afklive.streamer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FFmpegCommandBuilder {

    public static List<String> buildConversionCommand(Path source, Path target) {
        return List.of(
                "nice",
                "-n",
                "19",
                "ffmpeg",
                "-threads",
                "1",
                "-i",
                source.toString(),
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-vf",
                "scale=1280:-2",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-ar",
                "44100",
                target.toString(),
                "-y"
        );
    }

    public static List<String> buildConvertToShortCommand(Path input, Path output) {
        // Convert Landscape to Portrait (9:16) with blurred background
        // ffmpeg -i input.mp4 -vf "split[original][copy];[copy]scale=-1:1920,crop=w=1080:h=1920,gblur=sigma=20[blurred];[original]scale=1080:-1[scaled];[blurred][scaled]overlay=0:(H-h)/2" -c:v libx264 -c:a copy output.mp4
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(input.toString());
        command.add("-vf");
        command.add("split[original][copy];[copy]scale=-1:1920,crop=w=1080:h=1920,gblur=sigma=20[blurred];[original]scale=1080:-1[scaled];[blurred][scaled]overlay=0:(H-h)/2");

        // YouTube Shorts / Live optimizations
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("superfast"); // Balanced for speed/quality
        command.add("-b:v");
        command.add("4500k"); // Target bitrate for 1080p Shorts
        command.add("-maxrate");
        command.add("6000k");
        command.add("-bufsize");
        command.add("12000k");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-r");
        command.add("30");
        command.add("-g");
        command.add("60"); // 2-second GOP (keyframe interval) for 30fps

        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-ar");
        command.add("44100");

        command.add("-y");
        command.add(output.toString());
        return command;
    }

    public static List<String> buildMixCommand(Path videoPath, Path audioPath, String volume, Path outputPath) {
        // ffmpeg -i video.mp4 -stream_loop -1 -i audio.mp3 -filter_complex "[1:a]volume=0.5[a1];[0:a][a1]amix=inputs=2:duration=first[aout]" -map 0:v -map "[aout]" -c:v copy -c:a aac -y out.mp4
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(videoPath.toString());
        command.add("-stream_loop");
        command.add("-1");
        command.add("-i");
        command.add(audioPath.toString());
        command.add("-filter_complex");
        command.add("[1:a]volume=" + volume + "[a1];[0:a][a1]amix=inputs=2:duration=first[aout]");
        command.add("-map");
        command.add("0:v");
        command.add("-map");
        command.add("[aout]");
        command.add("-c:v");
        command.add("copy"); // Copy video stream
        command.add("-c:a");
        command.add("aac");
        command.add("-y");
        command.add(outputPath.toString());
        return command;
    }

    public static List<String> buildStreamCommand(
            Path videoPath,
            List<String> streamKeys,
            Path musicPath,
            String musicVolume,
            int loopCount,
            Path watermarkPath
    ) {
        List<String> command = new ArrayList<>();
        command.add("nice");
        command.add("-n");
        command.add("19");
        command.add("ffmpeg");

        // Video input (Index 0)
        command.add("-re");
        command.add("-stream_loop");
        command.add(String.valueOf(loopCount));
        command.add("-i");
        command.add(videoPath.toString());

        // Music input (Index 1) if provided
        boolean hasMusic = musicPath != null;
        if (hasMusic) {
            command.add("-stream_loop");
            command.add("-1");
            command.add("-i");
            command.add(musicPath.toString());
        }

        // Watermark input (Index 1 or 2) if provided
        boolean hasWatermark = watermarkPath != null;
        if (hasWatermark) {
            command.add("-i");
            command.add(watermarkPath.toString());
        }

        // --- FILTER COMPLEX ---
        // We need to handle Overlay and Audio Mixing in one complex filter chain
        // If hasWatermark, we MUST transcode video.
        // If hasMusic, we mix audio.

        List<String> filterChains = new ArrayList<>();
        String videoLabel = "0:v";
        String audioLabel = "0:a";

        if (hasWatermark) {
            // Overlay watermark (assume watermark is the last input)
            // Inputs: 0:v and {last_input_index}:v
            int watermarkIndex = hasMusic ? 2 : 1;
            // Scale watermark to 15% of width, position top-right with 20px padding
            String overlayFilter = String.format("[%d:v]scale=iw*0.15:-1[wm];[0:v][wm]overlay=main_w-overlay_w-20:20", watermarkIndex);
            filterChains.add(overlayFilter + "[vout]");
            videoLabel = "[vout]";
        }

        if (hasMusic) {
            // Mix audio
            // Inputs: 0:a and 1:a
            String mixFilter = String.format("[1:a]volume=%s[a1];[0:a][a1]amix=inputs=2:duration=first[aout]", musicVolume);
            filterChains.add(mixFilter);
            audioLabel = "[aout]";
        }

        if (!filterChains.isEmpty()) {
            command.add("-filter_complex");
            command.add(String.join(";", filterChains));
        }

        // --- ENCODING OPTIONS ---

        // Video Encoding
        if (hasWatermark) {
            // Must re-encode
            command.add("-map");
            command.add(videoLabel);
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("veryfast");
            command.add("-b:v");
            command.add("4000k");
            command.add("-maxrate");
            command.add("4500k");
            command.add("-bufsize");
            command.add("9000k");
            command.add("-pix_fmt");
            command.add("yuv420p");
            command.add("-g");
            command.add("60");
        } else {
            // Copy stream (Efficient)
            command.add("-map");
            command.add("0:v");
            command.add("-c:v");
            command.add("copy");
        }

        // Audio Encoding
        if (hasMusic) {
            command.add("-map");
            command.add(audioLabel);
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("128k");
        } else {
            command.add("-map");
            command.add("0:a?");
            command.add("-c:a");
            command.add("aac");
        }

        if (hasMusic) {
            command.add("-shortest");
        }

        // --- OUTPUTS (Multi-streaming) ---
        for (String key : streamKeys) {
            command.add("-f");
            command.add("flv");
            command.add("-flvflags");
            command.add("no_duration_filesize");

            if (key.startsWith("rtmp")) {
                 command.add(key);
            } else {
                 command.add("rtmps://a.rtmp.youtube.com:443/live2/" + key);
            }
        }

        return command;
    }
}