package com.afklive.streamer.streaming.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A/V pairing by sequence number, without FFmpeg. */
class DecoderSourceTest {

    private static final Canvas CANVAS = new Canvas(16, 16, 30, 44100);

    @TempDir
    Path dir;

    private DecoderSource live() {
        return new DecoderSource(CANVAS, new DecoderSource.Options(List.of("-i", "x"), true, true, true, 0), dir, "ffmpeg", "t");
    }

    private static byte[] marked(int size, int mark) {
        byte[] b = new byte[size];
        b[0] = (byte) mark;
        return b;
    }

    @Test
    void audioIsPairedWithItsOwnFrameEvenAfterDropsAndGaps() {
        DecoderSource s = live();
        // Video 0..4; audio for 1 is lost, audio 0 arrives, audio for 2..4 arrive.
        for (int i = 0; i < 5; i++) {
            s.inject(true, i, marked(CANVAS.frameBytes(), i));
        }
        for (int i : new int[]{0, 2, 3, 4}) {
            s.inject(false, i, marked(CANVAS.audioBytesPerFrame(), 100 + i));
        }
        Frame f = new Frame(CANVAS);
        int[] expected = {100, 0, 102, 103, 104};
        for (int i = 0; i < 5; i++) {
            assertThat(s.poll(f)).isTrue();
            assertThat(f.video[0]).isEqualTo((byte) i);
            assertThat(f.audio[0]).as("audio for frame " + i).isEqualTo((byte) expected[i]);
        }
    }

    @Test
    void skippingAheadInALiveSourceDropsTheMatchingAudioToo() {
        DecoderSource s = live();
        for (int i = 0; i < 10; i++) {
            s.inject(true, i, marked(CANVAS.frameBytes(), i));
            s.inject(false, i, marked(CANVAS.audioBytesPerFrame(), 100 + i));
        }
        Frame f = new Frame(CANVAS);
        assertThat(s.poll(f)).isTrue();
        // Too many frames were queued: the source skipped ahead, and the audio stayed with its frame.
        int frame = f.video[0];
        assertThat(frame).isGreaterThan(0);
        assertThat(f.audio[0]).isEqualTo((byte) (100 + frame));
        while (s.poll(f)) {
            assertThat(f.audio[0]).isEqualTo((byte) (100 + f.video[0]));
        }
    }

    @Test
    void audioAheadOfItsFrameIsKeptForThatFrame() {
        DecoderSource s = live();
        s.inject(true, 0, marked(CANVAS.frameBytes(), 0));
        s.inject(false, 1, marked(CANVAS.audioBytesPerFrame(), 101)); // frame 0's audio lost; 1's arrived early
        Frame f = new Frame(CANVAS);
        assertThat(s.poll(f)).isTrue();
        assertThat(f.audio[0]).isZero();
        s.inject(true, 1, marked(CANVAS.frameBytes(), 1));
        assertThat(s.poll(f)).isTrue();
        assertThat(f.audio[0]).isEqualTo((byte) 101);
    }

    @Test
    void framesAreDroppedToTheStreamRateBeforeScaling() {
        // A 60 fps source on a 30 fps stream: scaling half the frames is half the work.
        DecoderSource file = new DecoderSource(CANVAS, DecoderSource.Options.file(Path.of("x.mp4"), true, 0), dir, "ffmpeg", "t");
        String graph = file.command(dir.resolve("a.pcm")).get(file.command(dir.resolve("a.pcm")).indexOf("-filter_complex") + 1);
        assertThat(graph).startsWith("[0:v:0]fps=30:start_time=0[rated];[rated]scale=");
        assertThat(graph.indexOf("fps=")).isLessThan(graph.indexOf("scale="));
    }
}
