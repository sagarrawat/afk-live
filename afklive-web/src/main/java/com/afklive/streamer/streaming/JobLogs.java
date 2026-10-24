package com.afklive.streamer.streaming;

import com.afklive.streamer.repository.StreamJobLogStore;
import com.afklive.streamer.util.Redact;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AD-6: keeps the last {@link JobLog#MAX_LINES} lines of FFmpeg output of each run on this worker
 * (keys removed as each line arrives) and writes them to {@code stream_job_logs} every few seconds,
 * off the stream's threads, so the admin console can show them. The worker only calls
 * {@link #line} and {@link #close}; nothing here can block or fail a stream: a database problem
 * only delays the save.
 */
@Component
@ConditionalOnExpression("'${app.streaming.role:all}' != 'web'")
@Slf4j
public class JobLogs {

    static final Duration SAVE_EVERY = Duration.ofSeconds(10);
    /** A closed run whose log can't be saved is dropped after this many tries, so nothing piles up. */
    static final int MAX_TRIES_AFTER_CLOSE = 6;

    private final StreamJobLogStore store;
    private final Map<Long, Entry> logs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "job-log-saver");
        t.setDaemon(true);
        return t;
    });

    private static final class Entry {
        final JobLog log;
        volatile boolean seeded;
        volatile boolean closed;
        int triesAfterClose;

        Entry(JobLog log) {
            this.log = log;
        }
    }

    public JobLogs(StreamJobLogStore store) {
        this.store = store;
    }

    @PostConstruct
    void start() {
        saver.scheduleWithFixedDelay(this::saveAll, SAVE_EVERY.toSeconds(), SAVE_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        saver.shutdownNow();
        saveAll();
    }

    /** A line FFmpeg wrote for run {@code jobId}; {@code spec} says which keys to remove. Never throws. */
    public void line(long jobId, StreamSpec spec, String source, String line) {
        try {
            logs.computeIfAbsent(jobId, id -> new Entry(new JobLog(secretsOf(spec)))).log.add(source, line);
        } catch (RuntimeException e) {
            log.debug("Could not keep a log line of run {}: {}", jobId, e.getMessage());
        }
    }

    /** The run stopped here (ended, or moved to another worker): save what's left, then forget it. */
    public void close(long jobId, String note) {
        Entry e = logs.get(jobId);
        if (e == null) {
            return;
        }
        if (note != null) {
            e.log.add("worker", note);
        }
        e.closed = true;
        try {
            saver.execute(this::saveAll);
        } catch (RuntimeException ignored) {
            // shutting down: stop() saves
        }
    }

    /** Every key in a run's destinations (whole address, key, password, query values). */
    static List<String> secretsOf(StreamSpec spec) {
        List<String> out = new ArrayList<>();
        if (spec != null && spec.destinations() != null) {
            for (StreamSpec.Destination d : spec.destinations()) {
                out.addAll(Redact.secretsOf(d.url()));
            }
        }
        return out;
    }

    synchronized void saveAll() {
        for (Map.Entry<Long, Entry> me : logs.entrySet()) {
            long id = me.getKey();
            Entry e = me.getValue();
            try {
                if (!e.seeded) {
                    // A run taken over from another worker (or resumed after a restart) keeps the earlier lines.
                    store.find(id).ifPresent(saved -> e.log.seed(saved.lines()));
                    e.seeded = true;
                }
                if (e.log.hasUnsaved()) {
                    long v = e.log.version();
                    store.save(id, e.log.text(), ZonedDateTime.now(ZoneId.of("UTC")));
                    e.log.saved(v);
                }
                if (e.closed && !e.log.hasUnsaved()) {
                    logs.remove(id, e);
                }
            } catch (Exception ex) {
                log.debug("Saving the log of run {} failed: {}", id, ex.getMessage());
                if (e.closed && ++e.triesAfterClose >= MAX_TRIES_AFTER_CLOSE) {
                    logs.remove(id, e);
                }
            }
        }
    }

    /** For tests. */
    int open() {
        return logs.size();
    }
}
