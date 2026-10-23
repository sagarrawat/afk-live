package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The stitcher on real FFmpeg output: two separately encoded programmes become one continuous one. */
public class TsStitcherTest {

    @TempDir
    static Path dir;
    static byte[] a;
    static byte[] b;
    static byte[] wrapping;

    static byte[] encode(String name, String video, double freq, String... extra) throws Exception {
        Path out = dir.resolve(name + ".ts");
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", video, "-f", "lavfi",
                "-i", "sine=f=" + freq + ":r=44100", "-t", "4"));
        cmd.addAll(FFmpegCommandBuilder.liveEncodeArgs(360, 30));
        cmd.addAll(List.of(extra));
        cmd.addAll(List.of("-f", "mpegts", "-mpegts_flags", "+resend_headers", out.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        return Files.readAllBytes(out);
    }

    @BeforeAll
    static void sources() throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        a = encode("a", "testsrc2=size=640x360:rate=30", 440);
        b = encode("b", "testsrc=size=640x360:rate=30", 880);
        // Starts 2 s before the 33-bit timestamp rollover (2^33 / 90 kHz = 95443.7 s).
        wrapping = encode("w", "smptebars=size=640x360:rate=30", 660, "-output_ts_offset", "95441.7");
    }

    public record Packet(String type, double pts, double dts) {
    }

    /** Packet timestamps per stream as FFmpeg's own demuxer reads them, unwrapped as it does. */
    public static List<Packet> probe(Path file) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "packet=codec_type,pts_time,dts_time",
                "-of", "csv=p=0", file.toString()).redirectErrorStream(true).start();
        List<Packet> out = new ArrayList<>();
        for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) {
            String[] f = line.split(",");
            if (f.length >= 3 && !f[1].equals("N/A")) {
                out.add(new Packet(f[0], Double.parseDouble(f[1]), f[2].equals("N/A") ? Double.NaN : Double.parseDouble(f[2])));
            }
        }
        p.waitFor();
        return out;
    }

    public static void assertContinuous(List<Packet> packets, double expectedSeconds) {
        for (String type : List.of("video", "audio")) {
            List<Packet> s = packets.stream().filter(x -> x.type().equals(type)).toList();
            assertThat(s).as(type).isNotEmpty();
            double prev = Double.NEGATIVE_INFINITY;
            double maxGap = 0;
            for (Packet x : s) {
                double t = type.equals("video") ? x.dts() : x.pts();
                assertThat(t).as(type + " timestamps only go forward").isGreaterThan(prev);
                if (prev != Double.NEGATIVE_INFINITY) {
                    maxGap = Math.max(maxGap, t - prev);
                }
                prev = t;
            }
            assertThat(maxGap).as(type + " has no hole in the timeline").isLessThan(0.25);
            double span = (type.equals("video") ? s.getLast().dts() : s.getLast().pts())
                    - (type.equals("video") ? s.getFirst().dts() : s.getFirst().pts());
            assertThat(span).as(type + " length").isBetween(expectedSeconds - 0.6, expectedSeconds + 0.3);
        }
    }

    static Path run(String name, java.util.function.Consumer<TsStitcher> script, List<Integer> switches) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TsStitcher s = new TsStitcher(out::writeBytes, switches::add);
        script.accept(s);
        Path file = dir.resolve(name + "-out.ts");
        Files.write(file, out.toByteArray());
        return file;
    }

    static void feedInChunks(TsStitcher s, int id, byte[] data, int from, int to) {
        // Odd chunk sizes, like pipe reads: packets arrive split.
        for (int i = from; i < to; i += 65_000) {
            int n = Math.min(65_000, to - i);
            s.feed(id, java.util.Arrays.copyOfRange(data, i, i + n), n);
        }
    }

    @Test
    void aPlannedSwitchContinuesTheTimelineWithoutAHole() throws Exception {
        List<Integer> switched = new ArrayList<>();
        int half = (a.length / 2 / TsStitcher.PACKET) * TsStitcher.PACKET + 77; // mid-packet on purpose
        Path out = run("planned", s -> {
            s.expect(1);
            feedInChunks(s, 1, a, 0, half);
            s.expect(2);
            feedInChunks(s, 2, b, 0, b.length);            // held: the old one is still playing
            feedInChunks(s, 1, a, half, a.length);         // switches at a's next frame boundary
        }, switched);
        assertThat(switched).containsExactly(2);
        // About half of a, then all of b.
        assertContinuous(probe(out), 2 + 4);
        Process decode = new ProcessBuilder("ffmpeg", "-v", "error", "-i", out.toString(), "-f", "null", "-").redirectErrorStream(true).start();
        String errors = new String(decode.getInputStream().readAllBytes());
        assertThat(decode.waitFor()).isZero();
        assertThat(errors.lines().count()).as(errors).isLessThan(5);
    }

    @Test
    void aProducerThatDiedIsReplacedAtTheNewOnesFirstKeyframe() throws Exception {
        List<Integer> switched = new ArrayList<>();
        Path out = run("crash", s -> {
            s.expect(1);
            feedInChunks(s, 1, a, 0, a.length);
            s.ended(1);
            s.expect(2);
            feedInChunks(s, 2, b, 0, b.length);
        }, switched);
        assertThat(switched).containsExactly(2);
        assertContinuous(probe(out), 4 + 4);
    }

    @Test
    void theTimelineCarriesOnAcrossThe33BitRolloverAndAcrossProducers() throws Exception {
        List<Integer> switched = new ArrayList<>();
        Path out = run("wrap", s -> {
            s.expect(1);
            feedInChunks(s, 1, wrapping, 0, wrapping.length); // crosses the rollover after 2 s
            s.ended(1);
            s.expect(2);
            feedInChunks(s, 2, a, 0, a.length);                // a starts at 1.4 s: shifted past the rollover
            s.ended(2);
            s.expect(3);
            feedInChunks(s, 3, b, 0, b.length);
        }, switched);
        assertThat(switched).containsExactly(2, 3);
        assertContinuous(probe(out), 12);
    }

    @Test
    void theStitchedProgrammeRelaysToAPlatformFormat() throws Exception {
        Path ts = run("relay", s -> {
            s.expect(1);
            feedInChunks(s, 1, a, 0, a.length);
            s.ended(1);
            s.expect(2);
            feedInChunks(s, 2, b, 0, b.length);
        }, new ArrayList<>());
        Path flv = dir.resolve("relay.flv");
        List<String> relay = new ArrayList<>(FFmpegCommandBuilder.buildRelayCommand("file://" + flv));
        relay.set(relay.indexOf("pipe:0"), ts.toString());
        Process p = new ProcessBuilder(relay).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        assertContinuous(probe(flv), 8);
    }

    @Test
    void bytesFromAProducerThatWasReplacedAreDropped() throws Exception {
        List<Integer> switched = new ArrayList<>();
        Path out = run("late", s -> {
            s.expect(1);
            feedInChunks(s, 1, a, 0, a.length / 2);
            s.ended(1);
            s.expect(2);
            feedInChunks(s, 2, b, 0, b.length);
            feedInChunks(s, 1, a, a.length / 2, a.length); // the old process flushing its last bytes
        }, switched);
        assertThat(switched).containsExactly(2);
        assertContinuous(probe(out), 2 + 4);
    }
}
