package com.afklive.streamer.streaming;

import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.FFmpegCommandBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The preset families in {@link EncoderSettings} checked against real x264: presets of one family write
 * byte-identical SPS/PPS (so copy mode can hand a stream between a profile copy and the studio), presets of
 * different families don't.
 */
public class EncoderSettingsFfmpegTest {

    @TempDir
    static Path dir;
    static final Map<String, List<String>> SETS = new LinkedHashMap<>();

    /** One second through the live encoder at the default canvas (720x1280, 30 fps, 4 Mbps). */
    static byte[] encode(EncoderSettings encoder, int width, int height) throws Exception {
        Path out = dir.resolve(encoder.preset() + "-" + width + "x" + height + "-" + encoder.threads() + ".ts");
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=size=" + width + "x" + height + ":rate=30", "-f", "lavfi", "-i", "sine=r=44100", "-t", "1"));
        cmd.addAll(FFmpegCommandBuilder.liveEncodeArgs(Math.min(width, height), 30, encoder));
        cmd.addAll(List.of("-f", "mpegts", out.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        return Files.readAllBytes(out);
    }

    /** The H.264 sequence and picture parameter sets (NAL types 7 and 8) of an MPEG-TS stream's first keyframe, as hex. */
    public static List<String> parameterSets(byte[] ts) throws Exception {
        Path in = Files.createTempFile("ps", ".ts");
        Path h264 = Files.createTempFile("ps", ".h264");
        byte[] b;
        try {
            Files.write(in, ts);
            Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", in.toString(), "-map", "0:v:0", "-c", "copy",
                    "-frames:v", "1", "-f", "h264", h264.toString()).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            assertThat(p.waitFor()).as(log).isZero();
            b = Files.readAllBytes(h264);
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(h264);
        }
        List<String> sets = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                starts.add(i + 3);
                i += 2;
            }
        }
        for (int k = 0; k < starts.size(); k++) {
            int s = starts.get(k);
            int e = k + 1 < starts.size() ? starts.get(k + 1) - 3 : b.length;
            while (e > s && b[e - 1] == 0) {
                e--; // the leading zero of a 4-byte start code
            }
            int type = b[s] & 0x1f;
            if (type == 7 || type == 8) {
                sets.add(type + ":" + HexFormat.of().formatHex(b, s, e));
            }
        }
        return sets;
    }

    @BeforeAll
    static void encodeEveryPreset() throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        for (String preset : EncoderSettings.PRESETS) {
            SETS.put(preset, parameterSets(encode(new EncoderSettings(preset, 0), 720, 1280)));
        }
    }

    @Test
    void presetsOfOneFamilyWriteTheSameParameterSetsAndOthersDont() {
        for (String a : EncoderSettings.PRESETS) {
            assertThat(SETS.get(a)).as(a).anyMatch(s -> s.startsWith("7:")).anyMatch(s -> s.startsWith("8:"));
            for (String b : EncoderSettings.PRESETS) {
                boolean sameFamily = EncoderSettings.family(a).equals(EncoderSettings.family(b));
                if (sameFamily) {
                    assertThat(SETS.get(a)).as(a + " vs " + b).isEqualTo(SETS.get(b));
                } else {
                    assertThat(SETS.get(a)).as(a + " vs " + b).isNotEqualTo(SETS.get(b));
                }
            }
        }
    }

    @Test
    void theNewDefaultMatchesWhatProfileCopiesWereMadeWithAt1080pToo() throws Exception {
        assertThat(parameterSets(encode(new EncoderSettings(EncoderSettings.DEFAULT_PRESET, 0), 1920, 1080)))
                .isEqualTo(parameterSets(encode(new EncoderSettings(EncoderSettings.LEGACY_PRESET, 0), 1920, 1080)));
    }

    @Test
    void aThreadCapDoesNotChangeTheStream() throws Exception {
        assertThat(parameterSets(encode(new EncoderSettings(EncoderSettings.DEFAULT_PRESET, 2), 720, 1280)))
                .isEqualTo(SETS.get(EncoderSettings.DEFAULT_PRESET));
    }
}
