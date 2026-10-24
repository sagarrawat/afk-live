package com.afklive.streamer.streaming.soak;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * OPS-6: what a platform receives on one connection, read tag by tag from the FLV a relay sends. Every
 * audio and video tag is checked, for the whole run (no sampling):
 * <ul>
 *   <li>timestamps only go forward, per stream (FLV carries the video DTS);</li>
 *   <li>no hole in the timeline: the largest step between tags, against the same limits as
 *       {@code TsStitcherTest.assertContinuous} (video 0.25 s, audio 0.5 s);</li>
 *   <li>A/V drift: how far the audio timeline has moved against the video timeline since the start
 *       ({@code (audio span) - (video span)}, the median of each 10 s window against the first window after
 *       warm-up). A switch that shifted audio and video by different amounts shows up here;</li>
 *   <li>media vs wall clock: how far the video timeline has moved against real time since the start
 *       (a producer that runs slow or fast, or switches that lose or add time);</li>
 *   <li>stalls: wall-clock pauses in the bytes arriving;</li>
 *   <li>sequence headers sent again mid-stream (a platform may treat that as a new stream).</li>
 * </ul>
 * It also keeps the last few seconds of tags so a short, playable sample file can be cut around any moment
 * (a switch, or a periodic check) and probed with FFmpeg.
 */
public final class FlvTimeline {

    public static final double VIDEO_GAP_LIMIT_SECONDS = 0.25;
    public static final double AUDIO_GAP_LIMIT_SECONDS = 0.5;
    static final long WINDOW_NANOS = 10_000_000_000L;
    static final long STALL_NANOS = 2_000_000_000L;
    static final long PRE_ROLL_MS = 4_000;

    private final long warmupNanos;
    private byte[] buf = new byte[256 * 1024];
    private int len;
    private boolean headerDone;
    private byte[] fileHeader;
    private byte[] scriptTag;
    private byte[] videoConfig;
    private byte[] audioConfig;

    private long bytes;
    private long videoTags;
    private long audioTags;
    private long keyframes;
    private long midStreamHeaders;
    private long backwardsVideo;
    private long backwardsAudio;
    private long firstV = -1;
    private long lastV = -1;
    private long firstA = -1;
    private long lastA = -1;
    private long maxVideoGapMs;
    private long maxAudioGapMs;
    private long videoGapsOverLimit;
    private long audioGapsOverLimit;
    private long wall0;
    private long lastFeedNanos;
    private long stalls;
    private long maxStallNanos;

    private long windowStart;
    private final List<Long> windowAv = new ArrayList<>();
    private final List<Long> windowLag = new ArrayList<>();
    private final List<Window> windows = new ArrayList<>();

    private record Tag(byte[] raw, long ts, boolean videoKey) {
    }

    private final Deque<Tag> ring = new ArrayDeque<>();
    private Capture capture;

    /** One 10 s window: when it ended (s since the first tag), the median A/V offset and media-minus-wall lag (ms). */
    public record Window(double atSeconds, long avMs, long lagMs) {
    }

    /** A sample file being written: from a keyframe a few seconds back until {@code seconds} after it was asked for. */
    public static final class Capture {
        public final Path file;
        public final String reason;
        final OutputStream out;
        final long untilTs;
        final Consumer<Capture> done;

        Capture(Path file, String reason, OutputStream out, long untilTs, Consumer<Capture> done) {
            this.file = file;
            this.reason = reason;
            this.out = out;
            this.untilTs = untilTs;
            this.done = done;
        }
    }

    public FlvTimeline(long warmupNanos) {
        this.warmupNanos = warmupNanos;
    }

