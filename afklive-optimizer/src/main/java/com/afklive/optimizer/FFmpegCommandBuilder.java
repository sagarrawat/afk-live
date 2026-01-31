package com.afklive.optimizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FFmpegCommandBuilder {

    public static List<String> buildOptimizeCommand(Path input, Path output, String mode, int height) {
        String ffmpeg = "ffmpeg";
        java.io.File lambda = new java.io.File("/opt/bin/ffmpeg");
        if (lambda.exists()) {
             ffmpeg = lambda.getAbsolutePath();
        } else {
             java.io.File local = new java.io.File("bin/ffmpeg");
             if (local.exists()) ffmpeg = local.getAbsolutePath();
        }

        List<String> command = new ArrayList<>();
        // In Lambda, we want max performance, so skip nice.
        command.add(ffmpeg);
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

    public static List<String> buildOptimizeCommand(Path input, Path output) {
        return buildOptimizeCommand(input, output, "landscape", 1080);
    }
}
