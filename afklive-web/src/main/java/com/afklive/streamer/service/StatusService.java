package com.afklive.streamer.service;

import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.StatusIncident;
import com.afklive.streamer.model.StatusIncidentUpdate;
import com.afklive.streamer.repository.StatusIncidentRepository;
import com.afklive.streamer.repository.StatusIncidentUpdateRepository;
import com.afklive.streamer.repository.StatusSignalRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UsageRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RE-7: the public status page. Platform-level only: each component is operational, degraded or
 * down (never names or anything about one account), plus the incidents admins post. The only numbers
 * are the homepage's two platform totals ({@link Proof}), left out while they're small.
 * <ul>
 *   <li>Website: answering this request means it's up (and the database answered the checks below).</li>
 *   <li>Stream servers: workers that reported in the last {@link StreamService#HEARTBEAT_TIMEOUT}, and
 *       whether any stream that should run has no worker looking after it.</li>
 *   <li>Phone streaming: there's no health API on the ingest server (it's closed to the outside), so
 *       this says what we actually saw: the server calling our auth hook recently, or a worker receiving
 *       a phone's feed right now. With neither, it says "no recent check" rather than guessing.</li>
 * </ul>
 */
@Service
@Slf4j
public class StatusService {

    public static final String OPERATIONAL = "operational";
    public static final String DEGRADED = "degraded";
    public static final String OUTAGE = "outage";
    /** Nothing measured recently; shown as such and left out of the overall status. */
    public static final String NO_DATA = "no_data";

    public static final String SIGNAL_INGEST = "ingest";
    static final Duration INGEST_FRESH = Duration.ofMinutes(15);
    static final Duration UNCLAIMED_GRACE = Duration.ofMinutes(2);
    static final Duration HISTORY = Duration.ofDays(90);
    static final Duration CACHE_FOR = Duration.ofSeconds(15);

    private final WorkerStatusRepository workers;
    private final StreamJobRepository jobs;
    private final StatusSignalRepository signals;
    private final StatusIncidentRepository incidents;
    private final StatusIncidentUpdateRepository updates;
    private final IngestService ingest;
    private final AdminService admin;
    private final UsageRepository usage;

    private volatile PublicStatus cached;

    public StatusService(WorkerStatusRepository workers, StreamJobRepository jobs, StatusSignalRepository signals,
                         StatusIncidentRepository incidents, StatusIncidentUpdateRepository updates, IngestService ingest,
                         AdminService admin, UsageRepository usage) {
        this.workers = workers;
        this.jobs = jobs;
        this.signals = signals;
        this.incidents = incidents;
        this.updates = updates;
        this.ingest = ingest;
        this.admin = admin;
        this.usage = usage;
    }

    public record Component(String id, String name, String status, String detail) {
    }

    public record Update(String status, String message, ZonedDateTime at) {
    }

    public record Incident(Long id, String title, String status, ZonedDateTime startedAt, ZonedDateTime resolvedAt,
                           List<Update> updates) {
    }

    public record PublicStatus(String overall, ZonedDateTime checkedAt, List<Component> components, List<Incident> incidents,
                               Proof proof) {
    }

    /**
     * The homepage's proof line: streams a platform is receiving right now, and hours streamed on AFK Live
     * so far. Each is null until it's big enough to mean something (and to say nothing about any one
     * account): {@link #MIN_LIVE} streams, {@link #MIN_HOURS} hours, the hours rounded down to hundreds.
     */
    public record Proof(Long liveNow, Long hoursStreamed) {
    }

    public static final long MIN_LIVE = 10;
    public static final long MIN_HOURS = 1000;

    public static Proof proof(long liveNow, long streamSeconds) {
        long hours = streamSeconds / 3600;
        return new Proof(liveNow >= MIN_LIVE ? liveNow : null, hours >= MIN_HOURS ? hours / 100 * 100 : null);
    }

    /** What /api/public/status returns; recomputed at most every {@link #CACHE_FOR} per instance. */
    public PublicStatus current() {
        PublicStatus c = cached;
        if (c != null && c.checkedAt().plus(CACHE_FOR).isAfter(now())) {
            return c;
        }
        c = compute();
        cached = c;
        return c;
    }

