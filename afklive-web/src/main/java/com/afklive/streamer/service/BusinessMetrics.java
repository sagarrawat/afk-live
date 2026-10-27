package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin > Business: how the business is doing, from data that already exists (users, stream runs,
 * subscriptions, payments, plan prices). Read-only, a fixed number of queries per report (no per-row
 * lookups). Weeks start Monday 00:00 UTC and months are calendar months in UTC, like the Growth funnel.
 * <ul>
 *   <li>Accounts and sign-ups: {@code users.created_at} (accounts made before that column existed have no
 *       date: counted in the total, in no week). Admin accounts are left out everywhere.</li>
 *   <li>Active creators: accounts with a stream run on air for any part of the week. Stream-hours count
 *       each run's time inside the week, the same way the Costs panel counts them.</li>
 *   <li>First go-live: {@code users.first_live_at} (a platform received the stream).</li>
 *   <li>Paying: a subscription that is ACTIVE (paid through its period end) or PAST_DUE (a few days'
 *       grace) on an account with at least one completed payment. Plans an admin gave without a payment
 *       are shown apart and left out of MRR.</li>
 *   <li>MRR: for each ACTIVE paying subscription, the plan's current list price for its cycle, a yearly
 *       price divided by 12, without the 18 % GST that INR prices include (USD sales are zero-rated, so
 *       USD is shown separately with nothing taken off). It's an estimate: discounts, upgrade credit and
 *       old prices are not in it.</li>
 *   <li>Churn: subscriptions that moved to Free in the month (cancelled at period end, not renewed after
 *       the grace days, or ended by an admin). The subscription row is reused when the account pays again,
 *       so someone who left and came back in the same month is not counted.</li>
 * </ul>
 */
@Service
public class BusinessMetrics {

    static final int WEEKS = 12;
    static final int COHORTS = 8;
    /** INR prices include 18 % GST (BI-6). */
    static final double GST = 0.18;

    private static final String PAID = "EXISTS (SELECT 1 FROM payment_audits p WHERE p.user_id = s.user_id AND p.status IN ('COMPLETED', 'SUCCESS'))";

    private final NamedParameterJdbcTemplate jdbc;
    private final PlanService plans;

    public BusinessMetrics(NamedParameterJdbcTemplate jdbc, PlanService plans) {
        this.jdbc = jdbc;
        this.plans = plans;
    }

    /** One week. {@code partial} is the current week (not over yet). */
    public record Week(LocalDate start, boolean partial, long signUps, long signUpsWentLive, long activeCreators, double streamHours) {
    }

    /** Accounts made in one week, and the % of them on air in each week after (0 = the sign-up week; null = not reached yet). */
    public record Cohort(LocalDate start, long accounts, List<Double> activePercent) {
    }

    public record Accounts(long total, long undated, long wentLive, Double wentLivePercent) {
    }

    /** {@code everPaid}: accounts with a completed payment. {@code payingNow}: of those, on a paid plan now. */
    public record Conversion(long everPaid, Double everPaidPercent, long payingNow, Double payingNowPercent, long inGrace,
                             long endingAtPeriodEnd, long givenByAdmin) {
    }

    /** Paying accounts on one plan, cycle and currency; {@code monthlyEach} is the net monthly price of one. */
    public record PlanRow(String plan, String name, String cycle, String currency, long accounts, long inGrace, double monthlyEach,
                          double mrr) {
    }

    /** Monthly recurring revenue without GST: INR in rupees, USD in dollars (kept apart, no exchange rate). */
    public record Mrr(double inr, double usd, String formula) {
    }

    /** Subscriptions that moved to Free in this month so far and last month; rate = this month / (paying now + this month). */
    public record Churn(String month, long thisMonth, String previousMonth, long lastMonth, Double ratePercent, String formula) {
    }

    public record Report(OffsetDateTime generatedAt, String weekStarts, Accounts accounts, List<Week> weeks, Conversion conversion,
                         List<PlanRow> plans, Mrr mrr, Churn churn, List<Cohort> cohorts) {
    }

    public Report report(ZonedDateTime now) {
        return report(now, null);
    }

