package com.afklive.streamer.streaming;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins the MPEG-TS output of successive producer processes into one continuous programme, so the
 * relays (and the platforms behind them) never see a new timeline and never reconnect when the
 * process making the stream changes: a planned switch between copy mode and the studio (FO-2), or an
 * encoder restarted after a crash.
 * <p>
 * Producers are numbered by the caller. The first one plays as-is. A later one is <em>expected</em>
 * ({@link #expect}): its packets are held from its first video keyframe on, while the current producer
 * keeps playing; the switch happens at the current producer's next video frame boundary (or at once,
 * if it has ended), so neither side's frames are cut in half. From then on the new producer's
 * timestamps (PTS, DTS, PCR) are shifted to continue right after the last ones sent, and continuity
 * counters keep counting per PID. Timestamps are unwrapped across the 33-bit rollover (every 26.5
 * hours), so the offset math holds for streams that run for weeks.
 * <p>
 * Assumes what our producers write (FFmpeg's mpegts muxer): one programme, video and audio as PES
 * with PTS (video also DTS), keyframes flagged with the random access indicator.
 */
public final class TsStitcher {

    public static final int PACKET = 188;
    static final long WRAP = 1L << 33;
    /** About one frame at 30 fps, in 90 kHz units: the new producer starts this long after the last timestamp. */
    static final long GAP = 3000;
    /** Packets held from an expected producer before it's allowed to cut over: about 10 s at 6 Mbit/s. */
    static final int MAX_HELD = 40_000;

    public interface Sink {
        void write(byte[] data);
    }

    /** Called (under the stitcher's lock) when an expected producer takes over. */
    public interface Cutover {
        void switched(int producer);
    }

    private final Sink sink;
    private final Cutover cutover;
    private final Map<Integer, Source> sources = new HashMap<>();
    private final Map<Integer, Integer> nextCc = new HashMap<>();
    private int current = -1;
    private int expected = -1;
    /** Largest timestamp sent so far, unwrapped, in 90 kHz units. */
    private long maxOut = Long.MIN_VALUE;
    private long switches;

    public TsStitcher(Sink sink, Cutover cutover) {
        this.sink = sink;
        this.cutover = cutover;
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
        boolean ended;

        Source(int id) {
            this.id = id;
        }

        /** Unwraps a 33-bit timestamp of one kind (pts/dts/pcr per PID) into a continuous count. */
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
            tryCutoverNow();
        }
    }

    public synchronized int current() {
        return current;
    }

    public synchronized long switches() {
        return switches;
    }

    /** Bytes from producer {@code id}; anything from a producer that is neither current nor expected is dropped. */
    public synchronized void feed(int id, byte[] data, int length) {
        Source s = sources.get(id);
        if (s == null || (id != current && id != expected)) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + PACKET);
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
                packet(s, s.partial.clone(), out);
                s.partialLength = 0;
            } else {
                byte[] p = new byte[PACKET];
                System.arraycopy(data, pos, p, 0, PACKET);
                pos += PACKET;
                packet(s, p, out);
            }
            if (id != current && id != expected) {
                break; // replaced while this chunk was being read
            }
        }
        if (out.size() > 0) {
            sink.write(out.toByteArray());
        }
    }

    private void packet(Source s, byte[] p, ByteArrayOutputStream out) {
        if ((p[0] & 0xff) != 0x47) {
            return; // not aligned: FFmpeg always writes whole packets, so this never happens with our producers
        }
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        boolean pusi = (p[1] & 0x40) != 0;
        learn(s, p, pid, pusi);
        if (s.id == expected) {
            hold(s, p, pid, pusi);
            if (s.started && sources.get(current) != null && sources.get(current).ended) {
                tryCutoverNow();
            }
            return;
        }
        // Current producer: switch at a video frame boundary once the next one is ready.
        if (expected >= 0 && pusi && Boolean.TRUE.equals(s.videoPid.get(pid)) && isPes(p)) {
            Source next = sources.get(expected);
            if (next != null && next.started) {
                cutover(next, out);
                return;
            }
        }
        emit(s, p, out);
    }

    private void hold(Source s, byte[] p, int pid, boolean pusi) {
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
        if (s.held.size() < MAX_HELD) {
            s.held.add(p);
        }
    }

    private void tryCutoverNow() {
        if (expected < 0) {
            return;
        }
        Source next = sources.get(expected);
        if (next != null && next.started) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cutover(next, out);
            if (out.size() > 0) {
                sink.write(out.toByteArray());
            }
        }
    }

    private void cutover(Source next, ByteArrayOutputStream out) {
        long first = Long.MAX_VALUE;
        for (byte[] p : next.held) {
            long t = firstTimestamp(next, p);
            if (t != Long.MIN_VALUE) {
                first = Math.min(first, t);
            }
        }
        // Reset the unwrap state: firstTimestamp() above walked the held packets once already.
        next.lastRaw.clear();
        next.epoch.clear();
        next.offset = (first == Long.MAX_VALUE || maxOut == Long.MIN_VALUE) ? 0 : maxOut + GAP - first;
        int previous = current;
        current = next.id;
        expected = -1;
        switches++;
        for (byte[] p : next.held) {
            emit(next, p, out);
        }
        next.held.clear();
        sources.remove(previous);
        cutover.switched(next.id);
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

    private void emit(Source s, byte[] p, ByteArrayOutputStream out) {
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        int afc = (p[3] >> 4) & 0x3;
        if (s.offset != 0 || switches > 0) {
            shiftPcr(s, p, pid);
            if ((p[1] & 0x40) != 0 && isPes(p)) {
                shiftPes(s, p, pid);
            }
        } else {
            track(s, p, pid);
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

    /** First producer, no shift: only remember how far the timeline has got. */
    private void track(Source s, byte[] p, int pid) {
        if ((p[1] & 0x40) != 0 && isPes(p)) {
            int start = payloadStart(p);
            int flags = (p[start + 7] & 0xc0) >> 6;
            if ((flags & 2) != 0) {
                note(s.unwrap(pid * 4, readTs(p, start + 9)));
            }
            if (flags == 3) {
                note(s.unwrap(pid * 4 + 1, readTs(p, start + 14)));
            }
        }
    }

    private void shiftPes(Source s, byte[] p, int pid) {
        int start = payloadStart(p);
        int flags = (p[start + 7] & 0xc0) >> 6;
        if ((flags & 2) != 0) {
            long t = s.unwrap(pid * 4, readTs(p, start + 9)) + s.offset;
            writeTs(p, start + 9, t, flags == 3 ? 0x3 : 0x2);
            note(t);
        }
        if (flags == 3) {
            long t = s.unwrap(pid * 4 + 1, readTs(p, start + 14)) + s.offset;
            writeTs(p, start + 14, t, 0x1);
            note(t);
        }
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

    /** The earliest PTS/DTS in a held packet (unwrapped), or MIN_VALUE when it carries none. */
    private static long firstTimestamp(Source s, byte[] p) {
        if ((p[1] & 0x40) == 0 || !isPes(p)) {
            return Long.MIN_VALUE;
        }
        int pid = ((p[1] & 0x1f) << 8) | (p[2] & 0xff);
        int start = payloadStart(p);
        int flags = (p[start + 7] & 0xc0) >> 6;
        long t = Long.MIN_VALUE;
        if ((flags & 2) != 0) {
            t = s.unwrap(pid * 4, readTs(p, start + 9));
        }
        if (flags == 3) {
            t = Math.min(t, s.unwrap(pid * 4 + 1, readTs(p, start + 14)));
        }
        return t;
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
