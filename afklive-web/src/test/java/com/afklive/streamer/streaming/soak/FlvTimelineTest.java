package com.afklive.streamer.streaming.soak;

import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.FFmpegCommandBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** OPS-6: the soak sink's FLV reader, on synthetic tags (exact numbers) and on the production relay's real output. */
class FlvTimelineTest {

    @TempDir
    Path dir;

    /** A tiny FLV writer: header, sequence headers, then tags with the timestamps a test wants. */
    static final class Flv {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Flv() {
            out.writeBytes(new byte[]{'F', 'L', 'V', 1, 5, 0, 0, 0, 9, 0, 0, 0, 0});
            tag(9, 0, new byte[]{0x17, 0, 0, 0, 0, 1, 2, 3}); // AVC sequence header
            tag(8, 0, new byte[]{(byte) 0xaf, 0, 0x12, 0x10}); // AAC sequence header
        }

        Flv video(long ts, boolean key) {
            return tag(9, ts, new byte[]{(byte) (key ? 0x17 : 0x27), 1, 0, 0, 0, 9, 9, 9, 9});
        }

        Flv audio(long ts) {
            return tag(8, ts, new byte[]{(byte) 0xaf, 1, 7, 7, 7});
        }

        Flv tag(int type, long ts, byte[] data) {
            int size = data.length;
            out.write(type);
            out.write(size >> 16);
            out.write(size >> 8);
            out.write(size);
            out.write((int) (ts >> 16));
            out.write((int) (ts >> 8));
            out.write((int) ts);
            out.write((int) (ts >> 24));
            out.writeBytes(new byte[]{0, 0, 0});
            out.writeBytes(data);
            int prev = 11 + size;
            out.writeBytes(new byte[]{(byte) (prev >> 24), (byte) (prev >> 16), (byte) (prev >> 8), (byte) prev});
            return this;
        }

        byte[] take() {
            byte[] b = out.toByteArray();
            out.reset();
            return b;
        }
    }

    /**
     * {@code seconds} of 30 fps video and 44.1 kHz AAC, fed in real time on a fake clock (100 ms chunks).
     * From {@code shiftAt} on, audio timestamps are moved by {@code audioShiftMs} (what a bad switch would do).
     */
    static FlvTimeline play(FlvTimeline t, int seconds, double shiftAt, long audioShiftMs, Path captureTo,
                            AtomicReference<FlvTimeline.Capture> captured) throws Exception {
        Flv flv = new Flv();
        byte[] head = flv.take(); // header + both sequence headers
        t.feed(head, head.length, 1);
        long clock0 = 1_000_000_000L;
        int frame = 0;
        int aac = 0;
        for (int chunk = 0; chunk < seconds * 10; chunk++) {
            double until = (chunk + 1) / 10.0;
            // Interleaved by timestamp, as FFmpeg's muxer writes them.
            while (true) {
                double vt = frame / 30.0;
                double at = aac * 1024 / 44100.0;
                if (vt >= until && at >= until) {
                    break;
                }
                if (vt <= at) {
                    flv.video(Math.round(vt * 1000), frame % 60 == 0);
                    frame++;
                } else {
                    long ms = Math.round(at * 1000) + (at >= shiftAt ? audioShiftMs : 0);
                    flv.audio(ms);
                    aac++;
                }
            }
            byte[] b = flv.take();
            t.feed(b, b.length, clock0 + (long) (until * 1e9));
            if (captureTo != null && chunk == seconds * 5) {
                assertThat(t.capture(captureTo, 3, "test", captured::set)).isTrue();
            }
        }
        return t;
    }