    /**
     * {@code emailLike} limits the report to accounts whose email matches (a SQL LIKE pattern); tests use
     * it to see only their own rows in a shared database. Null means every account.
     */
    public Report report(ZonedDateTime now, String emailLike) {
        ZonedDateTime utc = now.withZoneSameInstant(ZoneOffset.UTC);
        LocalDate thisWeek = utc.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstWeek = thisWeek.minusWeeks(WEEKS - 1);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("now", utc.toOffsetDateTime())
                .addValue("from", firstWeek.atStartOfDay().atOffset(ZoneOffset.UTC))
                .addValue("scope", emailLike);

        Accounts accounts = accounts(p);
        List<Week> weeks = weeks(p, firstWeek, thisWeek);
        List<Cohort> cohorts = cohorts(p, thisWeek, utc);
        Money money = money(p);
        Churn churn = churn(p, utc, money.payingNow);
        Conversion conversion = new Conversion(money.everPaid, percent(money.everPaid, accounts.total()), money.payingNow,
                percent(money.payingNow, accounts.total()), money.inGrace, money.endingAtPeriodEnd, money.givenByAdmin);
        return new Report(OffsetDateTime.now(ZoneOffset.UTC), "Monday 00:00 UTC", accounts, weeks, conversion, money.rows,
                new Mrr(round2(money.mrrInr), round2(money.mrrUsd),
                        "Sum over paid plans of: list price for the cycle (yearly ÷ 12), ÷ 1.18 to take out GST for INR. "
                                + "Only plans paid through today; accounts in their grace days and plans given by an admin are left out."),
                churn, cohorts);
    }

    // ------------------------------------------------------------------ the queries

    /** The accounts these numbers are about: not admins, optionally limited by email (tests). */
    private static final String ACCOUNTS = "accts AS (SELECT id, created_at, first_live_at FROM users "
            + "WHERE COALESCE(role, 'ROLE_USER') <> 'ROLE_ADMIN' AND (CAST(:scope AS text) IS NULL OR email LIKE CAST(:scope AS text)))";

    /** Their stream runs that were on air at some point since :from, each clamped to now. */
    private static final String RUNS = "runs AS (SELECT r.user_id, r.s, GREATEST(r.s, LEAST(r.e, CAST(:now AS timestamptz))) AS e FROM ("
            + "SELECT j.user_id, j.start_time AS s, "
            + "COALESCE(j.end_time, CASE WHEN j.is_live THEN CAST(:now AS timestamptz) END, j.heartbeat_at, j.start_time) AS e "
            + "FROM stream_jobs j JOIN accts a ON a.id = j.user_id "
            + "WHERE j.start_time IS NOT NULL AND j.start_time < CAST(:now AS timestamptz)) r "
            + "WHERE r.e > CAST(:from AS timestamptz))";

    private Accounts accounts(MapSqlParameterSource p) {
        return jdbc.query("WITH " + ACCOUNTS + " SELECT count(*) AS total, count(*) FILTER (WHERE created_at IS NULL) AS undated, "
                + "count(*) FILTER (WHERE first_live_at IS NOT NULL) AS live FROM accts", p, rs -> {
                    rs.next();
                    long total = rs.getLong("total");
                    long live = rs.getLong("live");
                    return new Accounts(total, rs.getLong("undated"), live, percent(live, total));
                });
    }

    private List<Week> weeks(MapSqlParameterSource p, LocalDate firstWeek, LocalDate thisWeek) {
        Map<LocalDate, Week> byStart = new HashMap<>();
        jdbc.query("WITH " + ACCOUNTS + ", " + RUNS + ", "
                        + "wk AS (SELECT ws, ws + interval '7 days' AS we FROM generate_series(CAST(:from AS timestamptz), "
                        + "CAST(:now AS timestamptz), interval '7 days') AS ws) "
                        + "SELECT (wk.ws AT TIME ZONE 'UTC')::date AS d, "
                        + "(SELECT count(*) FROM accts a WHERE a.created_at >= wk.ws AND a.created_at < wk.we) AS signups, "
                        + "(SELECT count(*) FROM accts a WHERE a.created_at >= wk.ws AND a.created_at < wk.we AND a.first_live_at IS NOT NULL) AS went_live, "
                        + "(SELECT count(DISTINCT r.user_id) FROM runs r WHERE r.s < wk.we AND r.e > wk.ws) AS active, "
                        + "(SELECT COALESCE(SUM(EXTRACT(EPOCH FROM LEAST(r.e, wk.we) - GREATEST(r.s, wk.ws))), 0) FROM runs r "
                        + "WHERE r.s < wk.we AND r.e > wk.ws) AS secs "
                        + "FROM wk", p,
                rs -> {
                    LocalDate d = rs.getObject("d", LocalDate.class);
                    byStart.put(d, new Week(d, d.equals(thisWeek), rs.getLong("signups"), rs.getLong("went_live"), rs.getLong("active"),
                            round2(rs.getDouble("secs") / 3600)));
                });
        List<Week> out = new ArrayList<>();
        for (LocalDate d = firstWeek; !d.isAfter(thisWeek); d = d.plusWeeks(1)) {
            out.add(byStart.getOrDefault(d, new Week(d, d.equals(thisWeek), 0, 0, 0, 0)));
        }
        return out;
    }

