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

        // Video input (Index 0)
        command.add("-re");
        command.add("-stream_loop");
        command.add(String.valueOf(loopCount));
        command.add("-fflags");
        command.add("+genpts");
        command.add("-i");
        command.add(videoPath.toString());

        // Music input (Index 1) if provided
        boolean hasMusic = musicPath != null;
        if (hasMusic) {
            command.add("-stream_loop");
            command.add("-1");
            command.add("-i");
            command.add(musicPath.toString());
        } else if (muteVideoAudio) {
            // Generate Silence if muted and no music
            command.add("-f");
            command.add("lavfi");
            command.add("-i");
            command.add("anullsrc=channel_layout=stereo:sample_rate=44100");
        }

        // Watermark input (Index 1, 2, or 3) if provided
        boolean hasWatermark = watermarkPath != null;
        if (hasWatermark) {
            command.add("-i");
            command.add(watermarkPath.toString());
        }

        // --- FILTER COMPLEX ---
        List<String> filterChains = new ArrayList<>();
        String videoLabel = "0:v";
        String audioLabel = "0:a";

        // Determine if we need video scaling/transcoding
        boolean forceTranscode = false;

        // Handle "streamMode" and Quality Limiting
        // If maxHeight is enforced, adjust target resolutions
        int targetH = (streamMode != null && streamMode.equals("force_landscape")) ? 1080 : 720;
        // Logic refinement:
        // if force_landscape (1080p target), but limit is 720, use 720.
        // if force_portrait (1080w target?), no, force_portrait is 1080x1920. If limit 720, usually means 720x1280.

        if (streamMode != null && !streamMode.equals("original")) {
            forceTranscode = true;
            String scaleFilter = "";
            if (streamMode.equals("force_landscape")) {
                // Target: 1920x1080 (16:9) OR smaller if restricted
                int h = Math.min(1080, maxHeight);
                int w = (h * 16) / 9;
                // Ensure even dimensions
                if (w % 2 != 0) w--;

                scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2", w, h, w, h);
            } else if (streamMode.equals("force_portrait")) {
                // Target: 1080x1920 (9:16) OR smaller
                // Portrait limit is usually width? If Plan says "720p", it means 720p (landscape).
                // For vertical, 720p equivalent is 720x1280.
                // So we assume maxHeight applies to the shorter dimension (width in portrait).
                // Actually maxHeight usually refers to vertical lines.
                // But for 9:16, "1080p" means 1080x1920 (1080 wide).
                // So if maxHeight=720, we want 720x1280.

                int w = Math.min(1080, maxHeight);
                int h = (w * 16) / 9;
                if (h % 2 != 0) h--;

                scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2", w, h, w, h);
            }

            // We apply this scale to [0:v] immediately
            filterChains.add("[0:v]" + scaleFilter + "[scaled]");
            videoLabel = "[scaled]";
        } else {
            // Original Mode: Enforce Max Height if input exceeds it
            // Logic: scale='if(gt(iw,ih),-2,min(iw,MAX))':'if(gt(iw,ih),min(ih,MAX),-2)'
            // Explanation:
            // If Landscape (iw > ih): Scale Height to min(ih, MAX), Width -2 (auto).
            // If Portrait (iw <= ih): Scale Width to min(iw, MAX), Height -2 (auto).
            // This ensures the short dimension never exceeds MAX.

            // We ALWAYS add this filter to ensure compliance, forcing transcode.
            // (Optimization: we could skip if we knew dimensions, but we don't here).
            forceTranscode = true;
            String limitFilter = String.format("scale='if(gt(iw,ih),-2,min(iw,%d))':'if(gt(iw,ih),min(ih,%d),-2)'", maxHeight, maxHeight);
            filterChains.add("[0:v]" + limitFilter + "[scaled]");
            videoLabel = "[scaled]";
        }

        // Handle Watermark
        if (hasWatermark) {
            forceTranscode = true;
            int watermarkIndex = (hasMusic || muteVideoAudio) ? 2 : 1; // Index shifts if silence is inserted
            // Overlay on whatever current videoLabel is (original or scaled)
            String overlayFilter = String.format("[%s][%d:v]overlay=main_w-overlay_w-20:20", videoLabel.equals("0:v") ? "0:v" : videoLabel, watermarkIndex);

            // Note: If we haven't scaled, we might want to scale the watermark RELATIVE to video.
            // But if we did scale, we know the size (1920 or 1080 wide).
            // Let's keep it simple: just overlay. If user uploads giant watermark, it covers screen.
            // Better: Scale watermark to 15% width of MAIN video.
            // We need a complex filter chain for watermark scaling too.
            // [wm_in]scale=iw*0.15:-1[wm_out];[main][wm_out]overlay...

            String wmScale = String.format("[%d:v]scale=iw*0.15:-1[wm]", watermarkIndex);
            filterChains.add(wmScale);

            String overlay = String.format("[%s][wm]overlay=main_w-overlay_w-20:20[vout]", videoLabel.equals("0:v") ? "0:v" : videoLabel);
            filterChains.add(overlay);
            videoLabel = "[vout]";
        }

        if (hasMusic) {
            if (muteVideoAudio) {
                String volFilter = String.format("[1:a]volume=%s[aout]", musicVolume);
                filterChains.add(volFilter);
                audioLabel = "[aout]";
            } else {
                String mixFilter = String.format("[1:a]volume=%s[a1];[0:a][a1]amix=inputs=2:duration=first[aout]", musicVolume);
                filterChains.add(mixFilter);
                audioLabel = "[aout]";
            }
        }

        if (!filterChains.isEmpty()) {
            command.add("-filter_complex");
            command.add(String.join(";", filterChains));
        }

        // --- ENCODING OPTIONS ---

        // Video Encoding
        // If optimized and no filters, use copy.
        // Note: forceTranscode is true if streamMode != original OR watermark exists.
        // So checking forceTranscode covers those cases.
        // We only check isOptimized to confirm we CAN copy safely if forceTranscode is false.
        // (Actually, the previous logic tried to copy if !forceTranscode, but didn't guarantee input quality).
        // With isOptimized, we are more confident in copying.

        if (forceTranscode || hasWatermark) {
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
            command.add("-map");
            command.add("0:v");
            command.add("-c:v");
            command.add("copy");
            // If isOptimized is true, this is ideal.
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
            if (muteVideoAudio) {
                // Map the silence generated at index 1
                command.add("-map");
                command.add("1:a");
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
                // IMPORTANT: -shortest to stop when video ends (silence is infinite)
                command.add("-shortest");
            } else {
                 command.add("-map");
                 command.add("0:a?");
                 command.add("-c:a");
                 command.add("aac");
            }
        }

        if (hasMusic) {
            command.add("-shortest");
        }

        // --- OUTPUTS (Multi-streaming) ---
        if (!streamKeys.isEmpty()) {
            command.add("-f");
            command.add("tee");
            command.add("-map");
            command.add(videoLabel);
            command.add("-map");
            // If audioLabel is default "0:a", make it optional "0:a?" to prevent fail on silent video
            command.add(audioLabel.equals("0:a") ? "0:a?" : audioLabel);

            StringBuilder teePayload = new StringBuilder();
            for (int i = 0; i < streamKeys.size(); i++) {
                String key = streamKeys.get(i);
                if (i > 0) teePayload.append("|");
                String url = key.startsWith("rtmp") ? key : "rtmps://a.rtmp.youtube.com:443/live2/" + key;
                teePayload.append("[f=flv:flvflags=no_duration_filesize]").append(url);
            }
            command.add(teePayload.toString());
        }

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