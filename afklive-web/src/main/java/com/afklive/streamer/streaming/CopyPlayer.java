package com.afklive.streamer.streaming;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.streaming.studio.PlaylistFeed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * FO-2 copy mode: plays a playlist of profile copies (files already encoded exactly as the stream
 * sends them) back to back with FFmpeg's concat demuxer and stream copy, paced in real time. No
 * decode, no encode: a few percent of one CPU instead of most of one.
 * <p>
 * It is a {@link StreamRunner.EncoderLauncher}: each launch starts at the current playlist position
 * (after a crash, or when the scheduled list runs out), and the position is known from the wall clock
 * because the output is paced. The studio and this player use the same item ids and positions, so a
 * stream hands over between them mid-playlist.
 */
public final class CopyPlayer implements StreamRunner.EncoderLauncher {

    /** One playlist item: its id ("mediaId|key", as the studio uses), title, local profile copy and length. */
    public record Entry(String itemId, String title, Path file, double durationSeconds) {
    }

    /** What's on air: the item, how far into it, and the next ones. */
    public record Now(Entry entry, double elapsedSeconds, List<Entry> upNext, int loopsCompleted) {
    }

    /** An endless list is scheduled this far ahead per launch; the player relaunches seamlessly after. */
    static final double SCHEDULE_SECONDS = 48 * 3600;

    private record Slot(Entry entry, double inpoint, double startsAt, int loop) {
    }

    private final List<Entry> items;
    private final PlaylistFeed.Order order;
    private final int loops; // passes in total, 0 = forever (as PlaylistFeed)
    private final Path workDir;
    private final String ffmpeg;
    private final Random random;
    private final Object lock = new Object();
    private List<Slot> schedule = List.of();
    private long launchedAtNanos;
    private int index;
    private double offset;
    private int loopsCompleted;
    private boolean finished;

    public CopyPlayer(List<Entry> items, PlaylistFeed.Order order, int loops, StudioSpec.Position resume, Path workDir,
                      String ffmpeg, Random random) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Copy mode needs at least one item");
        }
        this.items = List.copyOf(items);
        this.order = order;
        this.loops = Math.max(0, loops);
        this.workDir = workDir;
        this.ffmpeg = ffmpeg;
        this.random = random;
        if (resume != null && resume.itemId() != null) {
            for (int i = 0; i < this.items.size(); i++) {
                if (this.items.get(i).itemId().equals(resume.itemId())) {
                    index = i;
                    offset = Math.max(0, Math.min(resume.offsetSeconds(), this.items.get(i).durationSeconds() - 1));
                    break;
                }
            }
            loopsCompleted = resume.loopsCompleted();
        }
    }

    @Override
    public Process launch() throws IOException {
        synchronized (lock) {
            List<Slot> slots = plan();
            Files.createDirectories(workDir);
            Path list = workDir.resolve("copy-" + UUID.randomUUID().toString().substring(0, 8) + ".ffconcat");
            StringBuilder sb = new StringBuilder("ffconcat version 1.0\n");
            for (Slot s : slots) {
                sb.append("file '").append(s.entry().file().toString().replace("'", "'\\''")).append("'\n");
                if (s.inpoint() > 0) {
                    sb.append(String.format(Locale.ROOT, "inpoint %.3f%n", s.inpoint()));
                }
            }
            Files.writeString(list, sb.toString());
            schedule = slots;
            launchedAtNanos = System.nanoTime();
            return new ProcessBuilder(command(ffmpeg, list)).start();
        }
    }

    /** The concat list from the current position: the rest of this pass, then more passes as the loop count allows. */
    private List<Slot> plan() {
        List<Slot> slots = new ArrayList<>();
        double t = 0;
        int loop = loopsCompleted;
        List<Entry> pass = new ArrayList<>(items);
        int i = index;
        double in = offset;
        while (true) {
            for (; i < pass.size(); i++) {
                Entry e = pass.get(i);
                slots.add(new Slot(e, in, t, loop));
                t += e.durationSeconds() - in;
                in = 0;
            }
            loop++;
            if ((loops > 0 && loop >= loops) || t >= SCHEDULE_SECONDS) {
                break;
            }
            if (order == PlaylistFeed.Order.SHUFFLE) {
                Collections.shuffle(pass, random);
            }
            i = 0;
        }
        return slots;
    }

    static List<String> command(String ffmpeg, Path list) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-progress", "pipe:2", "-stats_period", "2",
                "-re", "-f", "concat", "-safe", "0", "-i", list.toString(),
                "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
                "-f", "mpegts", "-mpegts_flags", "+resend_headers", "-muxdelay", "0", "pipe:1");
    }

    /** Where the stream is now, from the wall clock (the output is paced in real time). */
    public Now now() {
        synchronized (lock) {
            List<Slot> slots = schedule;
            if (slots.isEmpty()) {
                Entry e = items.get(index);
                return new Now(e, offset, upNext(index), loopsCompleted);
            }
            double elapsed = (System.nanoTime() - launchedAtNanos) / 1e9;
            for (int s = 0; s < slots.size(); s++) {
                Slot slot = slots.get(s);
                double end = s + 1 < slots.size() ? slots.get(s + 1).startsAt() : Double.MAX_VALUE;
                if (elapsed < end) {
                    List<Entry> next = new ArrayList<>();
                    for (int k = s + 1; k < Math.min(slots.size(), s + 4); k++) {
                        next.add(slots.get(k).entry());
                    }
                    double into = slot.inpoint() + (elapsed - slot.startsAt());
                    return new Now(slot.entry(), Math.min(into, slot.entry().durationSeconds()), next, slot.loop());
                }
            }
            Slot last = slots.getLast();
            return new Now(last.entry(), last.entry().durationSeconds(), List.of(), last.loop());
        }
    }

    private List<Entry> upNext(int from) {
        List<Entry> next = new ArrayList<>();
        for (int k = 1; k <= Math.min(3, items.size() - 1); k++) {
            next.add(items.get((from + k) % items.size()));
        }
        return next;
    }

    /** The playlist position in the studio's terms, for resume and for handing over to the studio. */
    public StudioSpec.Position position() {
        Now n = now();
        return new StudioSpec.Position(n.entry().itemId(), n.elapsedSeconds(), n.loopsCompleted());
    }

    @Override
    public void ended(Process process) {
        // Carry on from where it got to when launched again (crash, or the end of the scheduled list).
        synchronized (lock) {
            Now n = now();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).itemId().equals(n.entry().itemId())) {
                    index = i;
                    break;
                }
            }
            offset = n.elapsedSeconds() >= n.entry().durationSeconds() - 0.5 ? 0 : n.elapsedSeconds();
            if (offset == 0 && n.elapsedSeconds() > 0) {
                index = (index + 1) % items.size();
                if (index == 0) {
                    loopsCompleted = n.loopsCompleted() + 1;
                }
            } else {
                loopsCompleted = n.loopsCompleted();
            }
            finished = loops > 0 && loopsCompleted >= loops;
            schedule = List.of();
        }
    }

    @Override
    public boolean endless() {
        synchronized (lock) {
            return loops == 0 && !finished;
        }
    }

    /** The default FFmpeg binary, for callers outside tests. */
    public static String defaultFfmpeg() {
        return FFmpegCommandBuilder.ffmpegBinary();
    }
}
