package com.afklive.streamer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FFmpegCommandBuilder {

    private int loopCount = 1;
    private String streamMode = "original";
    private String watermarkPath;
    private String textOverlayPath;

    public void setLoop(int loopCount) {
        this.loopCount = loopCount;
    }

    public void setStreamMode(String streamMode) {
        this.streamMode = streamMode;
    }

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
                "ultrafast",
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
        command.add("ultrafast");
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
        command.add("-keyint_min");
        command.add("60");
        command.add("-sc_threshold");
        command.add("0");

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
            h = height;
            w = (int) Math.round(h * (9.0 / 16.0));
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
        command.add("ultrafast");
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
        command.add("-keyint_min");
        command.add("60");
        command.add("-sc_threshold");
        command.add("0");

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

    public void addWatermark(String watermarkPath) {
        this.watermarkPath = watermarkPath;
    }

    public void addTextOverlay(String textPath) {
        this.textOverlayPath = textPath;
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
        return buildStreamCommand(videoPath, streamKeys, musicPath, musicVolume, loopCount, watermarkPath, muteVideoAudio, streamMode, maxHeight, false, null);
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
            int maxHeight,
            boolean isOptimized
    ) {
        return buildStreamCommand(videoPath, streamKeys, musicPath, musicVolume, loopCount, watermarkPath, muteVideoAudio, streamMode, maxHeight, isOptimized, null);
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
            int maxHeight,
            boolean isOptimized,
            Path textOverlayPath
    ) {
        return buildStreamCommand(videoPath, streamKeys, musicPath, musicVolume, loopCount, watermarkPath,
                muteVideoAudio, streamMode, maxHeight, isOptimized, textOverlayPath, true);
    }

    /** Output frame rate. Fixed so the 2-second keyframe interval YouTube requires is exact. */
    static final int OUTPUT_FPS = 30;
    /** Keyframe every 2 seconds (YouTube requirement: 2 s recommended, never above 4 s). */
    static final int GOP = OUTPUT_FPS * 2;

    /**
     * Video bitrate ladder (kbps): target / maxrate / buffer. Each tier is at or above YouTube's
     * minimum for that resolution at 30 fps (720p 3 Mbps, 1080p 5, 1440p 7, 2160p 11).
     */
    static String[] bitrateFor(int height) {
        if (height >= 2160) return new String[]{"15000k", "20000k", "40000k"};
        if (height >= 1440) return new String[]{"9000k", "10000k", "18000k"};
        if (height >= 1080) return new String[]{"6000k", "6500k", "12000k"};
        return new String[]{"4000k", "4500k", "8000k"};
    }

    /**
     * Builds the FFmpeg command for one destination.
     *
     * @param sourceHasAudio false when the video has no audio track; a silent track is then added,
     *                       because YouTube keeps a stream in "preparing" without audio
     */
    public static List<String> buildStreamCommand(
            Path videoPath,
            List<String> streamKeys,
            Path musicPath,
            String musicVolume,
            int loopCount,
            Path watermarkPath,
            boolean muteVideoAudio,
            String streamMode,
            int maxHeight,
            boolean isOptimized,
            Path textOverlayPath,
            boolean sourceHasAudio
    ) {
        String ffmpeg = "ffmpeg";
        java.io.File local = new java.io.File("bin/ffmpeg");
        if (local.exists()) ffmpeg = local.getAbsolutePath();

        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");

        // Global settings
        command.add("-re");
        command.add("-stream_loop");
        command.add(String.valueOf(loopCount));
        command.add("-fflags");
        command.add("+genpts");

        // Input 0: Video
        command.add("-i");
        command.add(videoPath.toString());

        boolean hasMusic = musicPath != null;
        // Silence is needed when the output would otherwise have no audio at all.
        boolean needsSilence = !hasMusic && (muteVideoAudio || !sourceHasAudio);

        if (isOptimized) {
            // STREAM COPY MODE (low CPU): the file was normalized at upload to the stream profile.
            if (needsSilence) {
                command.add("-f"); command.add("lavfi");
                command.add("-i"); command.add("anullsrc=channel_layout=stereo:sample_rate=44100");
                command.add("-map"); command.add("0:v:0");
                command.add("-map"); command.add("1:a:0");
            } else {
                command.add("-map"); command.add("0:v:0");
                command.add("-map"); command.add("0:a:0");
            }
            command.add("-c:v"); command.add("copy");
            // Audio is re-encoded (cheap) so the sample rate always matches what YouTube expects.
            command.add("-c:a"); command.add("aac");
            command.add("-b:a"); command.add("128k");
            command.add("-ar"); command.add("44100");
            command.add("-ac"); command.add("2");
        } else {
            // RE-ENCODE MODE

            // Input 1: Audio (music or silence)
            if (hasMusic) {
                command.add("-stream_loop");
                command.add("-1");
                command.add("-i");
                command.add(musicPath.toString());
            } else if (needsSilence) {
                command.add("-f");
                command.add("lavfi");
                command.add("-i");
                command.add("anullsrc=channel_layout=stereo:sample_rate=44100");
            }

            // Input 2: Watermark
            boolean hasWatermark = watermarkPath != null;
            int wmIdx = (hasMusic || needsSilence) ? 2 : 1;
            if (hasWatermark) {
                command.add("-i");
                command.add(watermarkPath.toString());
            }

            // --- Filters ---
            List<String> filterChains = new ArrayList<>();
            String vLabel;
            String aLabel = "0:a";

            int safeMaxHeight = (maxHeight > 0) ? maxHeight : 1080;
            String scaleFilter;
            if ("force_portrait".equals(streamMode)) {
                int h = safeMaxHeight;
                int w = (int) Math.round(h * (9.0 / 16.0));
                if (w % 2 != 0) w++;
                if (h % 2 != 0) h++;
                scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1", w, h, w, h);
            } else {
                scaleFilter = String.format("scale=-2:min(ih\\,%d),pad=ceil(iw/2)*2:ceil(ih/2)*2", safeMaxHeight);
            }

            // Constant frame rate so keyframes land exactly every 2 seconds.
            filterChains.add("[0:v]" + scaleFilter + ",fps=" + OUTPUT_FPS + "[scaled]");
            vLabel = "[scaled]";

            if (hasWatermark) {
                filterChains.add(String.format("[%d:v]scale=iw*0.15:-1[wm]", wmIdx));
                filterChains.add(String.format("[%s][wm]overlay=main_w-overlay_w-20:20[vwm]", vLabel));
                vLabel = "[vwm]";
            }

            // Text Overlay (Subscribers)
            if (textOverlayPath != null) {
                String safePath = textOverlayPath.toString().replace("\\", "/").replace(":", "\\:");
                String nextLabel = "[vtext]";
                String drawText = String.format("drawtext=textfile='%s':reload=1:fontcolor=white:fontsize=24:box=1:boxcolor=black@0.6:boxborderw=5:x=20:y=h-th-20", safePath);
                filterChains.add(String.format("[%s]%s%s", vLabel.replace("[", "").replace("]", ""), drawText, nextLabel));
                vLabel = nextLabel;
            }

            // Audio
            if (hasMusic) {
                if (muteVideoAudio || !sourceHasAudio) {
                    filterChains.add(String.format("[1:a]volume=%s,aresample=44100[aout]", musicVolume));
                } else {
                    filterChains.add("[0:a]aresample=44100,asetpts=PTS-STARTPTS[a0]");
                    filterChains.add(String.format("[1:a]volume=%s,aresample=44100,asetpts=PTS-STARTPTS[a1]", musicVolume));
                    filterChains.add("[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[aout]");
                }
                aLabel = "[aout]";
            } else if (needsSilence) {
                aLabel = "1:a";
            }

            command.add("-filter_complex");
            command.add(String.join(";", filterChains));

            // --- Encoding ---
            String[] rate = bitrateFor(safeMaxHeight);
            command.add("-map"); command.add(vLabel);
            command.add("-c:v"); command.add("libx264");
            command.add("-preset"); command.add("veryfast");
            command.add("-b:v"); command.add(rate[0]);
            command.add("-maxrate"); command.add(rate[1]);
            command.add("-bufsize"); command.add(rate[2]);
            command.add("-pix_fmt"); command.add("yuv420p");
            command.add("-g"); command.add(String.valueOf(GOP));
            command.add("-keyint_min"); command.add(String.valueOf(GOP));
            command.add("-sc_threshold"); command.add("0");

            command.add("-map"); command.add(aLabel.equals("0:a") ? "0:a:0" : aLabel);
            command.add("-c:a"); command.add("aac");
            command.add("-b:a"); command.add("128k");
            command.add("-ar"); command.add("44100");
            command.add("-ac"); command.add("2");
        }

        command.add("-shortest");

        // --- Output ---
        String key = streamKeys.get(0).trim();
        // Only a full URL is used as-is; anything else is a bare key for YouTube's ingest.
        // (Checking for "://" matters: a bare key like "rtmp_x.flv" must never become a local output file.)
        String url = key.contains("://") ? key : "rtmp://a.rtmp.youtube.com:1935/live2/" + key;

        command.add("-f");
        command.add(url.regionMatches(true, 0, "srt://", 0, 6) ? "mpegts" : "flv");
        command.add(url);

        return command;
    }

    public static List<String> buildProbeCommand(Path input) {
        String ffprobe = "ffprobe";
        java.io.File lambda = new java.io.File("/opt/bin/ffprobe");
        if (lambda.exists()) {
            ffprobe = lambda.getAbsolutePath();
        } else {
            java.io.File local = new java.io.File("bin/ffprobe");
            if (local.exists()) ffprobe = local.getAbsolutePath();
        }

        List<String> command = new ArrayList<>();
        command.add(ffprobe);
        command.add("-v");
        command.add("error");
        command.add("-select_streams");
        command.add("v:0");
        command.add("-show_entries");
        command.add("stream=codec_name");
        command.add("-of");
        command.add("default=noprint_wrappers=1:nokey=1");
        command.add(input.toString());
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
