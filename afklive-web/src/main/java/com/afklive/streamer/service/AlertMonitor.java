package com.afklive.streamer.service;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.NotificationRepository;
import com.afklive.streamer.repository.StreamIncidentRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.streaming.StreamStates;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NT-1: watches every live run and alerts the creator within 2 minutes when it goes down (or a
 * destination, or the phone signal), and again when it recovers. A problem must last a while before
 * it counts, so a 5-second reconnect never wakes anyone. Incidents live in the database with a
 * unique "open" row, so several web instances alert once. Also NT-2's storage check.
 */
@Service
@Slf4j
public class AlertMonitor {

    static final Duration CHECK_EVERY = Duration.ofSeconds(30);
    /** A problem shorter than this is a hiccup, not an outage. */
    static final Duration DOWN_AFTER = Duration.ofSeconds(60);
    static final Duration HEARTBEAT_STALE = Duration.ofSeconds(60);
    static final Duration PHONE_LOST_AFTER = Duration.ofMinutes(2);
    static final Duration STUCK_STARTING_AFTER = Duration.ofMinutes(3);
    /** RE-9: one destination not reaching its platform while the stream still reaches others alerts after this long. */
    static final Duration DEST_DOWN_AFTER = Duration.ofMinutes(5);
    /** A destination that keeps dropping and coming back alerts at most this often. */
    static final Duration DEST_ALERT_EVERY = Duration.ofHours(1);

    static final String DOWN = "DOWN";
    static final String DEST = "DEST";
    static final String PHONE = "PHONE";
    static final String STARTING = "STARTING";
    /** RE-8: the stream runs but no platform is receiving it. For viewers it is down. */
    static final String UNREACHED = "UNREACHED";
    /** Incidents that count as the stream being down (RE-4 uptime). */
    public static final List<String> OUTAGE_KINDS = List.of(DOWN, UNREACHED);