    PublicStatus compute() {
        ZonedDateTime now = now();
        List<Component> components = new ArrayList<>();
        boolean dbOk = true;
        Component streams;
        try {
            streams = streamServers(now);
        } catch (RuntimeException e) {
            log.warn("Status check of stream servers failed: {}", e.getMessage());
            dbOk = false;
            streams = new Component("streams", "Stream servers", NO_DATA, "We can't check right now.");
        }
        components.add(new Component("website", "Website and studio", dbOk ? OPERATIONAL : DEGRADED,
                dbOk ? "Sign-in, the studio and billing are working." : "Some pages may not load or save."));
        components.add(streams);
        if (ingest.enabled()) {
            try {
                components.add(phoneIngest(now));
            } catch (RuntimeException e) {
                components.add(new Component("phone", "Phone streaming", NO_DATA, "We can't check right now."));
            }
        }
        List<Incident> list = List.of();
        try {
            list = incidentList().stream().map(IncidentWithAuthor::incident).toList();
        } catch (RuntimeException e) {
            log.warn("Status incidents couldn't be read: {}", e.getMessage());
        }
        String overall = OPERATIONAL;
        for (Component c : components) {
            overall = worst(overall, c.status());
        }
        if (list.stream().anyMatch(i -> i.resolvedAt() == null)) {
            overall = worst(overall, DEGRADED);
        }
        Proof proof = new Proof(null, null);
        try {
            proof = proof(jobs.countReachingSince(now.minus(StreamService.HEARTBEAT_TIMEOUT)), usage.totalSeconds());
        } catch (RuntimeException e) {
            log.warn("Status proof numbers couldn't be read: {}", e.getMessage());
        }
        return new PublicStatus(overall, now, components, list, proof);
    }

    private Component streamServers(ZonedDateTime now) {
        ZonedDateTime fresh = now.minus(StreamService.HEARTBEAT_TIMEOUT);
        long reporting = workers.countByHeartbeatAtAfter(fresh);
        long unattended = jobs.countUnattended(fresh, now.minus(UNCLAIMED_GRACE));
        if (reporting == 0) {
            return new Component("streams", "Stream servers", OUTAGE,
                    "Streams can't start, and running streams may have stopped. We're on it.");
        }
        if (unattended > 0) {
            return new Component("streams", "Stream servers", DEGRADED,
                    "Some streams are waiting to reconnect. They come back on their own.");
        }
        return new Component("streams", "Stream servers", OPERATIONAL, "Streams are starting and staying live.");
    }

    private Component phoneIngest(ZonedDateTime now) {
        ZonedDateTime since = now.minus(INGEST_FRESH);
        boolean hookSeen = signals.findById(SIGNAL_INGEST).map(s -> s.getSeenAt().isAfter(since)).orElse(false);
        boolean feedFlowing = jobs.existsByIsLiveTrueAndInputLiveTrueAndHeartbeatAtAfter(now.minus(StreamService.HEARTBEAT_TIMEOUT));
        if (hookSeen || feedFlowing) {
            return new Component("phone", "Phone streaming", OPERATIONAL, "Phones connected in the last 15 minutes.");
        }
        return new Component("phone", "Phone streaming", NO_DATA,
                "No phone has connected in the last 15 minutes, so there's nothing recent to report.");
    }

    static String worst(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String s) {
        return switch (s) {
            case OUTAGE -> 2;
            case DEGRADED -> 1;
            default -> 0;
        };
    }

