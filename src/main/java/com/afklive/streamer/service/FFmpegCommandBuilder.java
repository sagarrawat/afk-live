package com.afklive.streamer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FFmpegCommandBuilder {

    public static List<String> buildConversionCommand(Path source, Path target) {
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        return List.of(
                ffmpeg,
                "-threads",
                "1",
                "-i",
                source.toString(),
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
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
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        // Convert Landscape to Portrait (9:16) with blurred background
        // ffmpeg -i input.mp4 -vf "split[original][copy];[copy]scale=-1:1920,crop=w=1080:h=1920,gblur=sigma=20[blurred];[original]scale=1080:-1[scaled];[blurred][scaled]overlay=0:(H-h)/2" -c:v libx264 -c:a copy output.mp4
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
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

    public static List<String> buildOptimizeCommand(Path input, Path output) {
        List<String> command = new ArrayList<>();
        command.add("nice");
        command.add("-n");
        command.add("15");
        command.add("ffmpeg");
        command.add("-i");
        command.add(input.toString());

        // Video: Standardize to 1080p 30fps H.264
        command.add("-vf");
        command.add("scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2");

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium"); // Higher quality per bitrate for offline processing
        command.add("-profile:v");
        command.add("high");
        command.add("-b:v");
        command.add("4500k");
        command.add("-maxrate");
        command.add("4500k");
        command.add("-bufsize");
        command.add("9000k");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-r");
        command.add("30");
        command.add("-g");
        command.add("60"); // 2-second GOP

        // Audio: AAC 128k
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-ar");
        command.add("44100");

        command.add("-movflags");
        command.add("+faststart");

        command.add("-y");
        command.add(output.toString());
        return command;
    }

    public static List<String> buildMixCommand(Path videoPath, Path audioPath, String volume, Path outputPath) {
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        // ffmpeg -i video.mp4 -stream_loop -1 -i audio.mp3 -filter_complex "[1:a]volume=0.5[a1];[0:a][a1]amix=inputs=2:duration=first[aout]" -map 0:v -map "[aout]" -c:v copy -c:a aac -y out.mp4
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
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
            Path watermarkPath,
            boolean muteVideoAudio,
            String streamMode,
            int maxHeight
    ) {
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        List<String> command = new ArrayList<>();
        command.add(ffmpeg);

        // Global settings
        command.add("-re");
        command.add("-stream_loop");
        command.add(String.valueOf(loopCount));
        command.add("-fflags");
        command.add("+genpts");

        // Input 0: Video
        command.add("-i");
        command.add(videoPath.toString());

        // Input 1: Audio (Music or Silence)
        boolean hasMusic = musicPath != null;
        if (hasMusic) {
            command.add("-stream_loop");
            command.add("-1");
            command.add("-i");
            command.add(musicPath.toString());
        } else if (muteVideoAudio) {
            command.add("-f");
            command.add("lavfi");
            command.add("-i");
            command.add("anullsrc=channel_layout=stereo:sample_rate=44100");
        }

        // Input 2: Watermark
        boolean hasWatermark = watermarkPath != null;
        int wmIdx = (hasMusic || muteVideoAudio) ? 2 : 1;
        if (hasWatermark) {
            command.add("-i");
            command.add(watermarkPath.toString());
        }

        // --- Filters ---
        List<String> filterChains = new ArrayList<>();
        String vLabel = "0:v";
        String aLabel = "0:a";

        // Scaling logic (Force even dimensions to avoid libx264 crash)
        int h = (Math.min(1080, maxHeight) / 2) * 2;
        int w = ((h * 16 / 9) / 2) * 2; // Default to 16:9 even math

        if ("force_portrait".equals(streamMode)) {
            w = (Math.min(1080, maxHeight) / 2) * 2;
            h = ((w * 16 / 9) / 2) * 2;
        }

        String scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1", w, h, w, h);
        filterChains.add("[0:v]" + scaleFilter + "[scaled]");
        vLabel = "[scaled]";

        if (hasWatermark) {
            filterChains.add(String.format("[%d:v]scale=iw*0.15:-1[wm]", wmIdx));
            filterChains.add(String.format("[%s][wm]overlay=main_w-overlay_w-20:20[vout]", vLabel));
            vLabel = "[vout]";
        }

        if (hasMusic) {
            if (muteVideoAudio) {
                filterChains.add(String.format("[1:a]volume=%s[aout]", musicVolume));
            } else {
                filterChains.add(String.format("[0:a]volume=1.0[a0];[1:a]volume=%s[a1];[a0][a1]amix=inputs=2:duration=first[aout]", musicVolume));
            }
            aLabel = "[aout]";
        } else if (muteVideoAudio) {
            aLabel = "1:a";
        }

        command.add("-filter_complex");
        command.add(String.join(";", filterChains));

        // --- Encoding ---
        command.add("-map"); command.add(vLabel);
        command.add("-c:v"); command.add("libx264");
        command.add("-preset"); command.add("veryfast");
        command.add("-b:v"); command.add("3500k");
        command.add("-pix_fmt"); command.add("yuv420p");
        command.add("-g"); command.add("60");

        command.add("-map"); command.add(aLabel.equals("0:a") ? "0:a?" : aLabel);
        command.add("-c:a"); command.add("aac");
        command.add("-b:a"); command.add("128k");
        command.add("-ar"); command.add("44100");
        command.add("-shortest");

        // --- Output ---
        // For testing, we stream to the first key directly without 'tee'
        String key = streamKeys.get(0);
        String url = key.startsWith("rtmp") ? key : "rtmp://a.rtmp.youtube.com:1935/live2/" + key;

        command.add("-f");
        command.add("flv");
        command.add(url);

        return command;
    }

    public static List<String> buildMergeCommand(List<Path> inputs, Path output) {
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        // [0:v][0:a][1:v][1:a]concat=n=2:v=1:a=1[v][a]
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);

        for (Path input : inputs) {
            command.add("-i");
            command.add(input.toString());
        }

        StringBuilder filterComplex = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            filterComplex.append("[").append(i).append(":v]")
                    .append("[").append(i).append(":a]");
        }
        filterComplex.append("concat=n=").append(inputs.size()).append(":v=1:a=1[v][a]");

        command.add("-filter_complex");
        command.add(filterComplex.toString());
        command.add("-map");
        command.add("[v]");
        command.add("-map");
        command.add("[a]");

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast"); // fast merge
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");

        command.add("-y");
        command.add(output.toString());

        return command;
    }
}