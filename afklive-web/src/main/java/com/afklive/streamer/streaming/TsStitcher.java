package com.afklive.streamer.streaming;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Joins the MPEG-TS output of successive producer processes into one continuous programme, so the
 * relays (and the platforms behind them) never see a new timeline and never reconnect when the
 * process making the stream changes: a planned switch between copy mode and the studio (FO-2), or an
 * encoder restarted after a crash.
 * <p>
 * Producers are numbered by the caller. The first one plays as-is. A later one is <em>expected</em>
 * ({@link #expect}): its packets are held from its first video keyframe on, while the current producer
 * keeps playing. From then on the new producer's timestamps (PTS, DTS, PCR) are shifted to continue
 * the programme, and continuity counters keep counting per PID. Timestamps are unwrapped across the
 * 33-bit rollover (every 26.5 hours), so the offset math holds for streams that run for weeks.
 * <p>
 * <b>Keeping pace with the clock (OPS-6).</b> Producers are paced in real time, so media time runs in
 * step with the wall clock, and a switch must not move it: a programme that gains even 0.1 s per
 * switch is minutes ahead after a few weeks. Three rules keep it in step:
 * <ol>
 *   <li><em>The switch point is in the past, not the future.</em> While a producer is expected, the
 *   current one's output is delayed by {@link #DELAY} (only then; no latency otherwise). When the new
 *   producer's first keyframe arrives, the old one is cut at the latest <em>clean</em> frame boundary
 *   that had already arrived by then: before an I or P frame, so every old frame up to the latest one
 *   shown was sent and nothing is shown out of order. What the old producer sent after that point is
 *   dropped. So the new producer always starts a little <em>behind</em> its slot, never ahead
 *   (waiting for the old producer's next boundary would put the new one ahead by that wait).</li>
 *   <li><em>An absolute reference.</em> The stitcher remembers how far ahead of the clock the first
 *   producer's video timestamps arrived (its reference lead) and places each new producer so that
 *   its frames arrive with that same lead, measured when its first keyframe arrived. The new
 *   timeline starts strictly after the old one (video DTS and PTS) and at most {@link #MAX_CATCH_UP}
 *   after plain continuation: being behind is fixed by a short step forward in the timeline. Every
 *   switch aims at the same reference, so one switch's error (arrival jitter, a few ms) is corrected
 *   by the next instead of adding up.</li>
 *   <li><em>Video anchors the seam, not the largest timestamp.</em> Continuation is measured on video
 *   DTS (decode order) and video PTS (presentation order). Audio PES from the new producer that would
 *   start before the old producer's audio has finished are dropped, so audio only goes forward too
 *   (the gap left is at most one audio frame).</li>
 * </ol>
 * If a new producer is far behind (the old one crashed and the restart took a while), the timeline
 * simply continues and the reference moves to where the stream now is.
 * <p>
 * Assumes what our producers write (FFmpeg's mpegts muxer): one programme, video and audio as PES
 * with PTS (video also DTS), keyframes flagged with the random access indicator, each PES written as
 * consecutive packets.
 */
public final class TsStitcher {

    public static final int PACKET = 188;
    static final long WRAP = 1L << 33;
    /** About one frame at 30 fps, in 90 kHz units: the gap used before the frame rate is known. */
    static final long GAP = 3000;
    /** Packets held from an expected producer before it's allowed to cut over: about 10 s at 6 Mbit/s. */
    static final int MAX_HELD = 40_000;
    /**
     * How long the current producer's output is delayed while another one is expected (90 kHz): 0.25 s,
     * enough to cut it at a clean boundary before the new producer's first keyframe arrived.
     */
    static final long DELAY = 22_500;
    /** At most this much (90 kHz) is added to the timeline at a switch to catch up with the clock: 0.15 s. */
    static final long MAX_CATCH_UP = 13_500;
    /** Further behind than this (90 kHz, 1 s) at a switch, the reference moves instead (a crash, a slow restart). */
    static final long REBASE = 90_000;
    /** The smallest step allowed between the old producer's last and the new one's first video timestamp: 2 ms. */
    static final long MIN_STEP = 180;
    /** Old producer frames to wait for a clean boundary when none had arrived yet, before switching anyway. */
    static final int MAX_WAIT_FRAMES = 8;
    /** Video arrivals of the first producer kept for its reference lead (about 8 s at 30 fps). */
    private static final int LEAD_SAMPLES = 256;

    public interface Sink {
        void write(byte[] data);
    }

    /** Called (under the stitcher's lock) when an expected producer takes over. */
    public interface Cutover {
        void switched(int producer);
    }

    private final Sink sink;
    private final Cutover cutover;
    private final LongSupplier nanoClock;
    private final Map<Integer, Source> sources = new HashMap<>();
    private final Map<Integer, Integer> nextCc = new HashMap<>();
    private int current = -1;
    private int expected = -1;
    /** Largest timestamp sent so far, unwrapped, in 90 kHz units. */
    private long maxOut = Long.MIN_VALUE;
    /** Last video DTS and largest video PTS sent (unwrapped, 90 kHz), and the video frame duration. */
    private long lastVideoDts = Long.MIN_VALUE;
    private long maxVideoPts = Long.MIN_VALUE;
    private long frameDuration = GAP;
    /** Per audio PID: the last PTS sent (unwrapped, 90 kHz), one audio PES's duration, and which producer sent it. */
    private final Map<Integer, long[]> audioOut = new HashMap<>();
    /** The current producer's packets delayed while another producer is expected. */
    private final ArrayDeque<Delayed> delayed = new ArrayDeque<>();
    /** Largest video PTS sent or delayed (unwrapped, 90 kHz): which delayed frames start at a clean boundary. */
    private long delayedMaxPts = Long.MIN_VALUE;
    /** Media time minus wall clock (both 90 kHz) the programme keeps; MIN_VALUE until known. */
    private long referenceLead = Long.MIN_VALUE;
    private final long[] leadSamples = new long[LEAD_SAMPLES];
    private int leadCount;
    private long switches;
    /** How far the last switch put the programme ahead of its reference (90 kHz), for logs and tests. */
    private long lastSwitchError;

    public TsStitcher(Sink sink, Cutover cutover) {
        this(sink, cutover, System::nanoTime);
    }

    /** With an explicit clock (System.nanoTime by default): the time each chunk of producer output arrives. */
    public TsStitcher(Sink sink, Cutover cutover, LongSupplier nanoClock) {
        this.sink = sink;
        this.cutover = cutover;
        this.nanoClock = nanoClock;
    }

    /** A packet of the current producer, delayed; {@code clean}: a video PES that starts at a clean boundary. */
    private record Delayed(Source source, byte[] packet, long arrival, boolean videoStart, boolean clean) {
    }

    private static final class Source {
        final int id;
        final byte[] partial = new byte[PACKET];
        int partialLength;
        long offset;
        boolean started;
        final List<byte[]> held = new ArrayList<>();
        byte[] lastPat;
        byte[] lastPmt;
        final Map<Integer, Integer> pmtPids = new HashMap<>();
        final Map<Integer, Boolean> videoPid = new HashMap<>();
        final Map<Integer, Long> lastRaw = new HashMap<>();
        final Map<Integer, Long> epoch = new HashMap<>();
        /** Its own last video DTS and per-PID audio PTS (unwrapped, its own timeline): frame durations. */
        long ownVideoDts = Long.MIN_VALUE;
        final Map<Integer, Long> ownAudioPts = new HashMap<>();
        /** Audio PIDs whose current PES is being dropped (it overlapped the previous producer's audio). */
        final Set<Integer> dropping = new HashSet<>();
        /** First keyframe's DTS minus its arrival time (own timeline, 90 kHz): how far ahead of the clock it arrives. */
        long arrivalLead = Long.MIN_VALUE;
        /** Frames of the current producer seen while waiting for a clean boundary. */
        int waitedFrames;
        boolean ended;

        Source(int id) {
            this.id = id;
        }

        /**
         * Unwraps a 33-bit timestamp of one kind (pts/dts/pcr per PID; a separate key for look-ahead)
         * into a continuous count.
         */
        long unwrap(int key, long raw) {
            Long last = lastRaw.get(key);
            long e = epoch.getOrDefault(key, 0L);
            if (last != null) {
                if (raw < last && last - raw > WRAP / 2) {
                    e += WRAP;
                } else if (raw > last && raw - last > WRAP / 2) {
                    e -= WRAP; // a straggler from before the wrap
                }
            }
            lastRaw.put(key, raw);
            epoch.put(key, e);
            return raw + e;
        }
    }

    /** Announces producer {@code id}: it takes over at its first keyframe. */
    public synchronized void expect(int id) {
        if (current < 0) {
            current = id; // the very first producer plays as it is
        } else {
            expected = id;
            Source c = sources.get(current);
            if (c != null) {
                c.waitedFrames = 0;
            }
            delayedMaxPts = maxVideoPts;
        }
        sources.computeIfAbsent(id, Source::new);
    }

    /** Producer {@code id} stopped sending: switch to the expected one as soon as it has a keyframe. */
    public synchronized void ended(int id) {
        Source s = sources.get(id);
        if (s != null) {
            s.ended = true;
        }
        if (id == current) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long now = wall90();
            releaseAll(out);
            tryCutoverNow(out, now);
            write(out);
        }
    }

    /** Forgets an expected producer that never took over (it failed, or a hand-off timed out). */
    public synchronized void cancel(int id) {
        if (expected == id) {
            expected = -1;
            sources.remove(id);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            releaseAll(out);
            write(out);
        }
    }

    public synchronized int current() {
        return current;
    }

    public synchronized long switches() {
        return switches;
    }

    /** Milliseconds the last switch left the programme ahead of its reference against the clock (negative: behind). */
    public synchronized double lastSwitchErrorMillis() {
        return lastSwitchError / 90.0;
    }

    /** Bytes from producer {@code id}; anything from a producer that is neither current nor expected is dropped. */
    public synchronized void feed(int id, byte[] data, int length) {
        Source s = sources.get(id);
        if (s == null || (id != current && id != expected)) {
            return;
        }
        long now = wall90();
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + PACKET);
        release(out, now);
        int pos = 0;
        while (pos < length) {
            if (s.partialLength > 0 || length - pos < PACKET) {
                int take = Math.min(PACKET - s.partialLength, length - pos);
                System.arraycopy(data, pos, s.partial, s.partialLength, take);
                s.partialLength += take;
                pos += take;
                if (s.partialLength < PACKET) {
                    break;
                }
                packet(s, s.partial.clone(), out, now);
                s.partialLength = 0;
            } else {
                byte[] p = new byte[PACKET];
                System.arraycopy(data, pos, p, 0, PACKET);
                pos += PACKET;
                packet(s, p, out, now);
            }
            if (id != current && id != expected) {
                break; // replaced while this chunk was being read
            }
        }
        write(out);
    }

    private void write(ByteArrayOutputStream out) {
        if (out.size() > 0) {
            sink.write(out.toByteArray());
        }
    }

    /** The clock in 90 kHz units. */
    private long wall90() {
        return Math.floorDiv(nanoClock.getAsLong(), 1000) * 9 / 100;
    }

    private void packet(Source s, byte[] p, ByteArrayOutputStream out, long now) {
        if ((p[0] & 0xff) != 0x47) {
            return; // not aligned: FFmpeg always writes whole packets, so this never happens with our producers
        }
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        boolean pusi = (p[1] & 0x40) != 0;
        learn(s, p, pid, pusi);
        if (s.id == expected) {
            boolean wasStarted = s.started;
            hold(s, p, pid, pusi, now);
            if (s.started) {
                Source c = sources.get(current);
                if (c == null || c.ended) {
                    releaseAll(out);
                    tryCutoverNow(out, now);
                } else if (!wasStarted) {
                    // Its first keyframe: cut the old producer at the latest clean boundary already here.
                    int cut = latestCleanBoundary();
                    if (cut >= 0) {
                        cutover(sources.get(expected), cut, out, now);
                    }
                }
            }
            return;
        }
        if (expected < 0) {
            emit(s, p, out, now);
            return;
        }
        // Current producer while another is expected: delay; or, if the next one is ready and no clean
        // boundary had arrived when it was, switch at the next clean one (or after a few frames anyway).
        boolean videoStart = pusi && Boolean.TRUE.equals(s.videoPid.get(pid)) && isPes(p);
        boolean clean = videoStart && clean(s, p, pid);
        Source next = sources.get(expected);
        if (videoStart && next != null && next.started && (clean || ++s.waitedFrames > MAX_WAIT_FRAMES)) {
            cutover(next, delayed.size(), out, now);
            return;
        }
        delayed.add(new Delayed(s, p, now, videoStart, clean));
    }

    /** Whether a video PES of the current producer starts at a clean boundary: its PTS is past every one before it. */
    private boolean clean(Source s, byte[] p, int pid) {
        int start = payloadStart(p);
        int flags = (p[start + 7] & 0xc0) >> 6;
        if ((flags & 2) == 0) {
            return true;
        }
        long pts = s.unwrap(pid * 4 + 3, readTs(p, start + 9)) + s.offset;
        boolean clean = delayedMaxPts == Long.MIN_VALUE || pts > delayedMaxPts;
        delayedMaxPts = Math.max(delayedMaxPts, pts);
        return clean;
    }

    /** Index into {@link #delayed} of the latest video PES that starts at a clean boundary, or -1. */
    private int latestCleanBoundary() {
        int i = 0;
        int found = -1;
        for (Delayed d : delayed) {
            if (d.videoStart() && d.clean()) {
                found = i;
            }
            i++;
        }
        return found;
    }

    /** Sends delayed packets that have waited {@link #DELAY}. */
    private void release(ByteArrayOutputStream out, long now) {
        while (!delayed.isEmpty() && delayed.peekFirst().arrival() + DELAY <= now) {
            Delayed d = delayed.pollFirst();
            emit(d.source(), d.packet(), out, d.arrival());
        }
    }

    private void releaseAll(ByteArrayOutputStream out) {
        while (!delayed.isEmpty()) {
            Delayed d = delayed.pollFirst();
            emit(d.source(), d.packet(), out, d.arrival());
        }
    }

    private void hold(Source s, byte[] p, int pid, boolean pusi, long now) {
        if (!s.started) {
            if (pusi && Boolean.TRUE.equals(s.videoPid.get(pid)) && isPes(p) && randomAccess(p)) {
                s.started = true;
                if (s.lastPat != null) {
                    s.held.add(s.lastPat);
                }
                if (s.lastPmt != null) {
                    s.held.add(s.lastPmt);
                }
            } else {
                return; // nothing before its first keyframe is useful
            }
        }
        if (s.arrivalLead == Long.MIN_VALUE && pusi && Boolean.TRUE.equals(s.videoPid.get(pid)) && isPes(p)) {
            // How far ahead of the clock this producer's frames arrive (its own timeline), from its first
            // keyframe: measured, it arrives in step with the frames after it (no start-up burst or catch-up),
            // while frames held behind it could include FFmpeg's -re initial burst.
            int start = payloadStart(p);
            int flags = (p[start + 7] & 0xc0) >> 6;
            if ((flags & 2) != 0) {
                s.arrivalLead = readTs(p, start + (flags == 3 ? 14 : 9)) - now;
            }
        }
        if (s.held.size() < MAX_HELD) {
            s.held.add(p);
        }
    }

    /** The current producer has ended (its delayed output already sent): switch if the next one is ready. */
    private void tryCutoverNow(ByteArrayOutputStream out, long now) {
        if (expected < 0) {
            return;
        }
        Source next = sources.get(expected);
        if (next != null && next.started) {
            cutover(next, delayed.size(), out, now);
        }
    }

    /** Sends the first {@code keep} delayed packets of the old producer, drops the rest, and switches to {@code next}. */
    private void cutover(Source next, int keep, ByteArrayOutputStream out, long now) {
        for (int i = 0; i < keep; i++) {
            Delayed d = delayed.pollFirst();
            emit(d.source(), d.packet(), out, d.arrival());
        }
        delayed.clear();
        long first = Long.MAX_VALUE;
        long keyDts = Long.MIN_VALUE;
        long keyPts = Long.MIN_VALUE;
        for (byte[] p : next.held) {
            long[] t = timestamps(next, p);
            if (t != null) {
                first = Math.min(first, Math.min(t[0], t[1]));
                if (keyDts == Long.MIN_VALUE && t[2] == 1) {
                    keyDts = t[1];
                    keyPts = t[0];
                }
            }
        }
        // Reset the unwrap state: the walk above went through the held packets once already.
        next.lastRaw.clear();
        next.epoch.clear();
        captureReferenceLead();
        if (first == Long.MAX_VALUE || maxOut == Long.MIN_VALUE) {
            next.offset = 0;
        } else if (keyDts == Long.MIN_VALUE || lastVideoDts == Long.MIN_VALUE) {
            next.offset = maxOut + frameDuration - first;
        } else {
            next.offset = placement(next, keyDts, keyPts);
        }
        int previous = current;
        current = next.id;
        expected = -1;
        switches++;
        for (byte[] p : next.held) {
            emit(next, p, out, now);
        }
        next.held.clear();
        sources.remove(previous);
        cutover.switched(next.id);
    }

    /**
     * The offset for a new producer whose keyframe has these (unwrapped, own) timestamps: as close to
     * the reference lead as the joined timeline allows.
     */
    private long placement(Source next, long keyDts, long keyPts) {
        // Plain continuation: the next frame slot after the old producer's last frame, in decode order
        // and in presentation order.
        long natural = Math.max(lastVideoDts + frameDuration - keyDts, maxVideoPts + frameDuration - keyPts);
        // The earliest valid placement: still strictly after both.
        long lowest = Math.max(lastVideoDts + MIN_STEP - keyDts, maxVideoPts + MIN_STEP - keyPts);
        if (next.arrivalLead == Long.MIN_VALUE) {
            lastSwitchError = 0;
            return natural;
        }
        if (referenceLead == Long.MIN_VALUE) {
            referenceLead = next.arrivalLead + natural;
            lastSwitchError = 0;
            return natural;
        }
        long target = referenceLead - next.arrivalLead;
        long offset;
        if (target > natural + REBASE) {
            // Far behind the clock (the old producer stopped and the restart took a while): carry on from
            // here, and keep pace from where the stream now is.
            offset = natural;
            referenceLead = next.arrivalLead + natural;
            target = offset;
        } else {
            offset = Math.min(Math.max(lowest, target), Math.max(lowest, natural + MAX_CATCH_UP));
        }
        lastSwitchError = offset - target;
        return offset;
    }

    /** The first producer's lead over the clock, from its recent video arrivals (once, at the first switch). */
    private void captureReferenceLead() {
        if (referenceLead != Long.MIN_VALUE || leadCount < 30) {
            return;
        }
        int n = Math.min(leadCount, LEAD_SAMPLES);
        long[] sorted = Arrays.copyOf(leadSamples, n);
        Arrays.sort(sorted);
        // A high percentile: the frames least delayed on their way here (pipe reads, scheduling), as for new producers.
        referenceLead = sorted[(int) Math.floor((n - 1) * 0.9)];
    }

    /** Remembers PAT/PMT and which PIDs carry video, from this producer's own tables and PES headers. */
    private void learn(Source s, byte[] p, int pid, boolean pusi) {
        if (pid == 0 && pusi) {
            s.lastPat = p;
            int start = payloadStart(p);
            if (start > 0 && start + 1 < PACKET) {
                int ptr = start + 1 + (p[start] & 0xff);
                int sectionLength = ((p[ptr + 1] & 0x0f) << 8) | (p[ptr + 2] & 0xff);
                for (int i = ptr + 8; i + 4 <= ptr + 3 + sectionLength - 4 && i + 4 <= PACKET; i += 4) {
                    int program = ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
                    int pmt = ((p[i + 2] & 0x1f) << 8) | (p[i + 3] & 0xff);
                    if (program != 0) {
                        s.pmtPids.put(pmt, program);
                    }
                }
            }
        } else if (s.pmtPids.containsKey(pid) && pusi) {
            s.lastPmt = p;
        } else if (pusi && isPes(p)) {
            int start = payloadStart(p);
            int streamId = p[start + 3] & 0xff;
            s.videoPid.put(pid, streamId >= 0xe0 && streamId <= 0xef);
        }
    }

    /** Sends one packet: timestamps shifted (after the first switch), continuity counters continued. */
    private void emit(Source s, byte[] p, ByteArrayOutputStream out, long arrival) {
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        int afc = (p[3] >> 4) & 0x3;
        boolean shift = s.offset != 0 || switches > 0;
        if ((p[1] & 0x40) != 0 && isPes(p)) {
            if (!timestamps(s, p, pid, shift, arrival)) {
                s.dropping.add(pid);
                return;
            }
            s.dropping.remove(pid);
        } else if (s.dropping.contains(pid)) {
            return; // the rest of a dropped audio PES
        }
        if (shift) {
            shiftPcr(s, p, pid);
        }
        // Continuity counters keep counting across producers, per PID.
        if (pid != 0x1fff) {
            Integer cc = nextCc.get(pid);
            if ((afc & 1) != 0) {
                int value = cc == null ? (p[3] & 0x0f) : cc;
                p[3] = (byte) ((p[3] & 0xf0) | (value & 0x0f));
                nextCc.put(pid, (value + 1) & 0x0f);
            } else if (cc != null) {
                p[3] = (byte) ((p[3] & 0xf0) | ((cc - 1) & 0x0f));
            }
        }
        out.write(p, 0, PACKET);
    }

    /**
     * Shifts (when {@code shift}) and records a PES header's timestamps. Returns false for an audio PES
     * that would start before the previous producer's audio has finished: it is dropped.
     */
    private boolean timestamps(Source s, byte[] p, int pid, boolean shift, long arrival) {
        int start = payloadStart(p);
        int flags = (p[start + 7] & 0xc0) >> 6;
        if ((flags & 2) == 0) {
            return true;
        }
        boolean video = Boolean.TRUE.equals(s.videoPid.get(pid));
        long ownPts = s.unwrap(pid * 4, readTs(p, start + 9));
        long pts = ownPts + s.offset;
        long ownDts = ownPts;
        long dts = pts;
        if (flags == 3) {
            ownDts = s.unwrap(pid * 4 + 1, readTs(p, start + 14));
            dts = ownDts + s.offset;
        }
        if (video) {
            if (s.ownVideoDts != Long.MIN_VALUE) {
                long d = ownDts - s.ownVideoDts;
                if (d > 0 && d <= 9000) {
                    frameDuration = d; // at least 10 fps; ignores the gaps of a stalled producer
                }
            }
            s.ownVideoDts = ownDts;
        } else {
            long[] a = audioOut.computeIfAbsent(pid, k -> new long[] {Long.MIN_VALUE, 0, -1});
            if (a[2] != s.id && a[0] != Long.MIN_VALUE && pts < a[0] + a[1] - 90) {
                return false; // overlaps the previous producer's audio (1 ms tolerance for rounding)
            }
            Long ownLast = s.ownAudioPts.put(pid, ownPts);
            if (ownLast != null && ownPts - ownLast > 0 && ownPts - ownLast <= 45_000) {
                a[1] = ownPts - ownLast; // one PES's duration, from this producer's own spacing
            }
            a[0] = pts;
            a[2] = s.id;
        }
        if (shift) {
            writeTs(p, start + 9, pts, flags == 3 ? 0x3 : 0x2);
            if (flags == 3) {
                writeTs(p, start + 14, dts, 0x1);
            }
        }
        note(Math.max(pts, dts));
        if (video) {
            lastVideoDts = Math.max(lastVideoDts, dts);
            maxVideoPts = Math.max(maxVideoPts, pts);
            if (switches == 0) {
                leadSamples[leadCount % LEAD_SAMPLES] = dts - arrival;
                leadCount++;
            }
        }
        return true;
    }

    private void shiftPcr(Source s, byte[] p, int pid) {
        int afc = (p[3] >> 4) & 0x3;
        if ((afc & 2) == 0 || (p[4] & 0xff) == 0 || (p[5] & 0x10) == 0) {
            return;
        }
        long base = ((long) (p[6] & 0xff) << 25) | ((p[7] & 0xff) << 17) | ((p[8] & 0xff) << 9)
                | ((p[9] & 0xff) << 1) | ((p[10] & 0x80) >> 7);
        long shifted = Math.floorMod(s.unwrap(pid * 4 + 2, base) + s.offset, WRAP);
        p[6] = (byte) (shifted >> 25);
        p[7] = (byte) (shifted >> 17);
        p[8] = (byte) (shifted >> 9);
        p[9] = (byte) (shifted >> 1);
        p[10] = (byte) ((p[10] & 0x7f) | ((shifted & 1) << 7));
    }

    private void note(long t) {
        if (t > maxOut) {
            maxOut = t;
        }
    }

    /**
     * A held packet's PES timestamps (unwrapped, own timeline) as {pts, dts, video keyframe ? 1 : 0},
     * or null when it carries none. Without a DTS, dts is the PTS.
     */
    private static long[] timestamps(Source s, byte[] p) {
        if ((p[1] & 0x40) == 0 || !isPes(p)) {
            return null;
        }
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        int start = payloadStart(p);
        int flags = (p[start + 7] & 0xc0) >> 6;
        if ((flags & 2) == 0) {
            return null;
        }
        long pts = s.unwrap(pid * 4, readTs(p, start + 9));
        long dts = flags == 3 ? s.unwrap(pid * 4 + 1, readTs(p, start + 14)) : pts;
        boolean key = Boolean.TRUE.equals(s.videoPid.get(pid)) && randomAccess(p);
        return new long[] {pts, dts, key ? 1 : 0};
    }

    static int payloadStart(byte[] p) {
        int afc = (p[3] >> 4) & 0x3;
        if ((afc & 1) == 0) {
            return -1;
        }
        return (afc & 2) != 0 ? 5 + (p[4] & 0xff) : 4;
    }

    static boolean isPes(byte[] p) {
        int s = payloadStart(p);
        return s > 0 && s + 14 <= PACKET && p[s] == 0 && p[s + 1] == 0 && p[s + 2] == 1;
    }

    static boolean randomAccess(byte[] p) {
        int afc = (p[3] >> 4) & 0x3;
        return (afc & 2) != 0 && (p[4] & 0xff) > 0 && (p[5] & 0x40) != 0;
    }

    static long readTs(byte[] p, int i) {
        return ((long) (p[i] & 0x0e) << 29) | ((p[i + 1] & 0xff) << 22) | ((p[i + 2] & 0xfe) << 14)
                | ((p[i + 3] & 0xff) << 7) | ((p[i + 4] & 0xfe) >> 1);
    }

    static void writeTs(byte[] p, int i, long unwrapped, int prefix) {
        long t = Math.floorMod(unwrapped, WRAP);
        p[i] = (byte) ((prefix << 4) | ((t >> 29) & 0x0e) | 1);
        p[i + 1] = (byte) (t >> 22);
        p[i + 2] = (byte) (((t >> 14) & 0xfe) | 1);
        p[i + 3] = (byte) (t >> 7);
        p[i + 4] = (byte) (((t << 1) & 0xfe) | 1);
    }
}