    @Test
    void aCleanStreamHasNoGapsNoDriftAndKeepsPaceWithTheClock() throws Exception {
        FlvTimeline.Stats s = play(new FlvTimeline(Duration.ofSeconds(10).toNanos()), 60, Double.MAX_VALUE, 0, null, null).stats();
        assertThat(s.videoTags()).isEqualTo(1800);
        assertThat(s.keyframes()).isEqualTo(30);
        assertThat(s.backwardsVideo() + s.backwardsAudio()).isZero();
        assertThat(s.maxVideoGapSeconds()).isLessThan(0.04);
        assertThat(s.maxAudioGapSeconds()).isLessThan(0.03);
        assertThat(s.videoGapsOverLimit() + s.audioGapsOverLimit()).isZero();
        assertThat(s.avDriftMs()).isLessThanOrEqualTo(25);
        assertThat(s.lagDriftMs()).isLessThanOrEqualTo(150);
        assertThat(s.stalls()).isZero();
        assertThat(s.midStreamHeaders()).isZero();
        assertThat(s.windows()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void audioMovedAgainstVideoShowsAsDriftAndAHole() throws Exception {
        FlvTimeline.Stats s = play(new FlvTimeline(Duration.ofSeconds(10).toNanos()), 60, 35, 300, null, null).stats();
        assertThat(s.avDriftMs()).isBetween(260L, 340L);
        assertThat(s.maxAudioGapSeconds()).isGreaterThan(0.3);
        assertThat(s.backwardsVideo() + s.backwardsAudio()).isZero();
    }

    @Test
    void timestampsGoingBackwardsAndResentHeadersAreCounted() {
        FlvTimeline t = new FlvTimeline(0);
        Flv flv = new Flv().video(0, true).audio(0).video(33, false).audio(23).video(20, false)
                .tag(9, 40, new byte[]{0x17, 0, 0, 0, 0, 1, 2, 3});
        byte[] b = flv.take();
        // Split mid-tag, like a socket read.
        t.feed(b, 30, 1);
        byte[] rest = java.util.Arrays.copyOfRange(b, 30, b.length);
        t.feed(rest, rest.length, 2);
        FlvTimeline.Stats s = t.stats();
        assertThat(s.videoTags()).isEqualTo(3);
        assertThat(s.audioTags()).isEqualTo(2);
        assertThat(s.backwardsVideo()).isEqualTo(1);
        assertThat(s.midStreamHeaders()).isEqualTo(1);
    }

    @Test
    void aCaptureStartsOnAKeyframeAtLeastFourSecondsBackWithTheHeaders() throws Exception {
        Path file = dir.resolve("cap.flv");
        AtomicReference<FlvTimeline.Capture> done = new AtomicReference<>();
        play(new FlvTimeline(0), 20, Double.MAX_VALUE, 0, file, done);
        assertThat(done.get()).isNotNull();
        byte[] b = Files.readAllBytes(file);
        assertThat(new String(b, 0, 3)).isEqualTo("FLV");
        // Read it back: sequence headers first, then media from a keyframe, spanning pre-roll + 3 s.
        FlvTimeline back = new FlvTimeline(0);
        back.feed(b, b.length, 1);
        FlvTimeline.Stats s = back.stats();
        assertThat(s.midStreamHeaders()).isZero();
        assertThat(s.mediaSeconds()).isBetween(7.0, 9.1);
        int firstTag = 13 + (11 + 8 + 4) + (11 + 4 + 4);
        assertThat(b[firstTag]).isEqualTo((byte) 9);
        assertThat(b[firstTag + 11]).isEqualTo((byte) 0x17);
    }

    @Test
    void theProductionRelayIntoASinkIsReadTagByTag() throws Exception {
        Process v = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0, "ffmpeg");
        Path ts = dir.resolve("in.ts");
        List<String> enc = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "6"));
        enc.addAll(FFmpegCommandBuilder.liveEncodeArgs(360, 30, EncoderSettings.DEFAULT));
        enc.addAll(List.of("-f", "mpegts", ts.toString()));
        Process e = new ProcessBuilder(enc).redirectErrorStream(true).start();
        String elog = new String(e.getInputStream().readAllBytes());
        assertThat(e.waitFor()).as(elog).isZero();

        try (SoakSink sink = new SoakSink("t", 0)) {
            List<String> relay = new ArrayList<>(FFmpegCommandBuilder.buildRelayCommand(sink.url()));
            relay.set(relay.indexOf("pipe:0"), ts.toString());
            Process r = new ProcessBuilder(relay).redirectErrorStream(true).start();
            String rlog = new String(r.getInputStream().readAllBytes());
            assertThat(r.waitFor()).as(rlog).isZero();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < until && (sink.connections().isEmpty() || sink.connections().getFirst().stats().videoTags() < 170)) {
                Thread.sleep(50);
            }
            assertThat(sink.connections()).hasSize(1);
            FlvTimeline.Stats s = sink.connections().getFirst().stats();
            assertThat(s.videoTags()).isBetween(175L, 181L);
            assertThat(s.audioTags()).isBetween(250L, 262L);
            assertThat(s.keyframes()).isEqualTo(3); // every 2 s; the end-of-sequence tag is not a frame
            assertThat(s.backwardsVideo() + s.backwardsAudio()).isZero();
            assertThat(s.maxVideoGapSeconds()).isLessThan(FlvTimeline.VIDEO_GAP_LIMIT_SECONDS);
            assertThat(s.maxAudioGapSeconds()).isLessThan(FlvTimeline.AUDIO_GAP_LIMIT_SECONDS);
            assertThat(s.mediaSeconds()).isBetween(5.8, 6.0);
        }
    }
}