    /** The ingest server (MediaMTX) called its auth hook: it's up and reachable. Writes at most every 30 s. */
    public void ingestSeen() {
        try {
            ZonedDateTime now = now();
            signals.seen(SIGNAL_INGEST, now, now.minusSeconds(30));
        } catch (RuntimeException e) {
            log.debug("Couldn't record the ingest signal: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ incidents (admin)

    /** An incident with who posted it: for the admin console only, never the public page. */
    public record IncidentWithAuthor(Incident incident, String createdBy) {
    }

    @Transactional(readOnly = true)
    public List<IncidentWithAuthor> incidentList() {
        List<StatusIncident> found = incidents.findRecent(now().minus(HISTORY));
        Map<Long, List<Update>> byIncident = new LinkedHashMap<>();
        if (!found.isEmpty()) {
            for (StatusIncidentUpdate u : updates.findAllByIncidentIdInOrderByCreatedAtDescIdDesc(found.stream().map(StatusIncident::getId).toList())) {
                byIncident.computeIfAbsent(u.getIncidentId(), k -> new ArrayList<>())
                        .add(new Update(label(u.getStatus()), u.getMessage(), u.getCreatedAt()));
            }
        }
        return found.stream().map(i -> new IncidentWithAuthor(new Incident(i.getId(), i.getTitle(), label(i.getStatus()), i.getCreatedAt(),
                i.getResolvedAt(), byIncident.getOrDefault(i.getId(), List.of())), i.getCreatedBy())).toList();
    }

    @Transactional
    public Incident open(String adminEmail, String title, String status, String message) {
        String t = title == null ? "" : title.trim();
        if (t.length() < 3 || t.length() > 200) {
            throw new IllegalArgumentException("Give the incident a title (3 to 200 characters).");
        }
        StatusIncident.Status st = parse(status);
        String msg = requireMessage(message);
        ZonedDateTime now = now();
        StatusIncident i = new StatusIncident();
        i.setTitle(t);
        i.setStatus(st);
        i.setCreatedBy(adminEmail);
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        i.setResolvedAt(st == StatusIncident.Status.RESOLVED ? now : null);
        i = incidents.save(i);
        addUpdate(i.getId(), st, msg, adminEmail, now);
        admin.record(adminEmail, "STATUS_INCIDENT", "incident", i.getId(), label(st) + ": " + t);
        cached = null;
        return find(i.getId());
    }

    @Transactional
    public Incident update(String adminEmail, Long id, String status, String message) {
        StatusIncident i = incidents.lockById(id).orElseThrow(() -> new NotFoundException("Incident not found."));
        StatusIncident.Status st = parse(status);
        String msg = requireMessage(message);
        ZonedDateTime now = now();
        i.setStatus(st);
        i.setUpdatedAt(now);
        i.setResolvedAt(st == StatusIncident.Status.RESOLVED ? (i.getResolvedAt() == null ? now : i.getResolvedAt()) : null);
        incidents.save(i);
        addUpdate(id, st, msg, adminEmail, now);
        admin.record(adminEmail, "STATUS_UPDATE", "incident", id, label(st) + ": " + msg);
        cached = null;
        return find(id);
    }

    private Incident find(Long id) {
        StatusIncident i = incidents.findById(id).orElseThrow(() -> new NotFoundException("Incident not found."));
        List<Update> list = updates.findAllByIncidentIdInOrderByCreatedAtDescIdDesc(List.of(id)).stream()
                .map(u -> new Update(label(u.getStatus()), u.getMessage(), u.getCreatedAt())).toList();
        return new Incident(i.getId(), i.getTitle(), label(i.getStatus()), i.getCreatedAt(), i.getResolvedAt(), list);
    }

    private void addUpdate(Long incidentId, StatusIncident.Status st, String msg, String adminEmail, ZonedDateTime at) {
        StatusIncidentUpdate u = new StatusIncidentUpdate();
        u.setIncidentId(incidentId);
        u.setStatus(st);
        u.setMessage(msg);
        u.setCreatedBy(adminEmail);
        u.setCreatedAt(at);
        updates.save(u);
    }

    private static StatusIncident.Status parse(String status) {
        try {
            return StatusIncident.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Status must be investigating, identified, monitoring or resolved.");
        }
    }

    private static String requireMessage(String message) {
        String m = message == null ? "" : message.trim();
        if (m.isEmpty() || m.length() > 2000) {
            throw new IllegalArgumentException("Write what's happening (up to 2000 characters).");
        }
        return m;
    }

    private static String label(StatusIncident.Status s) {
        return s.name().toLowerCase(Locale.ROOT);
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
    }
}
