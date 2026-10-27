package com.afklive.streamer.service;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FO-3 and FO-5: cost per stream-hour and the monthly traffic budget, from rows the app already writes:
 * bytes each destination sent (written with the worker's heartbeat), run times ({@code stream_jobs}), the
 * plans, and each server's CPU ({@code worker_samples}). The arithmetic is {@link CostModel}.
 * <p>
 * Settings (all optional, see docs/operations.md): {@code APP_MONTHLY_SERVER_COST} and
 * {@code APP_SERVER_COST_CURRENCY} (existing), {@code APP_INCLUDED_TRANSFER_GB} (default 5,000: one
 * DigitalOcean 4 vCPU / 8 GiB droplet) and {@code APP_TRANSFER_PRICE_PER_GB} (default DigitalOcean's $0.01,
 * ₹0.85 when costs are in rupees).
 * <p>
 * FO-5 runs on web instances every 10 minutes: at 70% and 90% of the included transfer each ADMIN gets one
 * alert per threshold per month. The {@code transfer_alerts (month, threshold)} key is the claim, so with two
 * web instances only the one whose insert lands sends it.
 */
@Service
@Slf4j
public class CostMeter {

    public static final int[] THRESHOLDS = {70, 90};
    static final Duration CHECK_EVERY = Duration.ofMinutes(10);
    static final Duration CACHE = Duration.ofMinutes(5);
    public static final String TRANSFER_BUDGET = "TRANSFER_BUDGET";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final PlanService plans;
    private final NotificationService notifications;
    private final UserRepository users;
    private final CostModel.Inputs inputs;
    private final String role;
    private final boolean auto;
    private volatile Use cached;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "transfer-budget");
        t.setDaemon(true);
        return t;
    });

    public CostMeter(NamedParameterJdbcTemplate jdbc, PlanService plans, NotificationService notifications, UserRepository users,
                     @Value("${app.admin.monthly-server-cost:}") String monthlyServerCost,
                     @Value("${app.admin.server-cost-currency:INR}") String currency,
                     @Value("${app.admin.included-transfer-gb:}") String includedGb,
                     @Value("${app.admin.transfer-price-per-gb:}") String pricePerGb,
                     @Value("${app.streaming.role:all}") String role,
                     @Value("${app.admin.transfer-alerts-auto:true}") boolean auto) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.notifications = notifications;
        this.users = users;
        this.role = role;
        this.auto = auto;
        this.inputs = inputs(monthlyServerCost, currency, includedGb, pricePerGb);
    }

    /** Reads the settings; an empty or unreadable value falls back to the documented default. */
    static CostModel.Inputs inputs(String monthlyServerCost, String currency, String includedGb, String pricePerGb) {
        String cur = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
        Double included = number("APP_INCLUDED_TRANSFER_GB", includedGb, true);
        Double price = number("APP_TRANSFER_PRICE_PER_GB", pricePerGb, true);
        return new CostModel.Inputs(AdminService.parseCost(monthlyServerCost), cur,
                included == null ? CostModel.DEFAULT_INCLUDED_GB : included, included == null,
                price == null ? CostModel.defaultPrice(cur) : price, price == null);
    }

    private static Double number(String name, String raw, boolean zeroAllowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (Double.isFinite(v) && (v > 0 || (zeroAllowed && v == 0))) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        log.warn("{} isn't a number of zero or more; using the default", name);
        return null;
    }

    public CostModel.Inputs inputs() {
        return inputs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("worker".equalsIgnoreCase(role) || !auto) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                checkTransferBudget(ZonedDateTime.now(ZoneOffset.UTC));
            } catch (Exception e) {
                log.warn("Transfer budget check failed: {}", e.getMessage());
            }
        }, 2, CHECK_EVERY.toMinutes(), TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ FO-3: the report

    /** Average CPU per stream over the last 7 days, % of one server's CPU (measured, all servers together). */
    public record Cpu(Double percentPerStream, Double averageStreams, long samples) {
    }

    /** One account or server's traffic this month. */
    public record TrafficRow(String key, Long userId, String plan, double hours, double gb) {
    }

    public record Report(CostModel.Report cost, Cpu cpu, List<TrafficRow> topAccounts, List<TrafficRow> byServer) {
    }

    public Report report(ZonedDateTime now) {
        Month m = Month.of(now);
        List<Run> runs = runs(m);
        Map<String, Long> accounts = new HashMap<>();
        jdbc.query("SELECT COALESCE(plan_type, 'FREE') AS plan, count(*) AS n FROM users WHERE COALESCE(enabled, TRUE) GROUP BY 1",
                new MapSqlParameterSource(), rs -> {
                    accounts.put(rs.getString("plan"), rs.getLong("n"));
                });
        List<CostModel.Plan> priced = new ArrayList<>();
        for (PlanConfig p : plans.getAllPlans()) {
            boolean sold = "INR".equals(inputs.currency()) || "USD".equals(inputs.currency());
            priced.add(new CostModel.Plan(p.getPlanType().name(), p.getDisplayName(),
                    sold ? p.price(inputs.currency(), false) : null, accounts.getOrDefault(p.getPlanType().name(), 0L), p.getMonthlyHours()));
        }
        List<CostModel.Slice> slices = runs.stream().map(Run::slice).toList();
        CostModel.Report cost = CostModel.compute(inputs, m.label(), m.fraction(), slices, priced);
        cached = new Use(m.label(), cost.totals().gb(), inputs.includedGb(), now);
        return new Report(cost, cpu(now), top(runs, true), top(runs, false));
    }

    private Cpu cpu(ZonedDateTime now) {
        return jdbc.query("SELECT avg(cpu_percent / NULLIF(streams, 0)) AS per, avg(streams) AS streams, count(*) AS n "
                        + "FROM worker_samples WHERE at >= :since AND streams > 0 AND cpu_percent IS NOT NULL",
                new MapSqlParameterSource("since", now.minusDays(7).toOffsetDateTime()), rs -> {
                    rs.next();
                    return new Cpu(num(rs, "per"), num(rs, "streams"), rs.getLong("n"));
                });
    }

    private static List<TrafficRow> top(List<Run> runs, boolean byAccount) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, Run> first = new HashMap<>();
        for (Run r : runs) {
            String key = byAccount ? r.email() : r.worker() == null ? "(not recorded)" : r.worker();
            double[] v = sums.computeIfAbsent(key, k -> new double[2]);
            v[0] += r.slice().seconds() / 3600;
            v[1] += r.slice().bytes() / CostModel.GIB;
            first.putIfAbsent(key, r);
        }
        List<TrafficRow> out = new ArrayList<>();
        sums.forEach((k, v) -> out.add(new TrafficRow(k, byAccount ? first.get(k).userId() : null,
                byAccount ? first.get(k).slice().plan() : null, v[0], v[1])));
        out.sort(Comparator.comparingDouble(TrafficRow::gb).reversed().thenComparing(TrafficRow::key));
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    /** The month containing {@code now}, from its first instant to {@code now}. */
    record Month(String label, ZonedDateTime from, ZonedDateTime to, double fraction) {
        static Month of(ZonedDateTime now) {
            ZonedDateTime t = now.withZoneSameInstant(ZoneOffset.UTC);
            ZonedDateTime from = t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            ZonedDateTime end = from.plusMonths(1);
            double fraction = Duration.between(from, t).getSeconds() / (double) Duration.between(from, end).getSeconds();
            return new Month(BillingService.month(t), from, t, fraction);
        }
    }

    /** A run's part of the month, with who ran it and the server that last ran it. */
    public record Run(long jobId, Long userId, String email, String worker, CostModel.Slice slice) {
    }

    /**
     * Every run that overlapped the month. A run's bytes are its total so far (all destinations, every
     * reconnect), spread evenly over its time, so a run that started last month brings only this month's share.
     * A run without an end time ends at its last heartbeat (or now while live).
     */
    public List<Run> runsThisMonth(ZonedDateTime now) {
        return runs(Month.of(now));
    }

    List<Run> runs(Month m) {
        List<Run> out = new ArrayList<>();
        jdbc.query("SELECT j.id, j.user_id, u.email, COALESCE(u.plan_type, 'FREE') AS plan, j.worker_id, j.spec, "
                        + "s.quality_height, s.fps AS stream_fps, j.start_time, "
                        + "COALESCE(j.end_time, CASE WHEN j.is_live THEN CAST(:to AS timestamptz) END, j.heartbeat_at, j.start_time) AS ended, "
                        + "COALESCE((SELECT SUM(d.bytes_sent) FROM stream_job_destinations d WHERE d.job_id = j.id), 0) AS bytes "
                        + "FROM stream_jobs j JOIN users u ON u.id = j.user_id LEFT JOIN streams s ON s.id = j.stream_id "
                        + "WHERE j.start_time IS NOT NULL AND j.start_time < :to "
                        + "AND COALESCE(j.end_time, CASE WHEN j.is_live THEN CAST(:to AS timestamptz) END, j.heartbeat_at, j.start_time) > :from",
                new MapSqlParameterSource().addValue("from", m.from().toOffsetDateTime()).addValue("to", m.to().toOffsetDateTime()), rs -> {
                    OffsetDateTime start = rs.getObject("start_time", OffsetDateTime.class);
                    OffsetDateTime end = rs.getObject("ended", OffsetDateTime.class);
                    if (end.isAfter(m.to().toOffsetDateTime())) {
                        end = m.to().toOffsetDateTime();
                    }
                    double total = Math.max(0, Duration.between(start, end).toMillis() / 1000.0);
                    OffsetDateTime inFrom = start.isBefore(m.from().toOffsetDateTime()) ? m.from().toOffsetDateTime() : start;
                    double inside = Math.max(0, Duration.between(inFrom, end).toMillis() / 1000.0);
                    long bytes = rs.getLong("bytes");
                    double share = total > 0 ? inside / total : 1;
                    int[] q = quality(plainSpec(rs.getString("spec")), (Integer) rs.getObject("quality_height"), (Integer) rs.getObject("stream_fps"));
                    out.add(new Run(rs.getLong("id"), rs.getLong("user_id"), rs.getString("email"), rs.getString("worker_id"),
                            new CostModel.Slice(rs.getString("plan"), q[0], q[1], inside, bytes * share)));
                });
        return out;
    }

    /**
     * FO-5 fair use: GB (GiB) each account sent in the UTC month containing {@code now}, counted exactly as
     * {@link #runs}: a run's bytes are the sum of its destinations' {@code bytes_sent}, which only ever grows and
     * carries on from the last recorded value through handovers and takeovers (the new owner starts from it, and the
     * update never lowers it), so a moved run is counted once; a run spanning two months counts in each in
     * proportion to its time. {@code userId} null: every account with a run this month.
     */
    public Map<Long, Double> gbByAccount(ZonedDateTime now, Long userId) {
        Month m = Month.of(now);
        Map<Long, Double> out = new HashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", m.from().toOffsetDateTime())
                .addValue("to", m.to().toOffsetDateTime()).addValue("uid", userId);
        jdbc.query("SELECT j.user_id, j.start_time, "
                        + "COALESCE(j.end_time, CASE WHEN j.is_live THEN CAST(:to AS timestamptz) END, j.heartbeat_at, j.start_time) AS ended, "
                        + "COALESCE((SELECT SUM(d.bytes_sent) FROM stream_job_destinations d WHERE d.job_id = j.id), 0) AS bytes "
                        + "FROM stream_jobs j WHERE j.start_time IS NOT NULL AND j.start_time < :to "
                        + (userId == null ? "" : "AND j.user_id = :uid ")
                        + "AND COALESCE(j.end_time, CASE WHEN j.is_live THEN CAST(:to AS timestamptz) END, j.heartbeat_at, j.start_time) > :from",
                p, rs -> {
                    double bytes = rs.getLong("bytes") * share(rs.getObject("start_time", OffsetDateTime.class),
                            rs.getObject("ended", OffsetDateTime.class), m);
                    out.merge(rs.getLong("user_id"), bytes / CostModel.GIB, Double::sum);
                });
        return out;
    }

    /** The part of a run from {@code start} to {@code end} that falls inside the month (1 for a run of no length). */
    static double share(OffsetDateTime start, OffsetDateTime end, Month m) {
        OffsetDateTime to = m.to().toOffsetDateTime();
        OffsetDateTime from = m.from().toOffsetDateTime();
        OffsetDateTime e = end.isAfter(to) ? to : end;
        double total = Math.max(0, Duration.between(start, e).toMillis() / 1000.0);
        OffsetDateTime inFrom = start.isBefore(from) ? from : start;
        double inside = Math.max(0, Duration.between(inFrom, e).toMillis() / 1000.0);
        return total > 0 ? inside / total : 1;
    }

    /** The spec as written by the app: it's encrypted at rest (it holds stream keys); null when unreadable. */
    private static String plainSpec(String stored) {
        try {
            return com.afklive.streamer.util.SecretBox.decrypt(stored);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The run's height and frame rate from its spec, else the stream's settings, else 720p 30. */
    static int[] quality(String spec, Integer streamHeight, Integer streamFps) {
        int h = streamHeight == null ? 720 : streamHeight;
        int f = streamFps == null ? 30 : streamFps;
        if (spec != null && !spec.isBlank()) {
            try {
                JsonNode n = JSON.readTree(spec);
                if (n.path("maxHeight").asInt(0) > 0) {
                    h = n.path("maxHeight").asInt();
                }
                JsonNode studio = n.path("studio");
                if (studio.path("fps").asInt(0) > 0) {
                    f = studio.path("fps").asInt();
                }
            } catch (Exception ignored) {
                // an unreadable spec: the stream's settings
            }
        }
        return new int[] {h, f};
    }

    // ------------------------------------------------------------------ FO-5: the traffic budget

    /** GB sent this month against the included transfer. {@code percent} is null when none is included. */
    public record Use(String month, double gb, double includedGb, ZonedDateTime at) {
        public Double percent() {
            return includedGb > 0 ? gb / includedGb * 100 : null;
        }
    }

    /** This month's traffic, worked out at most every 5 minutes per instance (the overview asks often). */
    public Use use(ZonedDateTime now) {
        Use c = cached;
        if (c != null && c.month().equals(BillingService.month(now)) && Duration.between(c.at(), now).compareTo(CACHE) < 0) {
            return c;
        }
        Month m = Month.of(now);
        double bytes = runs(m).stream().mapToDouble(r -> r.slice().bytes()).sum();
        Use u = new Use(m.label(), bytes / CostModel.GIB, inputs.includedGb(), now);
        cached = u;
        return u;
    }

    /** Works out this month's traffic and alerts admins when it passed a threshold for the first time. */
    public Integer checkTransferBudget(ZonedDateTime now) {
        cached = null;
        Use u = use(now);
        return alertIfCrossed(u.month(), u.gb(), u.includedGb());
    }

    /**
     * Claims every threshold {@code gb} has passed this month and alerts admins about the highest one this
     * call claimed (passing 70% and 90% between two checks sends one alert, the 90% one). Returns it, or null
     * when there was nothing new. Nothing is included (0 GB): no budget, no alerts.
     */
    public Integer alertIfCrossed(String month, double gb, double includedGb) {
        if (includedGb <= 0) {
            return null;
        }
        double pct = gb / includedGb * 100;
        Integer claimed = null;
        for (int t : THRESHOLDS) {
            if (pct >= t && claim(month, t, gb, includedGb)) {
                claimed = t;
            }
        }
        if (claimed == null) {
            return null;
        }
        String title = String.format(Locale.ROOT, "Traffic is at %.0f%% of this month's included transfer", Math.floor(pct));
        String body = String.format(Locale.ROOT, "%s GB of %s GB sent in %s. Beyond the included transfer, every GB is billed%s. "
                        + "See the cost per stream-hour in admin, Billing.", gbText(gb), gbText(includedGb), month,
                inputs.pricePerGb() == null ? "" : String.format(Locale.ROOT, " (%s %s per GB)", trim(inputs.pricePerGb()), inputs.currency()));
        Notification.Level level = claimed >= 90 ? Notification.Level.ERROR : Notification.Level.WARNING;
        for (User admin : users.findAllByRole(com.afklive.streamer.util.AppConstants.ROLE_ADMIN)) {
            if (!admin.isEnabled()) {
                continue;
            }
            try {
                notifications.notify(admin, new NotificationService.Alert(TRANSFER_BUDGET, level, null, title, body,
                        "/studio/admin/billing", "transfer:" + month + ":" + claimed, Duration.ofDays(40), true));
            } catch (Exception e) {
                log.warn("Transfer alert for admin {} failed: {}", admin.getId(), e.getMessage());
            }
        }
        log.info("Transfer budget: {} GB of {} GB in {} ({}% threshold)", gbText(gb), gbText(includedGb), month, claimed);
        return claimed;
    }

    /** True for the one caller (of any instance) whose insert recorded this month's threshold. */
    boolean claim(String month, int threshold, double gb, double includedGb) {
        try {
            return jdbc.update("INSERT INTO transfer_alerts(month, threshold, gb_sent, included_gb) VALUES (:month, :t, :gb, :inc) "
                            + "ON CONFLICT (month, threshold) DO NOTHING",
                    new MapSqlParameterSource().addValue("month", month).addValue("t", threshold).addValue("gb", gb)
                            .addValue("inc", includedGb)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private static String gbText(double gb) {
        return String.format(Locale.ENGLISH, gb >= 100 ? "%,.0f" : "%,.1f", gb);
    }

    private static String trim(double v) {
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static Double num(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