    /** Bytes as they arrive from the relay; {@code nowNanos} is when (System.nanoTime, or a test clock). */
    public synchronized void feed(byte[] data, int n, long nowNanos) {
        if (n <= 0) {
            return;
        }
        if (lastFeedNanos != 0 && nowNanos - lastFeedNanos > STALL_NANOS) {
            stalls++;
        }
        if (lastFeedNanos != 0) {
            maxStallNanos = Math.max(maxStallNanos, nowNanos - lastFeedNanos);
        }
        lastFeedNanos = nowNanos;
        bytes += n;
        if (len + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
        System.arraycopy(data, 0, buf, len, n);
        len += n;
        int pos = 0;
        if (!headerDone) {
            if (len < 13) {
                return;
            }
            if (buf[0] != 'F' || buf[1] != 'L' || buf[2] != 'V') {
                throw new IllegalStateException("Not FLV");
            }
            fileHeader = Arrays.copyOfRange(buf, 0, 13);
            headerDone = true;
            pos = 13;
        }
        while (len - pos >= 11) {
            int size = ((buf[pos + 1] & 0xff) << 16) | ((buf[pos + 2] & 0xff) << 8) | (buf[pos + 3] & 0xff);
            int total = 11 + size + 4;
            if (len - pos < total) {
                break;
            }
            int type = buf[pos] & 0x1f;
            long ts = (((buf[pos + 7] & 0xffL) << 24) | ((buf[pos + 4] & 0xffL) << 16) | ((buf[pos + 5] & 0xffL) << 8)
                    | (buf[pos + 6] & 0xffL));
            byte[] raw = Arrays.copyOfRange(buf, pos, pos + total);
            tag(type, ts, raw, size, nowNanos);
            pos += total;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }

    private void tag(int type, long ts, byte[] raw, int size, long now) {
        if (type == 18) {
            if (scriptTag == null) {
                scriptTag = raw;
            }
            return;
        }
        if (size < 2 || (type != 8 && type != 9)) {
            return;
        }
        boolean video = type == 9;
        if (video && raw[12] == 2) {
            return; // AVC end of sequence: the relay writes it when it closes, at the last frame's timestamp
        }
        boolean config = raw[12] == 0; // AVCPacketType / AACPacketType 0 = sequence header
        if (config) {
            if (video ? videoConfig != null : audioConfig != null) {
                midStreamHeaders++;
            }
            if (video) {
                videoConfig = raw;
            } else {
                audioConfig = raw;
            }
            return;
        }
        if (wall0 == 0) {
            wall0 = now;
            windowStart = now;
        }
        boolean key = video && ((raw[11] & 0xf0) >> 4) == 1;
        if (video) {
            videoTags++;
            if (key) {
                keyframes++;
            }
            if (firstV < 0) {
                firstV = ts;
            } else {
                if (ts <= lastV) {
                    backwardsVideo++;
                }
                long gap = ts - lastV;
                maxVideoGapMs = Math.max(maxVideoGapMs, gap);
                if (gap >= VIDEO_GAP_LIMIT_SECONDS * 1000) {
                    videoGapsOverLimit++;
                }
            }
            lastV = ts;
            if (firstA >= 0) {
                windowAv.add((lastA - firstA) - (lastV - firstV));
                windowLag.add((lastV - firstV) - (now - wall0) / 1_000_000);
            }
        } else {
            audioTags++;
            if (firstA < 0) {
                firstA = ts;
            } else {
                if (ts <= lastA) {
                    backwardsAudio++;
                }
                long gap = ts - lastA;
                maxAudioGapMs = Math.max(maxAudioGapMs, gap);
                if (gap >= AUDIO_GAP_LIMIT_SECONDS * 1000) {
                    audioGapsOverLimit++;
                }
            }
            lastA = ts;
        }
        if (now - windowStart >= WINDOW_NANOS && !windowAv.isEmpty()) {
            windows.add(new Window((now - wall0) / 1e9, median(windowAv), median(windowLag)));
            windowAv.clear();
            windowLag.clear();
            windowStart = now;
        }
        remember(new Tag(raw, ts, key));
    }

    private void remember(Tag t) {
        ring.addLast(t);
        if (t.videoKey()) {
            // Keep from the newest keyframe at least PRE_ROLL_MS old: a capture then starts on a keyframe.
            Tag startAt = null;
            for (Tag x : ring) {
                if (x.videoKey() && x.ts() <= t.ts() - PRE_ROLL_MS) {
                    startAt = x;
                }
            }
            if (startAt != null) {
                while (ring.peekFirst() != startAt) {
                    ring.removeFirst();
                }
            }
        } else if (ring.size() > 20_000) {
            ring.removeFirst(); // no keyframes at all: don't grow without bound
        }
        Capture c = capture;
        if (c != null) {
            try {
                c.out.write(t.raw());
                if (t.ts() >= c.untilTs) {
                    finishCapture();
                }
            } catch (IOException e) {
                capture = null;
            }
        }
    }

    /**
     * Starts writing a playable sample: the FLV header and sequence headers, the kept pre-roll (from a
     * keyframe at least 4 s back), then what arrives for {@code seconds}. False when one is already being
     * written or nothing has arrived yet.
     */
    public synchronized boolean capture(Path file, double seconds, String reason, Consumer<Capture> done) throws IOException {
        if (capture != null || fileHeader == null || videoConfig == null || ring.isEmpty()) {
            return false;
        }
        Files.createDirectories(file.getParent());
        OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file), 1 << 20);
        out.write(fileHeader);
        if (scriptTag != null) {
            out.write(scriptTag);
        }
        out.write(videoConfig);
        if (audioConfig != null) {
            out.write(audioConfig);
        }
        for (Tag t : ring) {
            out.write(t.raw());
        }
        capture = new Capture(file, reason, out, ring.peekLast().ts() + Math.round(seconds * 1000), done);
        return true;
    }