    private final StreamJobRepository jobs;
    private final StreamIncidentRepository incidents;
    private final LiveStreamRepository streams;
    private final UserRepository users;
    private final PlanService plans;
    private final NotificationService notifier;
    private final NotificationRepository notificationRepository;
    private final String role;
    /** When each condition was first seen bad by this instance ("jobId:kind:target"). */
    private final Map<String, ZonedDateTime> firstBad = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alert-monitor");
        t.setDaemon(true);
        return t;
    });
    private long ticks;

    public AlertMonitor(StreamJobRepository jobs, StreamIncidentRepository incidents, LiveStreamRepository streams,
                        UserRepository users, PlanService plans, NotificationService notifier,
                        NotificationRepository notificationRepository, @Value("${app.streaming.role:all}") String role) {
        this.jobs = jobs;
        this.incidents = incidents;
        this.streams = streams;
        this.users = users;
        this.plans = plans;
        this.notifier = notifier;
        this.notificationRepository = notificationRepository;
        this.role = role;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                check(ZonedDateTime.now(ZoneId.of("UTC")));
                if (++ticks % 120 == 1) { // hourly
                    checkStorage();
                    notificationRepository.deleteOlderThan(ZonedDateTime.now(ZoneId.of("UTC")).minusDays(90));
                }
            } catch (Exception e) {
                log.error("Alert check failed", e);
            }
        }, CHECK_EVERY.toSeconds(), CHECK_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** One pass over every live run. Public so tests can drive it with their own clock. */
    public void check(ZonedDateTime now) {
        Set<String> seen = new HashSet<>();
        for (StreamJob job : jobs.findAllByIsLiveTrue()) {
            if (StreamStates.STOP.equals(job.getDesiredState())) {
                continue; // being stopped on purpose
            }
            String name = displayName(job);
            String email = job.getUser().getEmail();
            Long streamId = job.getStreamId();
            String link = streamId == null ? "/studio" : "/streams/" + streamId;

            boolean starting = StreamStates.STARTING.equals(job.getStatus()) || job.getStatus() == null;
            boolean stale = job.getHeartbeatAt() != null && job.getHeartbeatAt().isBefore(now.minus(HEARTBEAT_STALE));
            boolean down = !starting && (StreamStates.RECONNECTING.equals(job.getStatus()) || stale);

            // Started but never got on air.
            condition(seen, job, STARTING, "", starting && job.getStartTime() != null
                            && job.getStartTime().isBefore(now.minus(STUCK_STARTING_AFTER)), now, Duration.ZERO,
                    () -> notifier.notify(email, alert(NotificationService.Types.START_STUCK, Notification.Level.WARNING, streamId,
                            name + " hasn't started yet",
                            "It's been a few minutes and the stream isn't on air. We keep trying; check the stream's health for details.", link)),
                    null);

            // The whole stream is down (encoder restarting, or its server stopped reporting).
            condition(seen, job, DOWN, "", down, now, DOWN_AFTER,
                    () -> notifier.notify(email, alert(NotificationService.Types.STREAM_DOWN, Notification.Level.ERROR, streamId,
                            name + " is down",
                            stale ? "Its stream server stopped responding; we're moving it to another server automatically."
                                    : "The stream is restarting after a problem; viewers may see it buffer. We'll tell you when it's back.",
                            link)),
                    () -> notifier.notify(email, alert(NotificationService.Types.STREAM_RECOVERED, Notification.Level.SUCCESS, streamId,
                            name + " is back live", "The stream recovered on its own.", link)));

            // RE-8: the stream runs, but no platform is receiving it: as bad as down for viewers.
            Map<Integer, String> urls = StreamHealth.urls(job);
            boolean unreached = !starting && !down && StreamHealth.REACH_NONE.equals(StreamHealth.reach(job.getDestinations()));
            condition(seen, job, UNREACHED, "", unreached, now, DOWN_AFTER,
                    () -> notifier.notify(email, alert(NotificationService.Types.STREAM_DOWN, Notification.Level.ERROR, streamId,
                            name + " isn't reaching anyone",
                            "The stream is running, but no platform is receiving it. " + reasons(job, urls) + " We keep trying.", link)),
                    () -> notifier.notify(email, alert(NotificationService.Types.STREAM_RECOVERED, Notification.Level.SUCCESS, streamId,
                            name + " is reaching viewers again", "A platform is receiving the stream again.", link)));

            // RE-9: each destination on its own while the stream still reaches others (all of them is the alert above).
            if (!starting && !down) {
                for (StreamJobDestination d : job.getDestinations()) {
                    String target = String.valueOf(d.getPosition());
                    if (unreached) {
                        String key = job.getId() + ":" + DEST + ":" + target;
                        if (firstBad.containsKey(key)) {
                            seen.add(key); // keeps counting; its own alert waits until the stream reaches someone
                        }
                        continue;
                    }
                    boolean bad = StreamHealth.NOT_REACHING.equals(StreamHealth.reachOf(d));
                    String url = urls.get(d.getPosition());
                    String dn = StreamHealth.label(d, url);
                    String why = bad ? StreamHealth.why(d, url) : "";
                    String dedupe = "dest:" + job.getId() + ":" + target;
                    condition(seen, job, DEST, target, bad, now, DEST_DOWN_AFTER,
                            () -> notifier.notify(email, new NotificationService.Alert(NotificationService.Types.DESTINATION_DOWN,
                                    Notification.Level.ERROR, streamId, name + " isn't reaching " + dn,
                                    why + " Your other destinations are fine. We keep reconnecting to " + dn + ".", link,
                                    dedupe + ":down", DEST_ALERT_EVERY, true)),
                            () -> notifier.notify(email, new NotificationService.Alert(NotificationService.Types.DESTINATION_RECOVERED,
                                    Notification.Level.SUCCESS, streamId, name + " is reaching " + dn + " again",
                                    "The connection recovered.", link, dedupe + ":back", DEST_ALERT_EVERY, true)));
                }
            }

            // Phone streams: BRB has been on for a while (the stream itself is fine).
            condition(seen, job, PHONE, "", Boolean.FALSE.equals(job.getInputLive()) && !starting, now, PHONE_LOST_AFTER,
                    () -> notifier.notify(email, alert(NotificationService.Types.PHONE_LOST, Notification.Level.WARNING, streamId,
                            "Your phone's signal is lost on " + name,
                            "Viewers see your Be right back scene. Check your phone's connection or app.", link)),
                    () -> notifier.notify(email, alert(NotificationService.Types.PHONE_BACK, Notification.Level.SUCCESS, streamId,
                            "Your phone is back on " + name, "Viewers see you live again.", link)));
        }
        // PL-7: files the worker skipped because they couldn't be played, once a day per file.
        for (StreamJob job : jobs.findAllByIsLiveTrue()) {
            for (Map<String, Object> f : failedItems(job.getNowPlaying())) {
                String title = String.valueOf(f.getOrDefault("title", "A file"));
                Long streamId = job.getStreamId();
                notifier.notify(job.getUser().getEmail(), new NotificationService.Alert(NotificationService.Types.ITEM_FAILED,
                        Notification.Level.WARNING, streamId, title + " was skipped",
                        displayName(job) + " couldn't play " + title + ", so it went on to the next item. Replace the file or remove it from the playlist.",
                        streamId == null ? "/studio" : "/streams/" + streamId + "/playlist",
                        "ITEM_FAILED:" + streamId + ":" + f.get("mediaId"), Duration.ofDays(1), true));
            }
        }
        // Forget conditions of runs that ended (their incidents are closed when they end).
        firstBad.keySet().removeIf(k -> !seen.contains(k));
    }

    /**
     * Opens an incident (and alerts once) when {@code bad} has held for {@code after}; closes it (and
     * sends the recovery, if any) when it clears.
     */
    private void condition(Set<String> seen, StreamJob job, String kind, String target, boolean bad, ZonedDateTime now,
                           Duration after, Runnable onDown, Runnable onRecovered) {
        String key = job.getId() + ":" + kind + ":" + target;
        if (bad) {
            seen.add(key);
            ZonedDateTime since = firstBad.computeIfAbsent(key, k -> now);
            if (!since.plus(after).isAfter(now) && incidents.open(job.getId(), kind, target, since) == 1) {
                safely(onDown);
            }
        } else {
            firstBad.remove(key);
            if (incidents.resolve(job.getId(), kind, target, now) == 1 && onRecovered != null) {
                safely(onRecovered);
            }
        }
    }

    /** Each destination's reason, in plain words: "YouTube: YouTube refused the stream key. ..." */
    private static String reasons(StreamJob job, Map<Integer, String> urls) {
        List<String> out = new java.util.ArrayList<>();
        for (StreamJobDestination d : job.getDestinations()) {
            if (StreamHealth.NOT_REACHING.equals(StreamHealth.reachOf(d))) {
                String url = urls.get(d.getPosition());
                String label = StreamHealth.label(d, url);
                out.add(Character.toUpperCase(label.charAt(0)) + label.substring(1) + ": " + StreamHealth.why(d, url));
            }
        }
        return String.join(" ", out);
    }

    /** NT-2: storage almost full (80 %), at most once a day per user. */
    void checkStorage() {
        for (User u : users.findAll()) {
            PlanConfig plan;
            try {
                plan = plans.getPlanConfig(u.getPlanType());
            } catch (Exception e) {
                continue;
            }
            long max = plan.getMaxStorageBytes();
            if (max > 0 && u.getUsedStorageBytes() >= max * 0.8) {
                int pct = (int) Math.min(100, u.getUsedStorageBytes() * 100 / max);
                notifier.notify(u, new NotificationService.Alert(NotificationService.Types.STORAGE_ALMOST_FULL,
                        pct >= 100 ? Notification.Level.ERROR : Notification.Level.WARNING, null,
                        "Your library is " + pct + "% full",
                        pct >= 100 ? "New uploads are blocked until you free space or upgrade."
                                : "Delete files you no longer use, or upgrade for more space.",
                        "/library", "storage", Duration.ofDays(1), true));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> failedItems(String nowPlaying) {
        if (nowPlaying == null || !nowPlaying.contains("\"failed\"")) {
            return List.of();
        }
        try {
            Object f = com.afklive.streamer.streaming.StudioSpec.parse(nowPlaying, Map.class).get("failed");
            return f instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private NotificationService.Alert alert(String type, Notification.Level level, Long streamId, String title, String body, String link) {
        return new NotificationService.Alert(type, level, streamId, title, body, link, null, null, level != Notification.Level.INFO);
    }

    private String displayName(StreamJob job) {
        if (job.getStreamId() != null) {
            return streams.findById(job.getStreamId()).map(LiveStream::getName).orElse("Your stream");
        }
        return job.getTitle() != null && !job.getTitle().isBlank() ? job.getTitle() : "Your stream";
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Alert delivery failed: {}", e.getMessage());
        }
    }

    /** Closes a run's open incidents when it ends (no "recovered" message for a stream that stopped). */
    public void closeAll(Long jobId) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        for (var i : incidents.findAllByJobIdAndResolvedAtIsNull(jobId)) {
            incidents.resolve(jobId, i.getKind(), i.getTarget(), now);
        }
        List.copyOf(firstBad.keySet()).stream().filter(k -> k.startsWith(jobId + ":")).forEach(firstBad::remove);
    }
}
