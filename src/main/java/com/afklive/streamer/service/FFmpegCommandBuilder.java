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

        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-i");
        command.add(input.toString());
        command.add("-vf");
        command.add("split[original][copy];[copy]scale=-1:1920,crop=w=1080:h=1920,gblur=sigma=20[blurred];[original]scale=1080:-1[scaled];[blurred][scaled]overlay=0:(H-h)/2");

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("superfast");
        command.add("-b:v");
        command.add("4500k");
        command.add("-maxrate");
        command.add("6000k");
        command.add("-bufsize");
        command.add("12000k");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-r");
        command.add("30");
        command.add("-g");
        command.add("60");

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

    public static List<String> buildOptimizeCommand(Path input, Path output, String mode, int height) {
        List<String> command = new ArrayList<>();
        command.add("nice");
        command.add("-n");
        command.add("15");
        command.add("ffmpeg");
        command.add("-i");
        command.add(input.toString());

        // Resolution Logic
        int w, h;
        if ("portrait".equalsIgnoreCase(mode)) {
            // 9:16
            // If input is 1920x1080, we need to crop/blur to e.g. 1080x1920
            // Logic: standard shorts is 1080x1920.
            // If user selected different height (e.g. 720p), calculate w.
            h = height;
            w = (int) Math.round(h * (9.0 / 16.0));
            // Ensure even
            if (w % 2 != 0) w++;

            // Blur background effect for portrait
            String filter = String.format("split[original][copy];[copy]scale=-1:%d,crop=w=%d:h=%d,gblur=sigma=20[blurred];[original]scale=%d:-1[scaled];[blurred][scaled]overlay=(W-w)/2:(H-h)/2", h, w, h, w);
            command.add("-vf");
            command.add(filter);

        } else {
            // Landscape (Default)
            h = height;
            w = (int) Math.round(h * (16.0 / 9.0));
            if (w % 2 != 0) w++;

            String filter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2", w, h, w, h);
            command.add("-vf");
            command.add(filter);
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-profile:v");
        command.add("high");

        // Bitrate based on resolution
        String bitrate = "4500k";
        String maxrate = "4500k";
        String bufsize = "9000k";

        if (height >= 2160) { // 4K
            bitrate = "12000k"; maxrate = "20000k"; bufsize = "40000k";
        } else if (height >= 1440) { // 2K
             bitrate = "8000k"; maxrate = "12000k"; bufsize = "24000k";
        } else if (height <= 720) {
             bitrate = "2500k"; maxrate = "3500k"; bufsize = "7000k";
        }

        command.add("-b:v"); command.add(bitrate);
        command.add("-maxrate"); command.add(maxrate);
        command.add("-bufsize"); command.add(bufsize);

        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-r");
        command.add("30");
        command.add("-g");
        command.add("60");

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

    // Keep old signature for backward compatibility if needed, though we should update callers
    public static List<String> buildOptimizeCommand(Path input, Path output) {
        return buildOptimizeCommand(input, output, "landscape", 1080);
    }

    public static List<String> buildMixCommand(Path videoPath, Path audioPath, String volume, Path outputPath) {
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-i");
        command.add(videoPath.toString());
        command.add("-stream_loop");
        command.add("-1");
        command.add("-i");
        command.add(audioPath.toString());
        command.add("-filter_complex");
        // Robust mix with resample
        command.add("[1:a]volume=" + volume + ",aresample=44100[a1];[0:a]aresample=44100[a0];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[aout]");
        command.add("-map");
        command.add("0:v");
        command.add("-map");
        command.add("[aout]");
        command.add("-c:v");
        command.add("copy");
        command.add("-c:a");
        command.add("aac");
        command.add("-ar");
        command.add("44100");
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
             // Blur logic for portrait stream from landscape source could go here,
             // but simpler to just scale/pad or use optimized video.
             // For on-the-fly, we keep it simple to avoid CPU overload.
        }

        String scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1", w, h, w, h);
        filterChains.add("[0:v]" + scaleFilter + "[scaled]");
        vLabel = "[scaled]";

        if (hasWatermark) {
            filterChains.add(String.format("[%d:v]scale=iw*0.15:-1[wm]", wmIdx));
            filterChains.add(String.format("[%s][wm]overlay=main_w-overlay_w-20:20[vout]", vLabel));
            vLabel = "[vout]";
        }

        // Audio Logic
        if (hasMusic) {
            if (muteVideoAudio) {
                 // Video audio muted, just use music
                filterChains.add(String.format("[1:a]volume=%s,aresample=44100[aout]", musicVolume));
            } else {
                // Mix: Ensure timestamps and sample rates align to avoid "Preparing" hangs
                filterChains.add("[0:a]aresample=44100,asetpts=PTS-STARTPTS[a0]");
                filterChains.add(String.format("[1:a]volume=%s,aresample=44100,asetpts=PTS-STARTPTS[a1]", musicVolume));
                filterChains.add("[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[aout]");
            }
            aLabel = "[aout]";
        } else if (muteVideoAudio) {
            // Silence
            aLabel = "1:a";
        }

        command.add("-filter_complex");
        command.add(String.join(";", filterChains));

        // --- Encoding ---
        command.add("-map"); command.add(vLabel);
        command.add("-c:v"); command.add("libx264");
        command.add("-preset"); command.add("veryfast");
        command.add("-b:v"); command.add("4000k"); // Increased slightly
        command.add("-maxrate"); command.add("4500k");
        command.add("-bufsize"); command.add("9000k");
        command.add("-pix_fmt"); command.add("yuv420p");
        command.add("-g"); command.add("60");

        // Map audio
        if (aLabel.equals("0:a")) {
             // If we rely on 0:a, use ? to avoid failure if missing, but Youtube needs audio.
             // For robustness, if user didn't mute and didn't provide music, we take 0:a.
             command.add("-map"); command.add("0:a?");
        } else {
             command.add("-map"); command.add(aLabel);
        }

        command.add("-c:a"); command.add("aac");
        command.add("-b:a"); command.add("128k");
        command.add("-ar"); command.add("44100");

        // Shortest output (video length)
        command.add("-shortest");

        // --- Output ---
        // Support for multiple keys if needed, but for now take first
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
        command.add("ultrafast");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");

        command.add("-y");
        command.add(output.toString());

        return command;
    }
}
