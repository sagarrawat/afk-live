package com.afklive.streamer.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ON-8: "first live confirmed", the activation metric. An account's first live is confirmed the first
 * time any destination of any of its runs reached state ON (RE-8: a platform has been receiving the
 * stream steadily for 10 s). It is stored once on the account ({@code users.first_live_at}).
 * <p>
 * <strong>Where it's detected.</strong> A sweep on the web side, every 30 s, like {@link AlertMonitor},
 * reading what the worker already writes: the run's {@code REACHED} timeline event (written once per
 * destination when it first turns ON, and kept after the run ends, so a short run is never missed)
 * and, as a fallback for a lost event, a live run's destination whose {@code reach} is ON right now.
 * The record is one conditional {@code UPDATE ... WHERE first_live_at IS NULL RETURNING id}, so when
 * several web instances sweep at once each account is claimed by exactly one of them, and only that
 * one runs the follow-ups (GR-18 referral credit). Not in the worker: workers stay about streaming
 * (no billing or notifications there), this needs no worker deploy, and web and worker still talk only
 * through the database.
 */
@Service
@Slf4j
public class FirstLiveService {

    static final Duration CHECK_EVERY = Duration.ofSeconds(30);

    /**
     * Accounts with no first live yet whose stream a platform has received: the earliest REACHED event,
     * or now for a destination that is ON in a live run without one. The {@code first_live_at IS NULL}
     * condition is re-checked on the locked row, so a concurrent sweep that already set it skips it.
     */
    static final String RECORD = """
            UPDATE users u SET first_live_at = f.first
            FROM (
                SELECT user_id, min(at) AS first FROM (
                    SELECT j.user_id, e.at FROM stream_events e JOIN stream_jobs j ON j.id = e.job_id
                    WHERE e.kind = 'REACHED' AND j.user_id IN (SELECT id FROM users WHERE first_live_at IS NULL)
                    UNION ALL
                    SELECT j.user_id, now() FROM stream_jobs j JOIN stream_job_destinations d ON d.job_id = j.id
                    WHERE j.is_live = true AND d.reach = 'ON'
                      AND j.user_id IN (SELECT id FROM users WHERE first_live_at IS NULL)
                ) seen GROUP BY user_id
            ) f
            WHERE u.id = f.user_id AND u.first_live_at IS NULL
            RETURNING u.id
            """;

    private final JdbcTemplate jdbc;
    private final ReferralService referrals;
    private final String role;
    private final boolean auto;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "first-live");
        t.setDaemon(true);
        return t;
    });

    public FirstLiveService(JdbcTemplate jdbc, ReferralService referrals,
                            @Value("${app.streaming.role:all}") String role,
                            @Value("${app.first-live.auto:true}") boolean auto) {
        this.jdbc = jdbc;
        this.referrals = referrals;
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
                sweep();
            } catch (Exception e) {
                log.error("First-live sweep failed", e);
            }
        }, CHECK_EVERY.toSeconds(), CHECK_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** One pass: records new first lives, then credits referrals whose invited creator is now confirmed. */
    public List<Long> sweep() {
        List<Long> recorded = recordFirstLives();
        referrals.creditConfirmed();
        return recorded;
    }

    /** Records "first live confirmed" for every account that just got there. Returns the accounts this call recorded. */
    public List<Long> recordFirstLives() {
        List<Long> ids = jdbc.queryForList(RECORD, Long.class);
        for (Long id : ids) {
            log.info("First live confirmed for user {}", id);
        }
        return ids;
    }
}
