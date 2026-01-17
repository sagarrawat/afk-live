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
            String streamKey,
            Path musicPath,
            String musicVolume,
            int loopCount
    ) {
        List<String> command = new ArrayList<>();
        command.add("nice");
        command.add("-n");
        command.add("19");
        command.add("ffmpeg");

        // Video input
        command.add("-re");
        command.add("-stream_loop");
        command.add(String.valueOf(loopCount));
        command.add("-i");
        command.add(videoPath.toString());

        // Music input if provided
        boolean hasMusic = musicPath != null;
        if (hasMusic) {
            command.add("-stream_loop");
            command.add("-1");
            command.add("-i");
            command.add(musicPath.toString());
        }

        // Video stream mapping
        command.add("-map");
        command.add("0:v:0");
        command.add("-c:v");
        command.add("copy");

        // Audio handling
        if (hasMusic) {
            command.add("-map");
            command.add("1:a:0");
            command.add("-filter:a");
            command.add("volume=" + musicVolume);
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

        // Output configuration
        if (hasMusic) {
            command.add("-shortest");
        }

        command.add("-f");
        command.add("flv");
        command.add("-flvflags");
        command.add("no_duration_filesize");
        command.add("rtmps://a.rtmp.youtube.com:443/live2/" + streamKey);

        return command;
    }
}