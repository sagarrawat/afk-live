package com.afklive.streamer.service;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.StreamSchedule;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.StreamScheduleRepository;
import com.afklive.streamer.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SC-3: every scheduled run of a creator's streams over a week or a month, in their time zone, and
 * the stretches where more streams would be on air at once than their plan allows.
 */
@Service
public class ScheduleCalendarService {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final UserRepository users;
    private final LiveStreamRepository streams;
    private final StreamScheduleRepository schedules;
    private final PlanService plans;

    public ScheduleCalendarService(UserRepository users, LiveStreamRepository streams, StreamScheduleRepository schedules,
                                   PlanService plans) {
        this.users = users;
        this.streams = streams;
        this.schedules = schedules;
        this.plans = plans;
    }

    /** One run. start/end are instants (UTC); startLocal/endLocal are wall-clock times in the creator's zone. */
    public record Occurrence(Long streamId, String streamName, Long scheduleId, String kind, String start, String end,
                             String startLocal, String endLocal, String scheduleTimeZone, boolean movable) {
    }

    /** A stretch where {@code streams} streams would be on air at once, over the plan's limit. */
    public record Clash(String start, String end, String startLocal, String endLocal, int streams,
                        List<Long> streamIds, List<String> streamNames, String message) {
    }

    public record StreamRef(Long id, String name) {
    }

    public record Calendar(String from, int days, String timeZone, int limit, String planName, List<StreamRef> streams,
                           List<Occurrence> occurrences, List<Clash> clashes) {
    }

    @Transactional(readOnly = true)
    public Calendar calendar(String email, LocalDate from, int days, ZonedDateTime now) {
        if (days != 7 && days != 35 && days != 42) {
            throw new IllegalArgumentException("Show 7 days (a week) or 35 or 42 days (a month).");
        }
        User user = users.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("Account not found."));
        ZoneId zone = zoneOf(user.getTimeZone());
        LocalDate first = from != null ? from : now.withZoneSameInstant(zone).toLocalDate();
        ZonedDateTime rangeStart = first.atStartOfDay(zone);
        ZonedDateTime rangeEnd = first.plusDays(days).atStartOfDay(zone);
        PlanConfig plan = plans.getPlanConfig(user.getPlanType());
        int limit = Math.max(1, plan.getMaxActiveStreams());

        List<LiveStream> mine = streams.findAllByUserEmailOrderByCreatedAtAsc(email);
        Map<Long, String> names = new LinkedHashMap<>();
        mine.forEach(s -> names.put(s.getId(), s.getName()));
        List<StreamSchedule> all = names.isEmpty() ? List.of() : schedules.findAllByStreamIdIn(names.keySet());

        List<Occurrence> occurrences = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        for (StreamSchedule sc : all) {
            boolean ranOnce = sc.getKind() == StreamSchedule.Kind.ONCE && sc.getLastStartedOn() != null;
            if (!sc.isEnabled() && !ranOnce) {
                continue; // paused
            }
            for (StreamScheduleService.Window w : StreamScheduleService.windowsBetween(sc, rangeStart, rangeEnd)) {
                boolean movable = sc.getKind() == StreamSchedule.Kind.ONCE && sc.isEnabled() && w.start().isAfter(now);
                occurrences.add(new Occurrence(sc.getStreamId(), names.get(sc.getStreamId()), sc.getId(), sc.getKind().name(),
                        w.start().toInstant().toString(), w.end() == null ? null : w.end().toInstant().toString(),
                        local(w.start(), zone), w.end() == null ? null : local(w.end(), zone), sc.getTimeZone(), movable));
                // A run without an end keeps going: for clashes it lasts to the end of the range.
                Instant end = w.end() == null ? rangeEnd.toInstant() : w.end().toInstant();
                spans.add(new Span(sc.getStreamId(), w.start().toInstant(), end));
            }
        }
        occurrences.sort(Comparator.comparing(Occurrence::start).thenComparing(Occurrence::streamId));
        List<Clash> clashes = clashes(spans, limit, plan.getDisplayName(), names, zone);
        return new Calendar(first.toString(), days, zone.getId(), limit, plan.getDisplayName(),
                names.entrySet().stream().map(e -> new StreamRef(e.getKey(), e.getValue())).toList(), occurrences, clashes);
    }

    record Span(Long streamId, Instant start, Instant end) {
    }

    /**
     * Sweeps the runs in time order, counting distinct streams on air (two runs of the same stream
     * don't make it go live twice). Neighbouring stretches over the limit merge into one clash.
     */
    static List<Clash> clashes(List<Span> spans, int limit, String planName, Map<Long, String> names, ZoneId zone) {
        record Event(Instant at, Long streamId, int delta) {
        }
        List<Event> events = new ArrayList<>();
        for (Span s : spans) {
            if (s.end().isAfter(s.start())) {
                events.add(new Event(s.start(), s.streamId(), 1));
                events.add(new Event(s.end(), s.streamId(), -1));
            }
        }
        events.sort(Comparator.comparing(Event::at));
        Map<Long, Integer> onAir = new HashMap<>();
        List<Clash> out = new ArrayList<>();
        Instant clashStart = null;
        Set<Long> involved = new LinkedHashSet<>();
        int peak = 0;
        int i = 0;
        while (i < events.size()) {
            Instant at = events.get(i).at();
            while (i < events.size() && events.get(i).at().equals(at)) {
                Event e = events.get(i++);
                onAir.merge(e.streamId(), e.delta(), Integer::sum);
                if (onAir.get(e.streamId()) <= 0) {
                    onAir.remove(e.streamId());
                }
            }
            if (onAir.size() > limit) {
                if (clashStart == null) {
                    clashStart = at;
                    involved = new LinkedHashSet<>();
                    peak = 0;
                }
                involved.addAll(onAir.keySet());
                peak = Math.max(peak, onAir.size());
            } else if (clashStart != null) {
                out.add(clash(clashStart, at, peak, involved, limit, planName, names, zone));
                clashStart = null;
            }
        }
        return out;
    }

    private static Clash clash(Instant start, Instant end, int peak, Set<Long> ids, int limit, String planName,
                               Map<Long, String> names, ZoneId zone) {
        List<Long> sorted = new ArrayList<>(ids);
        List<String> streamNames = sorted.stream().map(id -> names.getOrDefault(id, "Stream " + id)).toList();
        String message = peak + " streams would be on air at once, but the " + planName + " plan runs "
                + (limit == 1 ? "1 stream" : limit + " streams") + " at a time. Move a run or upgrade your plan.";
        return new Clash(start.toString(), end.toString(), local(start.atZone(zone), zone), local(end.atZone(zone), zone),
                peak, sorted, streamNames, message);
    }

    private static String local(ZonedDateTime t, ZoneId zone) {
        return t.withZoneSameInstant(zone).toLocalDateTime().format(LOCAL);
    }

    private static ZoneId zoneOf(String id) {
        try {
            return id == null || id.isBlank() ? ZoneId.of("UTC") : ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
