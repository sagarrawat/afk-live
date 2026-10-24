package com.afklive.streamer.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AD-2 history: copies each worker's latest self-report ({@code stream_workers}, written every 30 s by the
 * worker) into {@code worker_samples} once a minute, and drops samples older than 7 days.
 * <p>
 * It runs on the web side, so no worker deploy (and no handover of live streams) is needed, and a worker
 * that stops reporting simply stops getting samples. The minute is the claim: {@code (worker_id, at)} is the
 * primary key and the insert does nothing on conflict, so with two or more web instances each minute is
 * written exactly once, by whichever gets there first. Pruning is a plain delete by time: safe to repeat.
 */
@Service
@Slf4j
public class WorkerSampler {

    static final Duration EVERY = Duration.ofSeconds(20);
    /** Only reports newer than this are sampled (a worker reports every 30 s). */
    static final Duration FRESH = Duration.ofSeconds(90);
    public static final Duration KEEP = Duration.ofDays(7);

    private final NamedParameterJdbcTemplate jdbc;
    private final String role;
    private final boolean auto;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "worker-sampler");
        t.setDaemon(true);
        return t;
    });
    private long ticks;

    public WorkerSampler(NamedParameterJdbcTemplate jdbc, @Value("${app.streaming.role:all}") String role,
                         @Value("${app.admin.worker-samples-auto:true}") boolean auto) {
        this.jdbc = jdbc;
        this.role = role;
        this.auto = auto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("worker".equalsIgnoreCase(role) || !auto) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
                sample(now);
                if (++ticks % 180 == 1) { // about hourly
                    prune(now);
                }
            } catch (Exception e) {
                log.warn("Worker sampling failed: {}", e.getMessage());
            }
        }, EVERY.toSeconds(), EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** Claims this minute for every freshly reporting worker; returns how many samples this call wrote. */
    public int sample(ZonedDateTime now) {
        ZonedDateTime minute = now.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        return jdbc.update("INSERT INTO worker_samples(worker_id, at, cpu_percent, memory_used_bytes, memory_total_bytes, streams) "
                        + "SELECT worker_id, :minute, cpu_percent, memory_used_bytes, memory_total_bytes, streams FROM stream_workers "
                        + "WHERE heartbeat_at > :fresh AND heartbeat_at <= :now "
                        + "ON CONFLICT (worker_id, at) DO NOTHING",
                new MapSqlParameterSource().addValue("minute", minute.toOffsetDateTime())
                        .addValue("fresh", now.minus(FRESH).toOffsetDateTime())
                        .addValue("now", now.plusSeconds(5).toOffsetDateTime()));
    }

    /** Drops samples older than {@link #KEEP}; returns how many. */
    public int prune(ZonedDateTime now) {
        return jdbc.update("DELETE FROM worker_samples WHERE at < :before",
                new MapSqlParameterSource().addValue("before", now.minus(KEEP).toOffsetDateTime()));
    }
}