    /** Ends a capture early (the stream is stopping). */
    public synchronized void finishCapture() {
        Capture c = capture;
        capture = null;
        if (c == null) {
            return;
        }
        try {
            c.out.close();
        } catch (IOException ignored) {
            // the sample is just shorter
        }
        if (c.done != null) {
            c.done.accept(c);
        }
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** Everything measured so far. */
    public synchronized Stats stats() {
        List<Window> judged = windows.stream().filter(w -> w.atSeconds() * 1e9 >= warmupNanos).toList();
        if (judged.isEmpty() && !windows.isEmpty()) {
            judged = List.of(windows.getLast());
        }
        long avDrift = 0;
        long lagDrift = 0;
        long lagEnd = 0;
        if (!judged.isEmpty()) {
            Window base = judged.getFirst();
            for (Window w : judged) {
                avDrift = Math.max(avDrift, Math.abs(w.avMs() - base.avMs()));
                lagDrift = Math.max(lagDrift, Math.abs(w.lagMs() - base.lagMs()));
            }
            lagEnd = judged.getLast().lagMs() - base.lagMs();
        }
        double mediaSeconds = firstV >= 0 ? (lastV - firstV) / 1000.0 : 0;
        double wallSeconds = wall0 == 0 ? 0 : (lastFeedNanos - wall0) / 1e9;
        return new Stats(bytes, videoTags, audioTags, keyframes, midStreamHeaders, backwardsVideo, backwardsAudio,
                maxVideoGapMs / 1000.0, maxAudioGapMs / 1000.0, videoGapsOverLimit, audioGapsOverLimit, avDrift, lagDrift,
                lagEnd, stalls, maxStallNanos / 1e9, mediaSeconds, wallSeconds, List.copyOf(windows));
    }

    /**
     * @param avDriftMs   largest change in the audio-vs-video timeline offset after warm-up
     * @param lagDriftMs  largest change in media-minus-wall-clock after warm-up
     * @param lagEndMs    that change at the end of the run (signed: negative means the media fell behind real time)
     */
    public record Stats(long bytes, long videoTags, long audioTags, long keyframes, long midStreamHeaders,
                        long backwardsVideo, long backwardsAudio, double maxVideoGapSeconds, double maxAudioGapSeconds,
                        long videoGapsOverLimit, long audioGapsOverLimit, long avDriftMs, long lagDriftMs, long lagEndMs,
                        long stalls, double maxStallSeconds, double mediaSeconds, double wallSeconds,
                        List<Window> windows) {
    }
}
