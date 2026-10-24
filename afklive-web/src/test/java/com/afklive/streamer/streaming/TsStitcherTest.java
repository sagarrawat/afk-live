package com.afklive.streamer.streaming;

import com.afklive.streamer.service.EncoderSettings;
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
        return encode(EncoderSettings.DEFAULT, name, video, freq, extra);
    }

    static byte[] encode(EncoderSettings encoder, String name, String video, double freq, String... extra) throws Exception {
        Path out = dir.resolve(name + ".ts");
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", video, "-f", "lavfi",
                "-i", "sine=f=" + freq + ":r=44100", "-t", "4"));
        cmd.addAll(FFmpegCommandBuilder.liveEncodeArgs(360, 30, encoder));
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
            // Audio travels in PES packets of up to ~0.2 s that FFmpeg interleaves loosely with video, so a switch
            // at a video frame boundary can leave up to a few tenths of a second of audio uncovered.
            assertThat(maxGap).as(type + " has no hole in the timeline").isLessThan(type.equals("video") ? 0.25 : 0.5);
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
    void aVeryfastProfileCopyAndASuperfastStudioFollowEachOtherCleanly() throws Exception {
        // During and after the rollout of the superfast default, copy mode plays profile copies made with veryfast
        // and hands over to (and back from) a studio encoding with superfast: copy -> studio -> copy.
        byte[] copy = encode(new EncoderSettings("veryfast", 0), "copy-vf", "testsrc2=size=640x360:rate=30", 440);
        byte[] studio = encode(new EncoderSettings("superfast", 0), "studio-sf", "testsrc=size=640x360:rate=30", 880);
        assertThat(EncoderSettingsFfmpegTest.parameterSets(studio))
                .as("same SPS/PPS, so decoders and the FLV relay's sequence header stay valid")
                .isEqualTo(EncoderSettingsFfmpegTest.parameterSets(copy));
        List<Integer> switched = new ArrayList<>();
        int half = (copy.length / 2 / TsStitcher.PACKET) * TsStitcher.PACKET + 77;
        Path out = run("mixed", s -> {
            s.expect(1);
            feedInChunks(s, 1, copy, 0, half);
            s.expect(2);
            feedInChunks(s, 2, studio, 0, studio.length);  // held while the copy plays on
            feedInChunks(s, 1, copy, half, copy.length);    // studio takes over at the copy's next frame
            s.ended(1);
            s.ended(2);
            s.expect(3);
            feedInChunks(s, 3, copy, 0, copy.length);       // and back to copy mode
        }, switched);
        assertThat(switched).containsExactly(2, 3);
        assertContinuous(probe(out), 2 + 4 + 4);
        Process decode = new ProcessBuilder("ffmpeg", "-v", "error", "-i", out.toString(), "-f", "null", "-").redirectErrorStream(true).start();
        String errors = new String(decode.getInputStream().readAllBytes());
        assertThat(decode.waitFor()).isZero();
        assertThat(errors.lines().count()).as(errors).isLessThan(5);

        // What a YouTube destination gets: the relay copies the stitched programme into FLV.
        Path flv = dir.resolve("mixed.flv");
        List<String> relay = new ArrayList<>(FFmpegCommandBuilder.buildRelayCommand("file://" + flv));
        relay.set(relay.indexOf("pipe:0"), out.toString());
        Process p = new ProcessBuilder(relay).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        assertContinuous(probe(flv), 10);
        Process decodeFlv = new ProcessBuilder("ffmpeg", "-v", "error", "-i", flv.toString(), "-f", "null", "-").redirectErrorStream(true).start();
        String flvErrors = new String(decodeFlv.getInputStream().readAllBytes());
        assertThat(decodeFlv.waitFor()).isZero();
        assertThat(flvErrors.lines().count()).as(flvErrors).isLessThan(5);
    }

    /** One producer's output cut into what FFmpeg writes per video frame (that frame plus the audio muxed with it). */
    record Chunk(double atSeconds, byte[] data) {
    }

    /** Splits a TS file at each video PES start; each chunk is due its video DTS after the first one. */
    static List<Chunk> framesOf(byte[] ts) {
        List<Chunk> out = new ArrayList<>();
        int videoPid = -1;
        long firstDts = Long.MIN_VALUE;
        double at = 0;
        int from = 0;
        for (int i = 0; i + TsStitcher.PACKET <= ts.length; i += TsStitcher.PACKET) {
            byte[] p = java.util.Arrays.copyOfRange(ts, i, i + TsStitcher.PACKET);
            int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
            if ((p[1] & 0x40) == 0 || !TsStitcher.isPes(p)) {
                continue;
            }
            int start = TsStitcher.payloadStart(p);
            int streamId = p[start + 3] & 0xff;
            if (streamId < 0xe0 || streamId > 0xef || (videoPid >= 0 && pid != videoPid)) {
                continue;
            }
            videoPid = pid;
            int flags = (p[start + 7] & 0xc0) >> 6;
            long dts = TsStitcher.readTs(p, start + (flags == 3 ? 14 : 9));
            if (firstDts == Long.MIN_VALUE) {
                firstDts = dts;
            }
            if (i > from) {
                out.add(new Chunk(at, java.util.Arrays.copyOfRange(ts, from, i)));
            }
            from = i;
            at = (dts - firstDts) / 90_000.0;
        }
        out.add(new Chunk(at, java.util.Arrays.copyOfRange(ts, from, ts.length)));
        return out;
    }

    @Test
    void mediaTimeKeepsPaceWithTheClockAcrossManySwitches() throws Exception {
        // OPS-6 soak finding: every studio<->copy hand-off pushed the programme ~150 ms ahead of real time, and
        // nothing pulled it back (+4.5 s after 56 switches). Here 24 hand-offs between real-time producers
        // (as StreamRunner.handOff does: start the next one, it takes over at its first keyframe) on a
        // simulated clock, with start-up delays that put each producer's frames at a different phase.
        byte[] studio = encode("pace-studio", "testsrc=size=640x360:rate=30", 880, "-t", "6", "-muxdelay", "0");
        byte[] copy = encode("pace-copy", "testsrc2=size=640x360:rate=30", 440, "-t", "6", "-muxdelay", "0");
        List<Chunk> studioFrames = framesOf(studio);
        List<Chunk> copyFrames = framesOf(copy);
        int switches = 24;
        double every = 2.5;
        java.util.Random random = new java.util.Random(7);

        record Arrival(double at, int producer, byte[] data) {
        }
        List<Arrival> arrivals = new ArrayList<>();
        double[] launchedAt = new double[switches + 2];
        for (int k = 1; k <= switches + 1; k++) {
            launchedAt[k] = (k - 1) * every;
            double startup = k == 1 ? 0.3 : 0.3 + random.nextDouble() * 0.9; // FFmpeg start, first encoded frame
            for (Chunk c : (k % 2 == 1 ? copyFrames : studioFrames)) {
                arrivals.add(new Arrival(launchedAt[k] + startup + c.atSeconds(), k, c.data()));
            }
        }
        arrivals.sort(java.util.Comparator.comparingDouble(Arrival::at));

        long[] clock = {0};
        List<double[]> leads = new ArrayList<>(); // {wall seconds, video DTS minus wall, ms}
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> switched = new ArrayList<>();
        TsStitcher s = new TsStitcher(data -> {
            out.writeBytes(data);
            for (int i = 0; i + TsStitcher.PACKET <= data.length; i += TsStitcher.PACKET) {
                byte[] p = java.util.Arrays.copyOfRange(data, i, i + TsStitcher.PACKET);
                if ((p[1] & 0x40) != 0 && TsStitcher.isPes(p)) {
                    int start = TsStitcher.payloadStart(p);
                    int streamId = p[start + 3] & 0xff;
                    int flags = (p[start + 7] & 0xc0) >> 6;
                    if (streamId >= 0xe0 && streamId <= 0xef && flags == 3) {
                        double wall = clock[0] / 1e9;
                        leads.add(new double[] {wall, TsStitcher.readTs(p, start + 14) / 90.0 - wall * 1000});
                    }
                }
            }
        }, switched::add, () -> clock[0]);
        int next = 1;
        for (Arrival a : arrivals) {
            while (next <= switches + 1 && launchedAt[next] <= a.at()) {
                s.expect(next++);
            }
            clock[0] = Math.round(a.at() * 1e9);
            s.feed(a.producer(), a.data(), a.data().length);
        }
        assertThat(switched).hasSize(switches);

        // Media time against the clock in each producer's turn, compared with the first producer's.
        double base = median(leads, 0.5, every);
        List<Double> drift = new ArrayList<>();
        for (int k = 1; k <= switches; k++) {
            drift.add(median(leads, k * every + 1.5, (k + 1) * every) - base);
        }
        // Before the fix: +142 ms after the first switch, +3.5 s after 24. After: within 0.1 ms of the first producer.
        assertThat(drift).as("ms ahead of the clock after each switch").allSatisfy(d -> assertThat(d).isBetween(-50.0, 50.0));
        double firstHalf = drift.subList(0, switches / 2).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double secondHalf = drift.subList(switches / 2, switches).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertThat(secondHalf - firstHalf).as("doesn't grow with the number of switches: %s", drift).isBetween(-20.0, 20.0);

        // And the joined programme is still one continuous, decodable timeline.
        Path file = dir.resolve("pace-out.ts");
        Files.write(file, out.toByteArray());
        List<Packet> packets = probe(file);
        double wallSpan = arrivals.getLast().at() - arrivals.getFirst().at();
        assertContinuousLoosely(packets);
        double videoSpan = packets.stream().filter(x -> x.type().equals("video")).mapToDouble(Packet::dts).max().orElseThrow()
                - packets.stream().filter(x -> x.type().equals("video")).mapToDouble(Packet::dts).min().orElseThrow();
        assertThat(videoSpan).as("media time against wall time").isBetween(wallSpan - 0.3, wallSpan + 0.3);
        Process decode = new ProcessBuilder("ffmpeg", "-v", "error", "-i", file.toString(), "-f", "null", "-").redirectErrorStream(true).start();
        String errors = new String(decode.getInputStream().readAllBytes());
        assertThat(decode.waitFor()).isZero();
        assertThat(errors.lines().count()).as(errors).isLessThan(5);
    }

    /** Video DTS (seconds) of every video PES in a TS buffer, in order. */
    static List<Double> videoDts(byte[] ts) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i + TsStitcher.PACKET <= ts.length; i += TsStitcher.PACKET) {
            byte[] p = java.util.Arrays.copyOfRange(ts, i, i + TsStitcher.PACKET);
            if ((p[1] & 0x40) != 0 && TsStitcher.isPes(p)) {
                int start = TsStitcher.payloadStart(p);
                int streamId = p[start + 3] & 0xff;
                int flags = (p[start + 7] & 0xc0) >> 6;
                if (streamId >= 0xe0 && streamId <= 0xef) {
                    out.add(TsStitcher.readTs(p, start + (flags == 3 ? 14 : 9)) / 90_000.0);
                }
            }
        }
        return out;
    }

    @Test
    void aHandOffThatNeverTakesOverLosesNothingAndAddsNoDelayOtherwise() throws Exception {
        byte[] clip = encode("cancel", "testsrc2=size=640x360:rate=30", 440, "-t", "6", "-muxdelay", "0");
        long[] clock = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TsStitcher s = new TsStitcher(out::writeBytes, x -> { }, () -> clock[0]);
        s.expect(1);
        int fed = 0;
        boolean expected = false;
        boolean cancelled = false;
        for (Chunk c : framesOf(clip)) {
            clock[0] = Math.round(c.atSeconds() * 1e9);
            if (!expected && c.atSeconds() >= 1.0) {
                s.expect(2); // a hand-off starts; the next producer never sends anything
                expected = true;
            }
            if (!cancelled && c.atSeconds() >= 2.0) {
                s.cancel(2); // it timed out: what was delayed goes out at once
                cancelled = true;
                assertThat(out.size()).as("nothing delayed is kept back").isEqualTo(fed);
            }
            s.feed(1, c.data(), c.data().length);
            fed += c.data().length;
            if (!expected || cancelled) {
                assertThat(out.size()).as("no delay without a hand-off").isEqualTo(fed);
            }
        }
        assertThat(out.toByteArray()).as("every packet, unchanged").isEqualTo(clip);
    }

    @Test
    void aRestartThatIsFarBehindCarriesOnAndLaterSwitchesKeepPaceFromThere() throws Exception {
        byte[] clip = encode("restart", "testsrc2=size=640x360:rate=30", 440, "-t", "6", "-muxdelay", "0");
        List<Chunk> frames = framesOf(clip);
        long[] clock = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TsStitcher s = new TsStitcher(out::writeBytes, x -> { }, () -> clock[0]);
        // Producer 1 plays 3 s and crashes; 2 sends its first frame 1.5 s later; 3 is a planned hand-off at 6 s.
        s.expect(1);
        for (Chunk c : frames) {
            if (c.atSeconds() >= 3.0) {
                break;
            }
            clock[0] = Math.round(c.atSeconds() * 1e9);
            s.feed(1, c.data(), c.data().length);
        }
        s.ended(1);
        s.expect(2);
        double startup2 = 4.5;
        double startup3 = 6.6;
        int i2 = 0;
        while (startup2 + frames.get(i2).atSeconds() < 6.0) {
            clock[0] = Math.round((startup2 + frames.get(i2).atSeconds()) * 1e9);
            s.feed(2, frames.get(i2).data(), frames.get(i2).data().length);
            i2++;
        }
        assertThat(s.switches()).isEqualTo(1);
        assertThat(s.lastSwitchErrorMillis()).as("no catching up 1.5 s: carry on from here").isZero();
        s.expect(3);
        // Producers 2 and 3 now run side by side in real time (2 from where it got to); 3 takes over at its keyframe.
        for (int k = 0; k < frames.size(); k++) {
            double at3 = startup3 + frames.get(k).atSeconds();
            while (i2 < frames.size() && startup2 + frames.get(i2).atSeconds() <= at3) {
                clock[0] = Math.round((startup2 + frames.get(i2).atSeconds()) * 1e9);
                s.feed(2, frames.get(i2).data(), frames.get(i2).data().length);
                i2++;
            }
            clock[0] = Math.round(at3 * 1e9);
            s.feed(3, frames.get(k).data(), frames.get(k).data().length);
        }
        assertThat(s.switches()).isEqualTo(2);
        assertThat(Math.abs(s.lastSwitchErrorMillis())).as("the planned switch keeps the new pace").isLessThan(5);
        List<Double> dts = videoDts(out.toByteArray());
        double maxStep = 0;
        for (int k = 1; k < dts.size(); k++) {
            assertThat(dts.get(k)).as("video DTS only goes forward").isGreaterThan(dts.get(k - 1));
            maxStep = Math.max(maxStep, dts.get(k) - dts.get(k - 1));
        }
        assertThat(maxStep).as("no jump in the timeline").isLessThan(0.25);
    }

    private static double median(List<double[]> leads, double from, double to) {
        double[] xs = leads.stream().filter(l -> l[0] >= from && l[0] < to).mapToDouble(l -> l[1]).sorted().toArray();
        assertThat(xs).as("frames between %s s and %s s", from, to).isNotEmpty();
        return xs[xs.length / 2];
    }

    /** As {@link #assertContinuous}, without a length check (the caller checks length against the clock). */
    static void assertContinuousLoosely(List<Packet> packets) {
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
            assertThat(maxGap).as(type + " has no hole in the timeline").isLessThan(type.equals("video") ? 0.25 : 0.5);
        }
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
