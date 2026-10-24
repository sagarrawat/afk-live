package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The admin console's read side (AD-1, AD-2, AD-3): what needs attention now, the users table with its
 * columns, global search and each worker's CPU and memory history. Read-only; every change still goes
 * through {@link AdminService} with a reason. Queries are a fixed number per request (no per-row lookups).
 */
@Service
public class AdminConsoleService {

    static final int PAGE_SIZE = 25;
    static final int SEARCH_LIMIT = 6;
    private static final Pattern RUN_ID = Pattern.compile("^#?(\\d{1,18})$");

    private final NamedParameterJdbcTemplate jdbc;
    private final AdminService admin;
    private final SupportService support;
    private final SiteFunnelService funnel;

    public AdminConsoleService(NamedParameterJdbcTemplate jdbc, AdminService admin, SupportService support, SiteFunnelService funnel) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.support = support;
        this.funnel = funnel;
    }

    // ------------------------------------------------------------------ overview: what needs attention now

    /**
     * One thing that's wrong now. {@code level} is red or amber; {@code link} is the console page that
     * explains it (a run, a user, support, servers); {@code since} is null when unknown.
     */
    public record Attention(String kind, String level, String title, String detail, ZonedDateTime since, Long jobId,
                            Long userId, String link) {
    }

    public record HealthCounts(int green, int amber, int red) {
    }

    public record Overview(List<Attention> attention, HealthCounts health, int liveStreams, long signUpsToday,
                           long firstGoLivesToday, int workers) {
    }

    public static final String STREAM_DOWN = "STREAM_DOWN";
    public static final String NOT_REACHING = "NOT_REACHING";
    public static final String DEST_NOT_REACHING = "DEST_NOT_REACHING";
    public static final String SLOW = "SLOW";
    public static final String STARTING = "STARTING";
    public static final String PHONE = "PHONE";
    public static final String STREAM_PROBLEM = "STREAM_PROBLEM";
    public static final String WORKER = "WORKER";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String EMAIL_OFF = "EMAIL_OFF";
    public static final String TICKETS = "TICKETS";

    private record OpenIncident(long jobId, String kind, String target, ZonedDateTime since) {
    }

    public Overview overview() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        List<AdminService.LiveRow> live = admin.liveStreams(null, null, null);
        Map<Long, AdminService.LiveRow> byJob = new HashMap<>();
        int green = 0, amber = 0, red = 0;
        for (AdminService.LiveRow r : live) {
            byJob.put(r.jobId(), r);
            switch (r.health() == null ? "" : r.health()) {
                case StreamHealth.RED -> red++;
                case StreamHealth.AMBER -> amber++;
                default -> green++;
            }
        }
        List<Attention> out = new ArrayList<>();
        Set<String> covered = new HashSet<>();

        // Open incidents (NT-1, RE-8 not reaching, RE-9 one destination, RE-10 slow): a minute or more old.
        List<OpenIncident> open = jdbc.query("SELECT job_id, kind, target, started_at FROM stream_incidents WHERE resolved_at IS NULL",
                (rs, i) -> new OpenIncident(rs.getLong("job_id"), rs.getString("kind"), rs.getString("target"), zdt(rs, "started_at")));
        for (OpenIncident i : open) {
            AdminService.LiveRow r = byJob.get(i.jobId());
            if (r == null) {
                continue; // the run ended; its incidents close with it
            }
            String name = r.streamName();
            String link = "/studio/admin/runs/" + r.jobId();
            Attention a = switch (i.kind()) {
                case AlertMonitor.DOWN -> new Attention(STREAM_DOWN, StreamHealth.RED, name + " is down",
                        r.lastError() != null ? r.lastError() : "The encoder is restarting or its server stopped reporting.", i.since(), r.jobId(), r.userId(), link);
                case AlertMonitor.UNREACHED -> new Attention(NOT_REACHING, StreamHealth.RED, name + " isn't reaching anyone",
                        notReachingDetail(r), i.since(), r.jobId(), r.userId(), link);
                case AlertMonitor.DEST -> {
                    StreamDtos.DestinationHealth d = destination(r, i.target());
                    yield new Attention(DEST_NOT_REACHING, StreamHealth.RED, name + " isn't reaching " + (d == null ? "a destination" : destName(d)),
                            d == null ? null : d.message(), i.since(), r.jobId(), r.userId(), link);
                }
                case AlertMonitor.SLOW -> new Attention(SLOW, StreamHealth.AMBER, name + " is slower than real time",
                        r.problem(), i.since(), r.jobId(), r.userId(), link);
                case AlertMonitor.STARTING -> new Attention(STARTING, StreamHealth.AMBER, name + " hasn't started",
                        "Pressed Go live more than 3 minutes ago and isn't on air yet.", i.since(), r.jobId(), r.userId(), link);
                case AlertMonitor.PHONE -> new Attention(PHONE, StreamHealth.AMBER, "Phone signal lost on " + name,
                        "Viewers see the Be right back scene.", i.since(), r.jobId(), r.userId(), link);
                default -> null;
            };
            if (a != null) {
                out.add(a);
                covered.add(r.jobId() + ":" + a.kind() + (DEST_NOT_REACHING.equals(a.kind()) ? ":" + i.target() : ""));
                covered.add(r.jobId() + ":any");
            }
        }

        // Live problems younger than an incident (under a minute, or a destination under 5 minutes).
        for (AdminService.LiveRow r : live) {
            String link = "/studio/admin/runs/" + r.jobId();
            boolean onAir = "LIVE".equals(r.status());
            if (onAir && r.speed() != null && r.speed() < AlertMonitor.SLOW_SPEED && !covered.contains(r.jobId() + ":" + SLOW)) {
                out.add(new Attention(SLOW, StreamHealth.AMBER, r.streamName() + " is slower than real time",
                        String.format(Locale.ROOT, "Running at %.2fx for less than a minute so far.", r.speed()), null, r.jobId(), r.userId(), link));
                covered.add(r.jobId() + ":any");
            }
            if (!covered.contains(r.jobId() + ":" + NOT_REACHING) && r.destinationHealth() != null) {
                List<StreamDtos.DestinationHealth> ds = r.destinationHealth();
                for (int p = 0; p < ds.size(); p++) {
                    StreamDtos.DestinationHealth d = ds.get(p);
                    if (StreamHealth.NOT_REACHING.equals(d.reach()) && !covered.contains(r.jobId() + ":" + DEST_NOT_REACHING + ":" + p)) {
                        out.add(new Attention(DEST_NOT_REACHING, StreamHealth.AMBER, r.streamName() + " isn't reaching " + destName(d),
                                d.message(), null, r.jobId(), r.userId(), link));
                        covered.add(r.jobId() + ":any");
                    }
                }
            }
            if (StreamHealth.RED.equals(r.health()) && !covered.contains(r.jobId() + ":any")) {
                out.add(new Attention(STREAM_PROBLEM, StreamHealth.RED, r.streamName() + " has a problem",
                        r.lastError() != null ? r.lastError() : r.problem(), null, r.jobId(), r.userId(), link));
            }
        }

        // Servers: stopped reporting while running streams, or nearly out of CPU.
        List<AdminService.WorkerRow> workers = admin.workers();
        for (AdminService.WorkerRow w : workers) {
            if (!w.healthy() && w.streams() > 0) {
                out.add(new Attention(WORKER, StreamHealth.RED, w.worker() + " stopped reporting",
                        w.streams() + " stream" + (w.streams() == 1 ? "" : "s") + " on it; they move to another server when its heartbeat times out.",
                        null, null, null, "/studio/admin/servers"));
            } else if (w.cpuPercent() != null && w.cpuPercent() >= 90) {
                out.add(new Attention(WORKER, StreamHealth.AMBER, String.format(Locale.ROOT, "%s is at %.0f%% CPU", w.worker(), w.cpuPercent()),
                        "Streams on it may run slower than real time. Check its last 24 hours before moving any.", null, null, null, "/studio/admin/servers"));
            }
        }

        // Payments that failed in the last day (payment_audits keeps the server's local time).
        jdbc.query("SELECT p.transaction_id, p.amount, p.currency, p.created_at, u.id AS uid, COALESCE(u.email, p.merchant_user_id) AS email "
                        + "FROM payment_audits p LEFT JOIN users u ON u.id = p.user_id "
                        + "WHERE p.status = 'FAILED' AND p.created_at >= :since ORDER BY p.created_at DESC LIMIT 20",
                new MapSqlParameterSource("since", LocalDateTime.now().minusHours(24)), rs -> {
                    long amount = rs.getLong("amount");
                    String cur = rs.getString("currency") == null ? "INR" : rs.getString("currency").toUpperCase(Locale.ROOT);
                    LocalDateTime at = rs.getObject("created_at", LocalDateTime.class);
                    Long uid = rs.getObject("uid") == null ? null : rs.getLong("uid");
                    out.add(new Attention(PAYMENT_FAILED, StreamHealth.AMBER, "Payment failed: " + rs.getString("email"),
                            BillingService.priceLabel(amount, cur) + ", order " + rs.getString("transaction_id"),
                            at == null ? null : at.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC),
                            null, uid, uid == null ? "/studio/admin/billing" : "/studio/admin/users/" + uid));
                });

        // OPS-1: one compact line, not a banner.
        try {
            if (!support.emailStatus().configured()) {
                out.add(new Attention(EMAIL_OFF, StreamHealth.AMBER, "Email is off",
                        "Nothing is sent: verification, resets, alerts. Set ZEPTOMAIL_URL and ZEPTOMAIL_TOKEN.", null, null, null, "/studio/admin/servers#email"));
            }
        } catch (RuntimeException ignored) {
            // email status unknown: say nothing rather than guess
        }
        Long tickets = jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE status = 'OPEN'", new MapSqlParameterSource(), Long.class);
        if (tickets != null && tickets > 0) {
            out.add(new Attention(TICKETS, StreamHealth.AMBER, tickets + " open support ticket" + (tickets == 1 ? "" : "s"),
                    null, null, null, null, "/studio/admin/support"));
        }

        out.sort(Comparator.comparingInt((Attention a) -> StreamHealth.RED.equals(a.level()) ? 0 : 1)
                .thenComparing(Attention::since, Comparator.nullsLast(Comparator.naturalOrder())));
        SiteFunnelService.Day today = funnel.daily(1).get(0);
        return new Overview(out, new HealthCounts(green, amber, red), live.size(), today.signUps(), today.firstGoLives(),
                (int) workers.stream().filter(w -> !"(unassigned)".equals(w.worker())).count());
    }

    private static String notReachingDetail(AdminService.LiveRow r) {
        if (r.destinationHealth() == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (StreamDtos.DestinationHealth d : r.destinationHealth()) {
            if (StreamHealth.NOT_REACHING.equals(d.reach()) && d.message() != null) {
                parts.add(d.message());
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private static StreamDtos.DestinationHealth destination(AdminService.LiveRow r, String target) {
        try {
            int p = Integer.parseInt(target);
            return r.destinationHealth() != null && p >= 0 && p < r.destinationHealth().size() ? r.destinationHealth().get(p) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String destName(StreamDtos.DestinationHealth d) {
        return d.platform() != null && !d.platform().isBlank() ? d.platform() : d.name() != null && !d.name().isBlank() ? d.name() : "a destination";
    }

    // ------------------------------------------------------------------ AD-3: the users table

    public record Money(String currency, long amount) {
    }

    /**
     * One row of the users table. {@code createdAt} is null for accounts made before it was recorded;
     * {@code lastLiveAt} is when they last pressed Go live; {@code paid} sums completed payments per currency
     * (minor units; refunded ones drop out).
     */
    public record UserRow(Long id, String email, String name, String plan, boolean enabled, String role, int liveStreams,
                          long storageBytes, ZonedDateTime createdAt, ZonedDateTime lastLiveAt, double hoursThisMonth,
                          List<Money> paid) {
    }

    /** {@code filter}: blank (everyone), {@code paying} (on a paid plan), {@code live} (live now) or {@code never} (never went live). */
    public Page<UserRow> users(String q, String filter, int page) {
        int p = Math.max(0, page);
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("month", BillingService.month(ZonedDateTime.now(ZoneOffset.UTC)))
                .addValue("limit", PAGE_SIZE).addValue("offset", (long) p * PAGE_SIZE);
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            where.append(" AND (LOWER(u.email) LIKE :like OR LOWER(COALESCE(u.full_name, '')) LIKE :like)");
            args.addValue("like", "%" + escapeLike(needle) + "%");
        }
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        switch (f) {
            case "", "all" -> { }
            case "paying" -> where.append(" AND COALESCE(u.plan_type, 'FREE') <> 'FREE'");
            case "live" -> where.append(" AND EXISTS (SELECT 1 FROM stream_jobs j WHERE j.user_id = u.id AND j.is_live)");
            case "never" -> where.append(" AND NOT EXISTS (SELECT 1 FROM stream_jobs j WHERE j.user_id = u.id AND j.start_time IS NOT NULL)");
            default -> throw new IllegalArgumentException("Filter is paying, live or never.");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM users u" + where, args, Long.class);
        List<UserRow> rows = jdbc.query("SELECT u.id, u.email, u.full_name, COALESCE(u.plan_type, 'FREE') AS plan, COALESCE(u.enabled, TRUE) AS enabled, "
                        + "u.role, COALESCE(u.used_storage_bytes, 0) AS storage, u.created_at, "
                        + "(SELECT count(*) FROM stream_jobs j WHERE j.user_id = u.id AND j.is_live) AS live, "
                        + "(SELECT max(j.start_time) FROM stream_jobs j WHERE j.user_id = u.id) AS last_live, "
                        + "COALESCE((SELECT m.stream_seconds FROM usage_months m WHERE m.user_id = u.id AND m.month = :month), 0) AS secs "
                        + "FROM users u" + where + " ORDER BY u.id DESC LIMIT :limit OFFSET :offset", args,
                (rs, i) -> new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"), rs.getString("plan"),
                        rs.getBoolean("enabled"), rs.getString("role"), rs.getInt("live"), rs.getLong("storage"), zdt(rs, "created_at"),
                        zdt(rs, "last_live"), Math.round(rs.getLong("secs") / 36.0) / 100.0, new ArrayList<>()));
        if (!rows.isEmpty()) {
            Map<Long, UserRow> byId = new LinkedHashMap<>();
            Map<String, Long> byEmail = new HashMap<>();
            for (UserRow r : rows) {
                byId.put(r.id(), r);
                byEmail.put(r.email(), r.id());
            }
            // Payments link to the account by id, or (older rows) only by the email they were made with.
            jdbc.query("SELECT p.user_id, p.merchant_user_id, UPPER(COALESCE(p.currency, 'INR')) AS cur, COALESCE(SUM(p.amount), 0) AS total "
                            + "FROM payment_audits p WHERE p.status IN ('COMPLETED', 'SUCCESS') "
                            + "AND (p.user_id IN (:ids) OR (p.user_id IS NULL AND p.merchant_user_id IN (:emails))) "
                            + "GROUP BY p.user_id, p.merchant_user_id, UPPER(COALESCE(p.currency, 'INR'))",
                    new MapSqlParameterSource().addValue("ids", byId.keySet()).addValue("emails", byEmail.keySet()), rs -> {
                        Long uid = rs.getObject("user_id") == null ? byEmail.get(rs.getString("merchant_user_id")) : rs.getLong("user_id");
                        UserRow r = uid == null ? null : byId.get(uid);
                        if (r != null) {
                            add(r.paid(), rs.getString("cur"), rs.getLong("total"));
                        }
                    });
        }
        return new PageImpl<>(rows, PageRequest.of(p, PAGE_SIZE), total == null ? 0 : total);
    }

    private static void add(List<Money> paid, String currency, long amount) {
        for (int i = 0; i < paid.size(); i++) {
            if (paid.get(i).currency().equals(currency)) {
                paid.set(i, new Money(currency, paid.get(i).amount() + amount));
                return;
            }
        }
        paid.add(new Money(currency, amount));
    }

    // ------------------------------------------------------------------ global search

    public record UserHit(Long id, String email, String name, String plan) {
    }

    /** {@code liveJobId}: the run that's live now; {@code lastJobId}: the newest run (null if never live). */
    public record StreamHit(Long id, String name, Long userId, String userEmail, Long liveJobId, Long lastJobId) {
    }

    public record RunHit(Long jobId, String streamName, Long userId, String userEmail, boolean live, ZonedDateTime startTime) {
    }

    public record SearchResult(List<UserHit> users, List<StreamHit> streams, List<RunHit> runs) {
    }

    /** Users by email or name, streams by name, a run by its id ("1234" or "#1234"). */
    public SearchResult search(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || needle.length() > 200) {
            return new SearchResult(List.of(), List.of(), List.of());
        }
        List<RunHit> runs = new ArrayList<>();
        var m = RUN_ID.matcher(needle);
        if (m.matches()) {
            runs = jdbc.query("SELECT j.id, COALESCE(s.name, j.title) AS name, u.id AS uid, u.email, COALESCE(j.is_live, FALSE) AS live, j.start_time "
                            + "FROM stream_jobs j JOIN users u ON u.id = j.user_id LEFT JOIN streams s ON s.id = j.stream_id WHERE j.id = :id",
                    new MapSqlParameterSource("id", Long.parseLong(m.group(1))),
                    (rs, i) -> new RunHit(rs.getLong("id"), rs.getString("name"), rs.getLong("uid"), rs.getString("email"),
                            rs.getBoolean("live"), zdt(rs, "start_time")));
        }
        if (needle.length() < 2) {
            return new SearchResult(List.of(), List.of(), runs);
        }
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("like", "%" + escapeLike(needle) + "%")
                .addValue("prefix", escapeLike(needle) + "%")
                .addValue("limit", SEARCH_LIMIT);
        List<UserHit> users = jdbc.query("SELECT id, email, full_name, COALESCE(plan_type, 'FREE') AS plan FROM users "
                        + "WHERE LOWER(email) LIKE :like OR LOWER(COALESCE(full_name, '')) LIKE :like "
                        + "ORDER BY (LOWER(email) LIKE :prefix) DESC, id DESC LIMIT :limit", args,
                (rs, i) -> new UserHit(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"), rs.getString("plan")));
        List<StreamHit> streams = jdbc.query("SELECT s.id, s.name, u.id AS uid, u.email, "
                        + "(SELECT j.id FROM stream_jobs j WHERE j.stream_id = s.id AND j.is_live LIMIT 1) AS live_job, "
                        + "(SELECT max(j.id) FROM stream_jobs j WHERE j.stream_id = s.id) AS last_job "
                        + "FROM streams s JOIN users u ON u.id = s.user_id WHERE LOWER(s.name) LIKE :like "
                        + "ORDER BY (SELECT count(*) FROM stream_jobs j WHERE j.stream_id = s.id AND j.is_live) DESC, s.id DESC LIMIT :limit", args,
                (rs, i) -> new StreamHit(rs.getLong("id"), rs.getString("name"), rs.getLong("uid"), rs.getString("email"),
                        rs.getObject("live_job") == null ? null : rs.getLong("live_job"),
                        rs.getObject("last_job") == null ? null : rs.getLong("last_job")));
        return new SearchResult(users, streams, runs);
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ------------------------------------------------------------------ AD-2: worker history

    /** One bucket: average and peak CPU, average memory use (% of total), most streams. */
    public record Point(ZonedDateTime at, Double cpu, Double cpuPeak, Double memory, int streams) {
    }

    public record ServerHistory(String worker, Long memoryTotalBytes, int bucketSeconds, List<Point> points) {
    }

    /** The last {@code hours} (1 to 168) per worker, in 1-minute (≤ 6 h), 5-minute (≤ 24 h) or 30-minute buckets. */
    public List<ServerHistory> history(int hours) {
        int h = Math.max(1, Math.min(168, hours));
        int bucket = h <= 6 ? 60 : h <= 24 ? 300 : 1800;
        ZonedDateTime since = ZonedDateTime.now(ZoneOffset.UTC).minusHours(h);
        Map<String, List<Point>> points = new LinkedHashMap<>();
        Map<String, Long> totals = new HashMap<>();
        jdbc.query("SELECT worker_id, to_timestamp(floor(extract(epoch FROM at) / :bucket) * :bucket) AS b, "
                        + "avg(cpu_percent) AS cpu, max(cpu_percent) AS peak, "
                        + "avg(memory_used_bytes::float8 / NULLIF(memory_total_bytes, 0)) * 100 AS mem, "
                        + "max(streams) AS streams, max(memory_total_bytes) AS total "
                        + "FROM worker_samples WHERE at >= :since GROUP BY 1, 2 ORDER BY 1, 2",
                new MapSqlParameterSource().addValue("bucket", bucket).addValue("since", since.toOffsetDateTime()), rs -> {
                    String w = rs.getString("worker_id");
                    points.computeIfAbsent(w, k -> new ArrayList<>()).add(new Point(zdt(rs, "b"), num(rs, "cpu"), num(rs, "peak"),
                            num(rs, "mem"), rs.getInt("streams")));
                    if (rs.getObject("total") != null) {
                        totals.put(w, rs.getLong("total"));
                    }
                });
        // Workers reporting now but without samples yet still get a (empty) series.
        for (AdminService.WorkerRow w : admin.workers()) {
            if (!"(unassigned)".equals(w.worker())) {
                points.computeIfAbsent(w.worker(), k -> new ArrayList<>());
            }
        }
        List<ServerHistory> out = new ArrayList<>();
        points.forEach((w, ps) -> out.add(new ServerHistory(w, totals.get(w), bucket, ps)));
        out.sort(Comparator.comparing(ServerHistory::worker));
        return out;
    }

    private static Double num(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : Math.round(v * 10.0) / 10.0;
    }

    private static ZonedDateTime zdt(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.atZoneSameInstant(ZoneOffset.UTC);
    }
}