    private List<Cohort> cohorts(MapSqlParameterSource p, LocalDate thisWeek, ZonedDateTime now) {
        LocalDate first = thisWeek.minusWeeks(COHORTS - 1);
        Map<LocalDate, Long> sizes = new HashMap<>();
        Map<LocalDate, Map<Integer, Long>> active = new HashMap<>();
        MapSqlParameterSource q = new MapSqlParameterSource(p.getValues())
                .addValue("first", first.atStartOfDay().atOffset(ZoneOffset.UTC));
        jdbc.query("WITH " + ACCOUNTS + ", " + RUNS + ", "
                        + "cu AS (SELECT a.id, date_trunc('week', a.created_at AT TIME ZONE 'UTC') AS cw FROM accts a "
                        + "WHERE a.created_at >= CAST(:first AS timestamptz) AND a.created_at < CAST(:now AS timestamptz)), "
                        + "grid AS (SELECT cu.id, cu.cw, n, (cu.cw AT TIME ZONE 'UTC') + n * interval '7 days' AS ws FROM cu "
                        + "CROSS JOIN generate_series(0, " + (COHORTS - 1) + ") AS n "
                        + "WHERE (cu.cw AT TIME ZONE 'UTC') + n * interval '7 days' <= CAST(:now AS timestamptz)) "
                        + "SELECT g.cw::date AS d, g.n, count(*) AS accounts, count(*) FILTER (WHERE EXISTS (SELECT 1 FROM runs r "
                        + "WHERE r.user_id = g.id AND r.s < g.ws + interval '7 days' AND r.e > g.ws)) AS active "
                        + "FROM grid g GROUP BY g.cw, g.n", q,
                rs -> {
                    LocalDate d = rs.getObject("d", LocalDate.class);
                    int n = rs.getInt("n");
                    if (n == 0) {
                        sizes.put(d, rs.getLong("accounts"));
                    }
                    active.computeIfAbsent(d, k -> new HashMap<>()).put(n, rs.getLong("active"));
                });
        List<Cohort> out = new ArrayList<>();
        for (LocalDate d = thisWeek; !d.isBefore(first); d = d.minusWeeks(1)) {
            long size = sizes.getOrDefault(d, 0L);
            List<Double> pct = new ArrayList<>();
            for (int n = 0; n < COHORTS; n++) {
                boolean reached = !d.plusWeeks(n).atStartOfDay(ZoneOffset.UTC).isAfter(now);
                Long a = active.getOrDefault(d, Map.of()).get(n);
                pct.add(!reached || size == 0 ? null : percent(a == null ? 0 : a, size));
            }
            out.add(new Cohort(d, size, pct));
        }
        return out;
    }

    private record Money(long everPaid, long payingNow, long inGrace, long endingAtPeriodEnd, long givenByAdmin, List<PlanRow> rows,
                         double mrrInr, double mrrUsd) {
    }

