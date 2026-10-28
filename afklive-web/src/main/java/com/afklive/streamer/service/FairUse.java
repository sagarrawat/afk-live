package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FO-5 fair use: each plan's monthly streaming data allowance ({@code plan_configs.monthly_traffic_gb}, null = none),
 * what each account has used of it this month, admin grants, and the per-plan worst case for admin Billing.
 * <p>
 * <b>The month is the UTC calendar month</b>, like streaming hours ({@code usage_months}), the cost panel and the
 * admin traffic budget: one boundary for every account (05:30 on the 1st in India), so usage, alerts and grants
 * agree with each other and with the server bill. Usage is {@link CostMeter#gbByAccount}: the bytes destinations
 * actually sent, from the counters workers write with the heartbeat.
 * <p>
 * What happens at each step is {@link FairUseModel}; the sweep that alerts and applies it is {@link FairUseSweep};
 * go-live and destination changes ask {@link #status} ({@link LiveStreamService}).
 */
@Service
@Slf4j
public class FairUse {

    static final Duration CACHE = Duration.ofSeconds(60);
    public static final int MAX_GRANT_GB = 100_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final PlanService plans;
    private final CostMeter meter;
    private final VideoBitrates bitrates;
    private final double gatewayFeePercent;
    private final Double serverPerStreamMonth;
    /** Per instance, a convenience for pages that ask often (/api/me); decisions use {@link #status} fresh. */
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public FairUse(NamedParameterJdbcTemplate jdbc, PlanService plans, CostMeter meter, VideoBitrates bitrates,
                   @Value("${app.admin.gateway-fee-percent:}") String gatewayFeePercent,
                   @Value("${app.admin.server-cost-per-stream-month:}") String serverPerStreamMonth) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.meter = meter;
        this.bitrates = bitrates;
        this.gatewayFeePercent = parse("APP_GATEWAY_FEE_PERCENT", gatewayFeePercent, 2.0, 100);
        this.serverPerStreamMonth = parse("APP_SERVER_COST_PER_STREAM_MONTH", serverPerStreamMonth, null, Double.MAX_VALUE);
    }

    private static Double parse(String name, String raw, Double fallback, double max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (Double.isFinite(v) && v >= 0 && v <= max) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // the warning below
        }
        log.warn("{} isn't a number from 0 to {}; using the default", name, max);
        return fallback;
    }

    // ------------------------------------------------------------------ an account's month

    /**
     * An account's streaming data this month. {@code planGb}: the plan's allowance (null: none, never limited);
     * {@code grantedGb}: extra granted by admins this month; {@code allowanceGb}: the two together (null without a
     * plan allowance); {@code percent} null without one. {@code resetsAt}: the next month's start (UTC).
     */
    public record Status(String month, double usedGb, Integer planGb, int grantedGb, Double allowanceGb, Double percent,
                         FairUseModel.Step step, ZonedDateTime resetsAt) {
        public boolean extrasPaused() {
            return step == FairUseModel.Step.EXTRAS_PAUSED || step == FairUseModel.Step.REDUCED;
        }

        public boolean reduced() {
            return step == FairUseModel.Step.REDUCED;
        }
    }

    /** Worked out now (go-live, destination changes, the sweep). */
    public Status status(User u, ZonedDateTime now) {
        String month = BillingService.month(now);
        double used = meter.gbByAccount(now, u.getId()).getOrDefault(u.getId(), 0.0);
        Status s = statusOf(plans.limitsFor(u), month, used, granted(u.getId(), month), now);
        cache.put(u.getId(), new Cached(s, now));
        return s;
    }

    /** At most a minute old (pages that ask often); a new month or plan change is never served from the cache. */
    public Status cachedStatus(User u, ZonedDateTime now) {
        Cached c = cache.get(u.getId());
        Integer planGb = plans.limitsFor(u).getMonthlyTrafficGb();
        if (c != null && c.status().month().equals(BillingService.month(now)) && java.util.Objects.equals(c.status().planGb(), planGb)
                && c.at().isAfter(now.minus(CACHE))) {
            return c.status();
        }
        return status(u, now);
    }

    private record Cached(Status status, ZonedDateTime at) {
    }

    public static Status statusOf(PlanConfig plan, String month, double usedGb, int grantedGb, ZonedDateTime now) {
        Integer planGb = plan.getMonthlyTrafficGb();
        Double allowance = planGb == null ? null : (double) planGb + Math.max(0, grantedGb);
        Double pct = allowance == null ? null : FairUseModel.percent(usedGb, allowance);
        if (pct != null && Double.isInfinite(pct)) {
            pct = 100.0;
        }
        ZonedDateTime t = now.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime resets = t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1);
        return new Status(month, usedGb, planGb, grantedGb, allowance, pct, FairUseModel.step(usedGb, allowance), resets);
    }

    /** Extra GB admins granted the account for {@code month}. */
    public int granted(long userId, String month) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(extra_gb), 0) FROM traffic_grants WHERE user_id = :u AND month = :m",
                new MapSqlParameterSource().addValue("u", userId).addValue("m", month), Integer.class);
        return n == null ? 0 : n;
    }

    /** Every account's grants for {@code month}. */
    Map<Long, Integer> grants(String month) {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("SELECT user_id, SUM(extra_gb) AS gb FROM traffic_grants WHERE month = :m GROUP BY user_id",
                new MapSqlParameterSource("m", month), rs -> {
                    out.put(rs.getLong("user_id"), rs.getInt("gb"));
                });
        return out;
    }

    /** Records a grant for the current month (the caller audits it). */
    public void grant(String admin, long userId, int gb, String reason, ZonedDateTime now) {
        if (gb < 1 || gb > MAX_GRANT_GB) {
            throw new IllegalArgumentException("Grant 1 to " + String.format(Locale.ENGLISH, "%,d", MAX_GRANT_GB) + " GB.");
        }
        jdbc.update("INSERT INTO traffic_grants(user_id, month, extra_gb, admin_email, reason) VALUES (:u, :m, :gb, :a, :r)",
                new MapSqlParameterSource().addValue("u", userId).addValue("m", BillingService.month(now)).addValue("gb", gb)
                        .addValue("a", admin).addValue("r", reason));
        cache.remove(userId);
    }

    public void forget(long userId) {
        cache.remove(userId);
    }

    // ------------------------------------------------------------------ admin Billing: worst case per plan

    public record WorstCase(String month, FairUseModel.Inputs inputs, double maxCostShare, List<FairUseModel.PlanCase> plans,
                            Map<String, Integer> suggestedAllowanceGb) {
    }

    /**
     * Each plan's cost at full use (worst case, fair use applied) and for the average account that streamed this
     * month (typical case), against its net price. Estimates: bitrates from the plan's FO-4 settings plus audio and
     * overhead; traffic at the price beyond the included transfer (the worst case assumes it's used up); server cost
     * per stream-hour from {@code APP_SERVER_COST_PER_STREAM_MONTH}, else this month's measured share
     * (FO-3), else unknown.
     */
    public WorstCase worstCase(ZonedDateTime now) {
        CostMeter.Report report = meter.report(now);
        CostModel.Inputs ci = meter.inputs();
        Double serverPerHour;
        String source;
        if (serverPerStreamMonth != null) {
            serverPerHour = serverPerStreamMonth / FairUseModel.FULL_MONTH_HOURS;
            source = "APP_SERVER_COST_PER_STREAM_MONTH";
        } else if (report.cost().totals().serverPerHour() != null) {
            serverPerHour = report.cost().totals().serverPerHour();
            source = "measured";
        } else {
            serverPerHour = null;
            source = ci.monthlyServerCost() == null ? "unset" : "no-hours";
        }
        FairUseModel.Inputs in = new FairUseModel.Inputs(ci.currency(), ci.pricePerGb(), serverPerHour, source, gatewayFeePercent);

        // Typical: accounts that streamed this month, by their plan now, projected to month end.
        double fraction = Math.max(1.0 / (31 * 24), report.cost().monthElapsed());
        Map<String, double[]> byPlan = new HashMap<>();
        Map<Long, double[]> byAccount = new HashMap<>();
        Map<Long, String> planOf = new HashMap<>();
        for (CostMeter.Run r : meter.runsThisMonth(now)) {
            double[] v = byAccount.computeIfAbsent(r.userId(), k -> new double[2]);
            v[0] += r.slice().seconds() / 3600;
            v[1] += r.slice().bytes() / CostModel.GIB;
            planOf.put(r.userId(), r.slice().plan());
        }
        byAccount.forEach((id, v) -> {
            double[] p = byPlan.computeIfAbsent(planOf.get(id), k -> new double[3]);
            p[0] += 1;
            p[1] += v[0] / fraction;
            p[2] += v[1] / fraction;
        });

        boolean sold = "INR".equals(ci.currency()) || "USD".equals(ci.currency());
        List<FairUseModel.PlanCase> out = new ArrayList<>();
        Map<String, Integer> suggested = new java.util.LinkedHashMap<>();
        for (PlanType t : PlanType.values()) {
            PlanConfig c = plans.getPlanConfig(t);
            FairUseModel.PlanLimits limits = limits(c, sold ? c.price(ci.currency(), false) : null);
            double[] p = byPlan.get(t.name());
            FairUseModel.Typical typical = p == null ? null : new FairUseModel.Typical((long) p[0], p[1] / p[0], p[2] / p[0]);
            out.add(FairUseModel.planCase(limits, in, typical));
            Integer s = FairUseModel.allowanceFor(limits, in);
            if (s != null) {
                suggested.put(t.name(), s);
            }
        }
        return new WorstCase(report.cost().month(), in, FairUseModel.MAX_COST_SHARE, out, suggested);
    }

    FairUseModel.PlanLimits limits(PlanConfig c, Long priceMinor) {
        int kbps = bitrates.videoKbps(c.getPlanType(), c.getMaxResolution(), c.getMaxFps());
        return new FairUseModel.PlanLimits(c.getPlanType().name(), c.getDisplayName(), c.getMaxActiveStreams(), c.getMaxDestinations(),
                c.getMaxResolution(), c.getMaxFps(), kbps, c.getMonthlyHours(), c.getMonthlyTrafficGb(), priceMinor);
    }
}
