package com.afklive.streamer.service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * OPS: the check behind the public {@code /healthz} (HealthController). Cheap on purpose: one trivial
 * query with a short timeout, the answer reused for {@link #REUSE} per instance, so a monitor (or anyone)
 * calling it often costs the database almost nothing. A check that hangs (database or connection pool
 * stuck) answers "not healthy" after {@link #TIMEOUT} instead of hanging the caller, and later callers
 * wait on that same check rather than starting more.
 */
@Component
public class HealthzProbe implements DisposableBean {

    /** {@code ?workers=1}: a stream server must have reported within this. */
    public static final Duration WORKER_FRESH = Duration.ofMinutes(2);
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    static final Duration REUSE = Duration.ofSeconds(5);
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ExecutorService checks = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "healthz");
        t.setDaemon(true);
        return t;
    });

    private record Answer(boolean ok, long at) {
    }

    /** Index 0: the database check; 1: database and workers. */
    private final Answer[] last = new Answer[2];
    private final Future<?>[] running = new Future<?>[2];

    @Autowired
    public HealthzProbe(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public HealthzProbe(DataSource dataSource, Clock clock) {
        this.clock = clock;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    /** True when the database answers (and, with workers, a stream server reported recently). Never throws. */
    public boolean healthy(boolean withWorkers) {
        int i = withWorkers ? 1 : 0;
        Future<?> f;
        synchronized (this) {
            Answer a = last[i];
            if (a != null && System.nanoTime() - a.at() < REUSE.toNanos()) {
                return a.ok();
            }
            f = running[i];
            if (f == null || f.isDone()) {
                f = checks.submit(() -> probe(withWorkers));
                running[i] = f;
            }
        }
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(f.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        } catch (Exception e) {
            ok = false;
        }
        synchronized (this) {
            last[i] = new Answer(ok, System.nanoTime());
        }
        return ok;
    }

    private boolean probe(boolean withWorkers) {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                return false;
            }
            if (!withWorkers) {
                return true;
            }
            Long n = jdbc.queryForObject("SELECT count(*) FROM stream_workers WHERE heartbeat_at > ?", Long.class,
                    OffsetDateTime.now(clock).minus(WORKER_FRESH));
            return n != null && n > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        checks.shutdownNow();
    }
}