    private Money money(MapSqlParameterSource p) {
        long everPaid = jdbc.query("WITH " + ACCOUNTS + " SELECT count(*) AS n FROM accts a WHERE EXISTS (SELECT 1 FROM payment_audits p "
                + "WHERE p.user_id = a.id AND p.status IN ('COMPLETED', 'SUCCESS'))", p, rs -> {
                    rs.next();
                    return rs.getLong("n");
                });
        record Group(String plan, String cycle, String currency, boolean paid, long active, long grace, long ending) {
        }
        List<Group> groups = jdbc.query("WITH " + ACCOUNTS + " SELECT s.plan_type, s.billing_cycle, COALESCE(s.currency, 'INR') AS currency, "
                        + PAID + " AS paid, "
                        + "count(*) FILTER (WHERE s.status = 'ACTIVE') AS active, count(*) FILTER (WHERE s.status = 'PAST_DUE') AS grace, "
                        + "count(*) FILTER (WHERE s.status = 'ACTIVE' AND s.cancel_at_period_end) AS ending "
                        + "FROM subscriptions s JOIN accts a ON a.id = s.user_id "
                        + "WHERE s.status IN ('ACTIVE', 'PAST_DUE') AND s.plan_type <> 'FREE' GROUP BY 1, 2, 3, 4", p,
                (rs, i) -> new Group(rs.getString("plan_type"), rs.getString("billing_cycle"), rs.getString("currency"), rs.getBoolean("paid"),
                        rs.getLong("active"), rs.getLong("grace"), rs.getLong("ending")));
        Map<PlanType, PlanConfig> configs = new HashMap<>();
        for (PlanConfig c : plans.getAllPlans()) {
            configs.put(c.getPlanType(), c);
        }
        long paying = 0, grace = 0, ending = 0, admin = 0;
        double inr = 0, usd = 0;
        Map<String, PlanRow> rows = new LinkedHashMap<>();
        for (Group g : groups) {
            if (!g.paid()) {
                admin += g.active() + g.grace();
                continue;
            }
            paying += g.active() + g.grace();
            grace += g.grace();
            ending += g.ending();
            PlanType type = planType(g.plan());
            PlanConfig c = type == null ? null : configs.get(type);
            double each = c == null ? 0 : monthlyNet(c, g.cycle(), g.currency());
            double mrr = each * g.active();
            if ("USD".equals(g.currency())) {
                usd += mrr;
            } else {
                inr += mrr;
            }
            String key = g.plan() + "|" + g.cycle() + "|" + g.currency();
            rows.put(key, new PlanRow(g.plan(), c == null ? g.plan() : c.getDisplayName(), g.cycle(), g.currency(), g.active() + g.grace(),
                    g.grace(), round2(each), round2(mrr)));
        }
        List<PlanRow> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing((PlanRow r) -> planOrder(r.plan())).thenComparing(PlanRow::currency).thenComparing(PlanRow::cycle));
        return new Money(everPaid, paying, grace, ending, admin, sorted, inr, usd);
    }

    /** One subscription's monthly value without GST, in rupees or dollars: list price for the cycle, yearly ÷ 12. */
    static double monthlyNet(PlanConfig c, String cycle, String currency) {
        boolean yearly = "YEARLY".equals(cycle);
        boolean usd = "USD".equals(currency);
        long minor = usd ? (yearly ? c.getPriceUsdYear() : c.getPriceUsdMonth()) : (yearly ? c.getPriceInrYear() : c.getPriceInrMonth());
        double perMonth = minor / 100.0 / (yearly ? 12 : 1);
        return usd ? perMonth : perMonth / (1 + GST);
    }

    private Churn churn(MapSqlParameterSource p, ZonedDateTime now, long payingNow) {
        ZonedDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime prevStart = monthStart.minusMonths(1);
        MapSqlParameterSource q = new MapSqlParameterSource(p.getValues())
                .addValue("monthStart", monthStart.toOffsetDateTime())
                .addValue("prevStart", prevStart.toOffsetDateTime());
        long[] n = jdbc.query("WITH " + ACCOUNTS + " SELECT "
                + "count(*) FILTER (WHERE s.updated_at >= CAST(:monthStart AS timestamptz) AND s.updated_at <= CAST(:now AS timestamptz)) AS this_month, "
                + "count(*) FILTER (WHERE s.updated_at >= CAST(:prevStart AS timestamptz) AND s.updated_at < CAST(:monthStart AS timestamptz)) AS last_month "
                + "FROM subscriptions s JOIN accts a ON a.id = s.user_id WHERE s.status = 'CANCELED' AND " + PAID, q, rs -> {
                    rs.next();
                    return new long[] {rs.getLong("this_month"), rs.getLong("last_month")};
                });
        return new Churn(monthStart.toLocalDate().toString().substring(0, 7), n[0], prevStart.toLocalDate().toString().substring(0, 7), n[1],
                percent(n[0], payingNow + n[0]),
                "Moved to Free this month ÷ (paying now + moved to Free this month). Paid accounts only.");
    }

    // ------------------------------------------------------------------ helpers

    private static PlanType planType(String name) {
        try {
            return PlanType.valueOf(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int planOrder(String name) {
        PlanType t = planType(name);
        return t == null ? Integer.MAX_VALUE : t.ordinal();
    }

    static Double percent(long part, long whole) {
        return whole <= 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
