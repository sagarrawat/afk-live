package com.afklive.streamer.service;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.Notification;
import com.afklive.streamer.repository.LiveStreamRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GR-9: the go-live check once a week for every stream that was live in the past week. One in-app
 * notice per stream, only when something is open (not fixed, not kept), never by email or push: the
 * check is a nudge, not an alert. Each week's check is claimed with a conditional update, so only one
 * web instance sends it.
 */
@Service
@Slf4j
public class GoLiveCheckWeekly {

    public static final String TYPE = "GO_LIVE_CHECK";
    static final Duration EVERY = Duration.ofDays(7);

    private final LiveStreamRepository streams;
    private final GoLiveCheckService checks;
    private final NotificationService notifier;
    private final String role;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "go-live-check");
        t.setDaemon(true);
        return t;
    });

    public GoLiveCheckWeekly(LiveStreamRepository streams, GoLiveCheckService checks, NotificationService notifier,
                             @Value("${app.streaming.role:all}") String role,
                             @Value("${app.check.weekly-auto:true}") boolean auto) {
        this.streams = streams;
        this.checks = checks;
        this.notifier = notifier;
        this.role = role;
        this.auto = auto;
    }

    private final boolean auto;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("worker".equalsIgnoreCase(role) || !auto) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                run(ZonedDateTime.now(ZoneId.of("UTC")));
            } catch (Exception e) {
                log.error("Weekly go-live check failed", e);
            }
        }, 10, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** One pass: returns how many notices were sent. Public so tests drive it with their own clock. */
    public int run(ZonedDateTime now) {
        int sent = 0;
        for (Long id : streams.findIdsLiveSince(now.minus(EVERY))) {
            if (streams.claimWeeklyCheck(id, now, now.minus(EVERY)) != 1) {
                continue; // checked this week already (here or on another instance)
            }
            try {
                LiveStream s = streams.findById(id).orElse(null);
                if (s == null) {
                    continue;
                }
                List<GoLiveCheckService.FlagView> open = checks.openFlags(s);
                if (open.isEmpty()) {
                    continue;
                }
                String body = open.size() == 1 ? open.getFirst().title() + "."
                        : open.getFirst().title() + ", and " + (open.size() - 1) + " more.";
                notifier.notify(s.getUser(), new NotificationService.Alert(TYPE, Notification.Level.INFO, s.getId(),
                        "Weekly check for " + s.getName() + ": " + open.size() + (open.size() == 1 ? " thing" : " things") + " to look at",
                        body + " Each one has a quick fix, or you can keep it as it is.", "/streams/" + s.getId(),
                        "check:" + s.getId(), EVERY.minusHours(12), false));
                sent++;
            } catch (Exception e) {
                log.warn("Weekly check for stream {} failed: {}", id, e.getMessage());
            }
        }
        return sent;
    }
}
