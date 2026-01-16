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
        command.add("-f");
        command.add("flv");
        command.add("-flvflags");
        command.add("no_duration_filesize");
        command.add("rtmps://a.rtmp.youtube.com:443/live2/" + streamKey);

        return command;
    }
}