package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos.ScheduleRequest;
import com.afklive.streamer.dto.StreamDtos.ScheduleView;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamSchedule;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.StreamScheduleRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SC-1, SC-2, SC-5: one-time and daily/weekly schedules. Every 20 seconds the web app starts any
 * occurrence that has begun (each occurrence once, claimed in the database, so several web
 * instances never start it twice) and sets its stop time. A creator who stops a scheduled stream by
 * hand isn't overruled: that occurrence has already been started. A start that fails is recorded
 * on the schedule with the reason, and can be retried with one click.
 */
@Service
@Slf4j
public class StreamScheduleService {

    static final long TICK_SECONDS = 20;
    /** A one-time schedule without an end may still start this late (e.g. after a web restart). */
    static final Duration OPEN_ENDED_START_WINDOW = Duration.ofMinutes(30);
    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final StreamScheduleRepository schedules;
    private final LiveStreamRepository streams;
    private final StreamJobRepository jobs;
    private final LiveStreamService liveStreams;
    private final StreamService streamService;
    private final String role;
    private final com.afklive.streamer.repository.StreamCommandRepository commands;
    private long ticks;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired
    private NotificationService notifications;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-schedules");
        t.setDaemon(true);
        return t;
    });

    public StreamScheduleService(StreamScheduleRepository schedules, LiveStreamRepository streams, StreamJobRepository jobs,
                                 LiveStreamService liveStreams, StreamService streamService,
                                 com.afklive.streamer.repository.StreamCommandRepository commands,
                                 org.springframework.transaction.PlatformTransactionManager txManager,
                                 @Value("${app.streaming.role:all}") String role) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.commands = commands;
        this.schedules = schedules;
        this.streams = streams;
        this.jobs = jobs;
        this.liveStreams = liveStreams;
        this.streamService = streamService;
        this.role = role;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startTimer() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                runDue(ZonedDateTime.now(ZoneId.of("UTC")));
            } catch (Exception e) {
                log.error("Schedule check failed", e);
            }
            if (++ticks % 180 == 0) { // about hourly: applied commands are no longer needed
                try {
                    commands.deleteOlderThan(ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1));
                } catch (Exception e) {
                    log.debug("Command cleanup failed: {}", e.getMessage());
                }
            }
        }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopTimer() {
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ CRUD

    public List<ScheduleView> list(String email, Long streamId) {
        liveStreams.get(email, streamId);
        return schedules.findAllByStreamIdOrderByIdAsc(streamId).stream().map(this::view).toList();
    }

    public ScheduleView create(String email, Long streamId, ScheduleRequest req) {
        LiveStream s = liveStreams.get(email, streamId);
        if (schedules.findAllByStreamIdOrderByIdAsc(streamId).size() >= 20) {
            throw new IllegalArgumentException("Up to 20 schedules per stream.");
        }
        StreamSchedule sc = new StreamSchedule();
        sc.setStreamId(s.getId());
        apply(sc, req, s.getTimeZone());
        return view(schedules.save(sc));
    }

    public ScheduleView update(String email, Long streamId, Long scheduleId, ScheduleRequest req) {
        LiveStream s = liveStreams.get(email, streamId);
        StreamSchedule sc = owned(streamId, scheduleId);
        apply(sc, req, s.getTimeZone());
        return view(schedules.save(sc));
    }

    public void delete(String email, Long streamId, Long scheduleId) {
        liveStreams.get(email, streamId);
        schedules.delete(owned(streamId, scheduleId));
    }

    /** SC-5: one-click retry of a failed start: go live now, ending when the occurrence would have ended. */
    public StreamJob retry(String email, Long streamId, Long scheduleId) {
        liveStreams.get(email, streamId);
        StreamSchedule sc = owned(streamId, scheduleId);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        StreamJob job = liveStreams.goLive(email, streamId, null, playlistIds(sc));
        currentWindow(sc, now).map(Window::end).filter(Objects::nonNull).filter(end -> end.isAfter(now))
                .ifPresent(end -> streamService.setStopAt(job.getId(), email, end));
        schedules.recordError(sc.getId(), null);
        return job;
    }

    private StreamSchedule owned(Long streamId, Long scheduleId) {
        return schedules.findById(scheduleId).filter(x -> x.getStreamId().equals(streamId))
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found."));
    }

    void apply(StreamSchedule sc, ScheduleRequest req, String defaultZone) {
        StreamSchedule.Kind kind;
        try {
            kind = StreamSchedule.Kind.valueOf(req.kind() == null ? "" : req.kind().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Schedule type must be ONCE, DAILY or WEEKLY.");
        }
        ZoneId zone = LiveStreamService.zone(req.timeZone() != null ? req.timeZone() : defaultZone);
        sc.setKind(kind);
        sc.setTimeZone(zone.getId());
        sc.setEnabled(req.enabled() == null || req.enabled());
        sc.setLastError(null);
        if (kind == StreamSchedule.Kind.ONCE) {
            ZonedDateTime start = localDateTime(req.startAt(), zone, "start");
            ZonedDateTime end = req.endAt() == null || req.endAt().isBlank() ? null : localDateTime(req.endAt(), zone, "end");
            if (start == null) {
                throw new IllegalArgumentException("Pick a start date and time.");
            }
            if (end != null && !end.isAfter(start)) {
                throw new IllegalArgumentException("The end must be after the start.");
            }
            if (start.isBefore(ZonedDateTime.now().minusMinutes(1)) && (end == null || end.isBefore(ZonedDateTime.now()))) {
                throw new IllegalArgumentException("That time has already passed.");
            }
            sc.setStartAt(start.withZoneSameInstant(ZoneId.of("UTC")));
            sc.setEndAt(end == null ? null : end.withZoneSameInstant(ZoneId.of("UTC")));
            sc.setDaysOfWeek(null);
            sc.setStartTime(null);
            sc.setEndTime(null);
            sc.setLastStartedOn(null);
        } else {
            LocalTime start = localTime(req.startTime(), "start");
            LocalTime end = localTime(req.endTime(), "end");
            if (start == null || end == null) {
                throw new IllegalArgumentException("Daily and weekly schedules need a start and an end time.");
            }
            if (start.equals(end)) {
                throw new IllegalArgumentException("Start and end can't be the same time.");
            }
            sc.setStartTime(start);
            sc.setEndTime(end);
            sc.setStartAt(null);
            sc.setEndAt(null);
            if (kind == StreamSchedule.Kind.WEEKLY) {
                List<String> days = new ArrayList<>();
                for (String d : req.daysOfWeek() == null ? List.<String>of() : req.daysOfWeek()) {
                    String up = d.trim().toUpperCase(Locale.ROOT);
                    up = up.length() > 3 ? up.substring(0, 3) : up;
                    if (!DAYS.contains(up)) {
                        throw new IllegalArgumentException("Unknown day: " + d);
                    }
                    if (!days.contains(up)) {
                        days.add(up);
                    }
                }
                if (days.isEmpty()) {
                    throw new IllegalArgumentException("Pick at least one day.");
                }
                days.sort(Comparator.comparingInt(DAYS::indexOf));
                sc.setDaysOfWeek(String.join(",", days));
            } else {
                sc.setDaysOfWeek(null);
            }
        }
        List<String> skips = new ArrayList<>();
        for (String d : req.skipDates() == null ? List.<String>of() : req.skipDates()) {
            try {
                skips.add(LocalDate.parse(d.trim()).toString());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Skip dates look like 2026-12-25.");
            }
        }
        if (skips.size() > 100) {
            throw new IllegalArgumentException("Up to 100 skipped dates.");
        }
        sc.setSkipDates(skips.isEmpty() ? null : String.join(",", skips));
        // SC-4: the slot's own videos ([] for the stream's playlist); left out, the slot keeps its list.
        if (req.playlist() == null) {
            return;
        }
        List<Long> playlist = req.playlist().stream().filter(Objects::nonNull).toList();
        if (playlist.size() > 500) {
            throw new IllegalArgumentException("A slot's list can hold up to 500 videos.");
        }
        if (!playlist.isEmpty()) {
            String email = streams.findById(sc.getStreamId()).map(x -> x.getUser().getEmail()).orElse("");
            if (liveStreams.slotVideos(email, ids(playlist)).size() != playlist.size()) {
                throw new IllegalArgumentException("A file in this slot's list isn't a video in your library.");
            }
        }
        sc.setPlaylist(playlist.isEmpty() ? null : ids(playlist));
    }

    private static String ids(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    static List<Long> playlistIds(StreamSchedule sc) {
        if (sc.getPlaylist() == null || sc.getPlaylist().isBlank()) {
            return null;
        }
        List<Long> out = new ArrayList<>();
        for (String p : sc.getPlaylist().split(",")) {
            try {
                out.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ occurrences

    /** One occurrence: its local date (for "start once"), start and end (end may be null for one-time schedules). */
    public record Window(LocalDate date, ZonedDateTime start, ZonedDateTime end) {
    }

    /** The occurrence that is running at {@code now}, if any (skipped dates and wrong weekdays excluded). */
    public static Optional<Window> currentWindow(StreamSchedule sc, ZonedDateTime now) {
        if (sc.getKind() == StreamSchedule.Kind.ONCE) {
            if (sc.getStartAt() == null) {
                return Optional.empty();
            }
            ZonedDateTime start = sc.getStartAt();
            ZonedDateTime until = sc.getEndAt() != null ? sc.getEndAt() : start.plus(OPEN_ENDED_START_WINDOW);
            if (!now.isBefore(start) && now.isBefore(until)) {
                LocalDate date = start.withZoneSameInstant(zoneOf(sc)).toLocalDate();
                return Optional.of(new Window(date, start, sc.getEndAt()));
            }
            return Optional.empty();
        }
        ZoneId zone = zoneOf(sc);
        LocalDate today = now.withZoneSameInstant(zone).toLocalDate();
        // Yesterday too: an overnight window (22:00-02:00) that began yesterday is still running.
        for (LocalDate date : List.of(today.minusDays(1), today)) {
            Window w = windowOn(sc, date, zone);
            if (w != null && !now.isBefore(w.start()) && now.isBefore(w.end())) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    /** When the next occurrence starts after {@code now} (for display), or null. */
    public static ZonedDateTime nextStart(StreamSchedule sc, ZonedDateTime now) {
        if (!sc.isEnabled()) {
            return null;
        }
        if (sc.getKind() == StreamSchedule.Kind.ONCE) {
            return sc.getStartAt() != null && sc.getStartAt().isAfter(now) ? sc.getStartAt() : null;
        }
        ZoneId zone = zoneOf(sc);
        LocalDate today = now.withZoneSameInstant(zone).toLocalDate();
        for (int i = 0; i < 400; i++) {
            Window w = windowOn(sc, today.plusDays(i), zone);
            if (w != null && w.start().isAfter(now)) {
                return w.start();
            }
        }
        return null;
    }

    /**
     * SC-3: every occurrence that overlaps [{@code from}, {@code to}), in start order. Recurring
     * schedules look at one day per day in the range (plus the day before, for overnight windows),
     * so the work is bounded by the length of the range. A one-time schedule without an end counts
     * from its start.
     */
    public static List<Window> windowsBetween(StreamSchedule sc, ZonedDateTime from, ZonedDateTime to) {
        if (!to.isAfter(from)) {
            return List.of();
        }
        if (sc.getKind() == StreamSchedule.Kind.ONCE) {
            ZonedDateTime start = sc.getStartAt();
            if (start == null || !start.isBefore(to)) {
                return List.of();
            }
            ZonedDateTime end = sc.getEndAt();
            boolean overlaps = end == null ? !start.isBefore(from) : end.isAfter(from);
            return overlaps ? List.of(new Window(start.withZoneSameInstant(zoneOf(sc)).toLocalDate(), start, end)) : List.of();
        }
        ZoneId zone = zoneOf(sc);
        LocalDate first = from.withZoneSameInstant(zone).toLocalDate().minusDays(1);
        LocalDate last = to.withZoneSameInstant(zone).toLocalDate();
        List<Window> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Window w = windowOn(sc, d, zone);
            if (w != null && w.start().isBefore(to) && w.end().isAfter(from)) {
                out.add(w);
            }
        }
        return out;
    }

    private static Window windowOn(StreamSchedule sc, LocalDate date, ZoneId zone) {
        if (sc.getStartTime() == null || sc.getEndTime() == null) {
            return null;
        }
        if (sc.getKind() == StreamSchedule.Kind.WEEKLY) {
            String day = DAYS.get(date.getDayOfWeek().getValue() - 1);
            if (sc.getDaysOfWeek() == null || !Arrays.asList(sc.getDaysOfWeek().split(",")).contains(day)) {
                return null;
            }
        }
        if (sc.getSkipDates() != null && Arrays.asList(sc.getSkipDates().split(",")).contains(date.toString())) {
            return null;
        }
        ZonedDateTime start = date.atTime(sc.getStartTime()).atZone(zone);
        LocalDate endDate = sc.getEndTime().isAfter(sc.getStartTime()) ? date : date.plusDays(1);
        ZonedDateTime end = endDate.atTime(sc.getEndTime()).atZone(zone);
        return new Window(date, start, end);
    }

    private static ZoneId zoneOf(StreamSchedule sc) {
        try {
            return ZoneId.of(sc.getTimeZone());
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /** Starts every occurrence that has begun and hasn't been started yet. */
    public void runDue(ZonedDateTime now) {
        for (StreamSchedule sc : schedules.findAllByEnabledTrue()) {
            Optional<Window> w = currentWindow(sc, now);
            if (w.isEmpty()) {
                continue;
            }
            startOccurrence(sc, w.get(), now);
        }
    }

    /**
     * Claims the occurrence and starts the stream in one transaction: if this instance dies halfway,
     * nothing is committed and another instance (or the next tick) starts it. A start that fails for a
     * reason (pre-flight) is claimed in a second transaction with the reason recorded, so it isn't
     * retried every tick.
     */
    private void startOccurrence(StreamSchedule sc, Window w, ZonedDateTime now) {
        Optional<LiveStream> stream = streams.findById(sc.getStreamId());
        if (stream.isEmpty()) {
            return;
        }
        String email = stream.get().getUser().getEmail();
        try {
            Boolean started = tx.execute(status -> {
                if (schedules.claimOccurrence(sc.getId(), w.date()) != 1) {
                    return false; // already started (by us or another instance)
                }
                if (!jobs.findAllByStreamIdAndIsLiveTrue(sc.getStreamId()).isEmpty()) {
                    // Already live, started by hand (or by another schedule): leave that run and its end alone.
                    schedules.recordError(sc.getId(), "Was already live at the scheduled start; left running as it was.");
                } else {
                    StreamJob job = liveStreams.goLive(email, sc.getStreamId(), null, playlistIds(sc));
                    if (w.end() != null && w.end().isAfter(now)) {
                        streamService.setStopAt(job.getId(), email, w.end().withZoneSameInstant(ZoneId.of("UTC")));
                    }
                    schedules.recordError(sc.getId(), null);
                    log.info("Schedule {} started stream {} (job {})", sc.getId(), sc.getStreamId(), job.getId());
                }
                if (sc.getKind() == StreamSchedule.Kind.ONCE) {
                    schedules.disable(sc.getId());
                }
                return true;
            });
            if (!Boolean.TRUE.equals(started)) {
                return;
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "Could not start" : e.getMessage();
            log.warn("Schedule {} could not start stream {}: {}", sc.getId(), sc.getStreamId(), reason);
            Boolean claimed = tx.execute(status -> {
                if (schedules.claimOccurrence(sc.getId(), w.date()) == 1) {
                    schedules.recordError(sc.getId(), reason);
                    if (sc.getKind() == StreamSchedule.Kind.ONCE) {
                        schedules.disable(sc.getId());
                    }
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(claimed)) {
                // SC-5: tell the creator, with a link to retry.
                notifications.notify(email, new NotificationService.Alert(NotificationService.Types.SCHEDULE_FAILED,
                        com.afklive.streamer.model.Notification.Level.ERROR, sc.getStreamId(),
                        stream.get().getName() + " didn't start on schedule",
                        reason + " Fix it and press Retry on the stream's schedule.",
                        "/streams/" + sc.getStreamId() + "/schedule", null, null, true));
            }
        }
    }

    // ------------------------------------------------------------------ views

    ScheduleView view(StreamSchedule sc) {
        List<com.afklive.streamer.dto.StreamDtos.SlotItem> slot = List.of();
        if (sc.getPlaylist() != null) {
            String email = streams.findById(sc.getStreamId()).map(x -> x.getUser().getEmail()).orElse("");
            slot = liveStreams.slotVideos(email, sc.getPlaylist()).stream()
                    .map(m -> new com.afklive.streamer.dto.StreamDtos.SlotItem(m.getId(), m.getTitle())).toList();
        }
        return view(sc, slot);
    }

    static ScheduleView view(StreamSchedule sc, List<com.afklive.streamer.dto.StreamDtos.SlotItem> slot) {
        return new ScheduleView(sc.getId(), sc.getKind().name(), sc.getStartAt(), sc.getEndAt(),
                sc.getDaysOfWeek() == null ? List.of() : List.of(sc.getDaysOfWeek().split(",")),
                sc.getStartTime() == null ? null : sc.getStartTime().toString(),
                sc.getEndTime() == null ? null : sc.getEndTime().toString(),
                sc.getTimeZone(), sc.getSkipDates() == null ? List.of() : List.of(sc.getSkipDates().split(",")),
                sc.isEnabled(), nextStart(sc, ZonedDateTime.now(ZoneId.of("UTC"))), sc.getLastError(), slot);
    }

    private static ZonedDateTime localDateTime(String value, ZoneId zone, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim()).atZone(zone);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The " + what + " must look like 2026-10-23T21:30.");
        }
    }

    private static LocalTime localTime(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The " + what + " time must look like 21:30.");
        }
    }
}
