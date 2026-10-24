package com.afklive.streamer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    /**
     * What we need to know about a file. {@code height}/{@code fps} are 0 when unknown;
     * {@code hasAudio} is null when the file couldn't be probed.
     */
    public record MediaInfo(String videoCodec, int width, int height, double fps, Boolean hasAudio, double durationSeconds) {
        public static final MediaInfo UNKNOWN = new MediaInfo(null, 0, 0, 0, null, 0);

        public MediaInfo(String videoCodec, int width, int height, double fps, Boolean hasAudio) {
            this(videoCodec, width, height, fps, hasAudio, 0);
        }
    }

    public MediaInfo probe(Path file) {
        return probe(file.toString());
    }

    /** Probes a local path or an http(s) URL (ffprobe reads only what it needs, with range requests). */
    public MediaInfo probe(String file) {
        List<String> command = List.of(ffprobe(), "-v", "error", "-print_format", "json", "-show_streams", "-show_format", file);
        Path out = null;
        try {
            // Output goes to a file so a hung ffprobe can't block us before the timeout applies.
            out = java.nio.file.Files.createTempFile("probe-", ".json");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffprobe timed out for {}", file);
                return MediaInfo.UNKNOWN;
            }
            if (process.exitValue() != 0) {
                log.warn("ffprobe failed for {} (exit {})", file, process.exitValue());
                return MediaInfo.UNKNOWN;
            }
            return parse(java.nio.file.Files.readAllBytes(out));
        } catch (IOException e) {
            log.warn("ffprobe could not run for {}", file, e);
            return MediaInfo.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MediaInfo.UNKNOWN;
        } finally {
            if (out != null) {
                try {
                    java.nio.file.Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }

    /**
     * AU-5: integrated loudness (EBU R128) of a file's sound in LUFS, or null if it can't be measured
     * or is silent. Decodes the whole sound track once (at low priority); a few seconds per hour of audio.
     */
    public Double loudness(String file) {
        List<String> command = new java.util.ArrayList<>();
        if (java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/nice"))) {
            command.addAll(List.of("/usr/bin/nice", "-n", "15"));
        }
        command.addAll(List.of(com.afklive.streamer.service.FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-nostdin", "-nostats",
                "-i", file, "-vn", "-sn", "-dn", "-af", "ebur128=framelog=quiet", "-f", "null", "-"));
        Path err = null;
        try {
            err = java.nio.file.Files.createTempFile("loudness-", ".log");
            Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(err.toFile()).start();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? parseLoudness(java.nio.file.Files.readString(err)) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (err != null) {
                try {
                    java.nio.file.Files.deleteIfExists(err);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }

    /** The "I: -16.2 LUFS" line of ebur128's summary; null for silence (-70 LUFS) or no summary. */
    static Double parseLoudness(String log) {
        int summary = log.lastIndexOf("Summary:");
        if (summary < 0) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("I:\\s+(-?[0-9.]+|-inf) LUFS").matcher(log.substring(summary));
        if (!m.find() || m.group(1).equals("-inf")) {
            return null;
        }
        double v = Double.parseDouble(m.group(1));
        return v <= -69 ? null : v;
    }

    /**
     * The length of a file whose header doesn't say (browser and screen recordings in WebM/MKV, some
     * phone apps): reads it through once without decoding (stream copy, fast) and takes the last
     * timestamp FFmpeg reports. 0 when it can't be measured.
     */
    public double measureDuration(String file) {
        List<String> command = List.of(com.afklive.streamer.service.FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-nostdin",
                "-i", file, "-map", "0", "-c", "copy", "-f", "null", "-");
        Path err = null;
        try {
            err = java.nio.file.Files.createTempFile("duration-", ".log");
            Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(err.toFile()).start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return 0;
            }
            return process.exitValue() == 0 ? parseLastTime(java.nio.file.Files.readString(err)) : 0;
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            if (err != null) {
                try {
                    java.nio.file.Files.deleteIfExists(err);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }

    static final int STILL_W = 64;
    static final int STILL_H = 36;
    /** Where frames are sampled, as fractions of the length. */
    static final double[] STILL_SAMPLES = {0.05, 0.2, 0.4, 0.6, 0.8, 0.95};

    /**
     * GR-9: whether a video is one still picture from start to end (a "static image" video). Grabs a
     * small grey frame at six points from start to end (a seek each, no full decode) and compares
     * them. True only when every frame matches the first; null when any frame can't be read, so the
     * go-live check never guesses.
     */
    public Boolean stillPicture(String file, double durationSeconds) {
        if (durationSeconds <= 0) {
            return null;
        }
        List<byte[]> frames = new java.util.ArrayList<>();
        for (double f : STILL_SAMPLES) {
            byte[] frame = greyFrame(file, durationSeconds * f);
            if (frame == null) {
                return null;
            }
            frames.add(frame);
        }
        return sameFrames(frames);
    }

    /**
     * Every frame matches the first: on average within one grey level, and no pixel off by more than
     * 16 (a small moving element, a ticking clock or rain changes some pixel by far more at this size;
     * compression noise averages out when 1080p is shrunk to 64x36).
     */
    static boolean sameFrames(List<byte[]> frames) {
        byte[] first = frames.getFirst();
        for (byte[] f : frames) {
            if (f.length != first.length) {
                return false;
            }
            long sum = 0;
            int max = 0;
            for (int i = 0; i < f.length; i++) {
                int d = Math.abs((f[i] & 0xff) - (first[i] & 0xff));
                sum += d;
                max = Math.max(max, d);
            }
            if (max > 16 || sum > first.length) {
                return false;
            }
        }
        return true;
    }

    private static byte[] greyFrame(String file, double atSeconds) {
        List<String> command = List.of(com.afklive.streamer.service.FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-nostdin",
                "-loglevel", "error", "-ss", String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, atSeconds)), "-i", file,
                "-frames:v", "1", "-an", "-vf", "scale=" + STILL_W + ":" + STILL_H + ":flags=area,format=gray",
                "-f", "rawvideo", "-y");
        Path out = null;
        try {
            out = java.nio.file.Files.createTempFile("still-", ".gray");
            List<String> cmd = new java.util.ArrayList<>(command);
            cmd.add(out.toString());
            // Output goes to a file, so a stalled read from storage can't block us past the timeout.
            Process process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(out);
            return process.exitValue() == 0 && bytes.length == STILL_W * STILL_H ? bytes : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    java.nio.file.Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }

    /** The last "time=HH:MM:SS.xx" in FFmpeg's progress output, in seconds. */
    static double parseLastTime(String log) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("time=(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)").matcher(log);
        double last = 0;
        while (m.find()) {
            last = Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
        }
        return last;
    }

    static MediaInfo parse(byte[] ffprobeJson) throws IOException {
        JsonNode root = JSON.readTree(ffprobeJson);
        JsonNode streams = root.path("streams");
        double duration = root.path("format").path("duration").asDouble(0);
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
        return new MediaInfo(codec, width, height, fps, hasAudio, duration);
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
        for (String candidate : List.of("/opt/bin/ffprobe", "bin/ffprobe")) {
            java.io.File f = new java.io.File(candidate);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        return "ffprobe";
    }
}
