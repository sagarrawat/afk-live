package com.afklive.streamer.streaming;

import com.afklive.streamer.util.Redact;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * AD-1 / AD-3: the last {@link #MAX_LINES} lines of FFmpeg output for one run, keys removed as each
 * line arrives (nothing unredacted is ever kept). The worker writes it to the database now and then
 * so admins can read it from the console.
 */
public final class JobLog {

    public static final int MAX_LINES = 200;
    static final int MAX_LINE_CHARS = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Deque<String> lines = new ArrayDeque<>();
    private final List<String> secrets;
    private final Supplier<ZonedDateTime> clock;
    private long version;
    private long savedVersion;

    public JobLog(Collection<String> secrets) {
        this(secrets, () -> ZonedDateTime.now(ZoneId.of("UTC")));
    }

    JobLog(Collection<String> secrets, Supplier<ZonedDateTime> clock) {
        this.secrets = List.copyOf(secrets);
        this.clock = clock;
    }

    /** {@code source}: "encoder" or "dest 1" (1-based, as admins count). */
    public void add(String source, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String clean = Redact.line(line.strip(), secrets);
        if (clean.length() > MAX_LINE_CHARS) {
            clean = clean.substring(0, MAX_LINE_CHARS) + "…";
        }
        String entry = clock.get().format(TIME) + " " + source + ": " + clean;
        synchronized (this) {
            lines.addLast(entry);
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
            version++;
        }
    }

    /** Lines saved earlier for this run (already redacted), placed before anything added here. */
    public synchronized void seed(String saved) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        Deque<String> mine = new ArrayDeque<>(lines);
        lines.clear();
        for (String l : saved.split("\n")) {
            if (!l.isBlank()) {
                lines.addLast(l);
            }
        }
        lines.addAll(mine);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public synchronized boolean hasUnsaved() {
        return version != savedVersion;
    }

    /** The text to save; call {@link #saved(long)} with {@link #version()} once it's written. */
    public synchronized String text() {
        return String.join("\n", lines);
    }

    public synchronized long version() {
        return version;
    }

    public synchronized void saved(long v) {
        savedVersion = Math.max(savedVersion, v);
    }

    public synchronized int size() {
        return lines.size();
    }
}
