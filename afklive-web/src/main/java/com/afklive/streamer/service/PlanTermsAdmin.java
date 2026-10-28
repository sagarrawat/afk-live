package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import com.afklive.streamer.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * BI-10 for admins: a subscriber's recorded plan terms (the limits kept from their purchase) next to what their plan
 * gives today, and the audited changes to them. Limit checks keep using the more generous of the two
 * ({@link PlanTerms#merge}); so raising a recorded limit gives more, and "reset" replaces the record with the plan's
 * current limits, which is the only way to take a grandfathered limit away. Every change needs a reason, and a change
 * that leaves the subscriber with less needs an explicit confirmation; both land in admin_actions with before and after.
 */
@Service
@Slf4j
public class PlanTermsAdmin {

    static final Set<Integer> HEIGHTS = Set.of(720, 1080, 1440, 2160);
    static final Set<Integer> FPS = Set.of(30, 60);
    static final long GB = 1024L * 1024 * 1024;

    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final PlanService plans;
    private final AdminActionRepository audit;
    private final NamedParameterJdbcTemplate jdbc;

    public PlanTermsAdmin(UserRepository users, SubscriptionRepository subscriptions, PlanService plans, AdminActionRepository audit,
                          NamedParameterJdbcTemplate jdbc) {
        this.users = users;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** One set of limits. {@code monthlyHours} 0 = no limit; {@code trafficGb} null = no streaming data allowance. */
    public record Limits(int streams, int destinations, int maxHeight, int maxFps, long storageBytes, int monthlyHours,
                         Integer trafficGb, boolean watermark) {
        static Limits of(PlanConfig c) {
            return new Limits(c.getMaxActiveStreams(), c.getMaxDestinations(), c.getMaxResolution(), c.getMaxFps(), c.getMaxStorageBytes(),
                    c.getMonthlyHours(), c.getMonthlyTrafficGb(), c.isWatermark());
        }

        /** The recorded terms as they are (a field never recorded reads as nothing: 0, or a watermark). */
        static Limits of(PlanTerms t) {
            return new Limits(nz(t.getStreams()), nz(t.getDestinations()), nz(t.getMaxHeight()), nz(t.getMaxFps()),
                    t.getStorageBytes() == null ? 0 : t.getStorageBytes(), t.getMonthlyHours() == null ? 1 : t.getMonthlyHours(),
                    t.getTrafficGb(), t.getWatermark() == null || t.getWatermark());
        }

        private static int nz(Integer v) {
            return v == null ? 0 : v;
        }
    }

    /**
     * {@code applies}: the account has a paid plan with a live subscription, so recorded terms count (otherwise
     * {@code why} says why not). {@code recorded}: the terms on the subscription (null: none yet, the plan's limits
     * apply). {@code effective}: what limit checks use. {@code extras}: where the recorded terms give more than the plan
     * does today. {@code resetReduces}: what "reset to the plan's limits" would take away.
     */
    public record TermsView(boolean applies, String why, String plan, String planName, ZonedDateTime capturedAt, Limits recorded,
                            Limits current, Limits effective, List<String> extras, List<String> resetReduces) {
    }

    // ------------------------------------------------------------------ reading

    public TermsView view(Long userId) {
        User u = user(userId);
        PlanConfig current = plans.getPlanConfig(u.getPlanType());
        Optional<Subscription> sub = live(u);
        String why = why(u, sub);
        PlanTerms t = sub.map(Subscription::getTerms).filter(x -> x.getPlan() == u.getPlanType()).orElse(null);
        Limits recorded = t == null ? null : Limits.of(t);
        PlanConfig effective = why == null && t != null ? t.merge(current) : current;
        List<String> reduces = why == null && t != null ? reductions(Limits.of(effective), Limits.of(current)) : List.of();
        return new TermsView(why == null, why, u.getPlanType().name(), current.getDisplayName(), t == null ? null : t.getCapturedAt(),
                recorded, Limits.of(current), Limits.of(effective), t == null ? List.of() : extras(t, current), reduces);
    }

    // ------------------------------------------------------------------ changes

    /** Recorded limits to raise; null leaves a limit as it is. {@code watermark} can only be turned off. */
    public record Raise(Integer streams, Integer destinations, Integer maxHeight, Integer maxFps, Integer storageGb, Integer monthlyHours,
                        Boolean watermark) {
    }

    /**
     * Raises recorded limits (e.g. grants 4K or extra destinations). Each value must be more than the subscriber has
     * now; to lower anything, reset. The grant stays through renewals of the same plan and is carried into an upgrade.
     */
    @Transactional
    public TermsView raise(String admin, Long userId, Raise in, String reason) {
        String why = requireReason(reason);
        if (in == null) {
            throw new IllegalArgumentException("Say which limits to raise.");
        }
        Ctx c = lock(userId);
        PlanTerms t = c.terms();
        Limits before = Limits.of(t.merge(c.current()));
        List<String> changes = new ArrayList<>();
        if (in.streams() != null) {
            check(in.streams() >= 1 && in.streams() <= 100, "Streams at a time is 1 to 100.");
            higher(in.streams() > before.streams(), "streams at a time", before.streams());
            changes.add("streams " + before.streams() + " → " + in.streams());
            t.setStreams(in.streams());
        }
        if (in.destinations() != null) {
            check(in.destinations() >= 1 && in.destinations() <= 20, "Destinations per stream is 1 to 20.");
            higher(in.destinations() > before.destinations(), "destinations per stream", before.destinations());
            changes.add("destinations " + before.destinations() + " → " + in.destinations());
            t.setDestinations(in.destinations());
        }
        if (in.maxHeight() != null) {
            check(HEIGHTS.contains(in.maxHeight()), "Top quality is 720, 1080, 1440 or 2160 (4K).");
            higher(in.maxHeight() > before.maxHeight(), "top quality", before.maxHeight() + "p");
            changes.add("quality " + before.maxHeight() + "p → " + in.maxHeight() + "p");
            t.setMaxHeight(in.maxHeight());
        }
        if (in.maxFps() != null) {
            check(FPS.contains(in.maxFps()), "Frame rate is 30 or 60.");
            higher(in.maxFps() > before.maxFps(), "frame rate", before.maxFps());
            changes.add("fps " + before.maxFps() + " → " + in.maxFps());
            t.setMaxFps(in.maxFps());
        }
        if (in.storageGb() != null) {
            check(in.storageGb() >= 1 && in.storageGb() <= 10_000, "Storage is 1 to 10,000 GB.");
            long bytes = in.storageGb() * GB;
            higher(bytes > before.storageBytes(), "storage", before.storageBytes() / GB + " GB");
            changes.add("storage " + before.storageBytes() / GB + " GB → " + in.storageGb() + " GB");
            t.setStorageBytes(bytes);
        }
        if (in.monthlyHours() != null) {
            check(in.monthlyHours() >= 0 && in.monthlyHours() <= 744, "Monthly hours is 0 (no limit) to 744.");
            boolean more = before.monthlyHours() != 0 && (in.monthlyHours() == 0 || in.monthlyHours() > before.monthlyHours());
            higher(more, "monthly hours", before.monthlyHours() == 0 ? "no limit" : before.monthlyHours());
            changes.add("hours " + hours(before.monthlyHours()) + " → " + hours(in.monthlyHours()));
            t.setMonthlyHours(in.monthlyHours());
        }
        if (in.watermark() != null) {
            check(!in.watermark(), "A watermark can only be removed here; reset to the plan's limits to put it back.");
            higher(before.watermark(), "no watermark", "already none");
            changes.add("watermark removed");
            t.setWatermark(false);
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("Say which limits to raise.");
        }
        save(c, t);
        record(admin, "TERMS_RAISE", userId, c.user().getEmail() + " (" + c.current().getDisplayName() + "): " + String.join(", ", changes) + ": " + why);
        return view(userId);
    }

    /**
     * Replaces the recorded terms with the plan's limits as sold today. When that takes anything away, {@code confirm}
     * must be true (the error lists what would go); the audit row lists it too.
     */
    @Transactional
    public TermsView reset(String admin, Long userId, String reason, boolean confirm) {
        String why = requireReason(reason);
        Ctx c = lock(userId);
        PlanTerms before = c.terms();
        List<String> lost = reductions(Limits.of(before.merge(c.current())), Limits.of(c.current()));
        if (!lost.isEmpty() && !confirm) {
            throw new ConflictException("This takes away: " + String.join(", ", lost) + ". Confirm to go ahead.");
        }
        save(c, PlanTerms.of(c.current(), now()));
        record(admin, "TERMS_RESET", userId, c.user().getEmail() + " reset to " + c.current().getDisplayName() + "'s current limits"
                + (lost.isEmpty() ? " (nothing taken away)" : "; taken away: " + String.join(", ", lost))
                + "; recorded before: " + describe(before) + ": " + why);
        return view(userId);
    }

    /**
     * Sets this subscriber's monthly streaming data allowance ({@code gb}), or clears it ({@code gb} null: no allowance
     * of their own). Limit checks use the larger of it and the plan's, so this can't go below the plan; it can still
     * take away "no limit", which needs {@code confirm}.
     */
    @Transactional
    public TermsView dataAllowance(String admin, Long userId, Integer gb, String reason, boolean confirm) {
        String why = requireReason(reason);
        check(gb == null || (gb >= 1 && gb <= 1_000_000), "A data allowance is 1 to 1,000,000 GB a month.");
        Ctx c = lock(userId);
        PlanTerms t = c.terms();
        Integer was = t.getTrafficGb();
        if (Objects.equals(was, gb)) {
            throw new IllegalArgumentException("Nothing changed.");
        }
        Limits before = Limits.of(t.merge(c.current()));
        t.setTrafficGb(gb);
        List<String> lost = reductions(before, Limits.of(t.merge(c.current())));
        if (!lost.isEmpty() && !confirm) {
            throw new ConflictException("This takes away: " + String.join(", ", lost) + ". Confirm to go ahead.");
        }
        save(c, t);
        record(admin, "TERMS_DATA_ALLOWANCE", userId, c.user().getEmail() + ": recorded data allowance " + gbText(was) + " → " + gbText(gb)
                + "; in effect " + gbText(before.trafficGb()) + " → " + gbText(Limits.of(t.merge(c.current())).trafficGb())
                + (lost.isEmpty() ? "" : " (taken away: " + String.join(", ", lost) + ")") + ": " + why);
        return view(userId);
    }

    // ------------------------------------------------------------------ who is grandfathered (item: admin list)

    public record GrandfatheredRow(Long userId, String email, String plan, String cycle, String status, ZonedDateTime capturedAt,
                                   List<String> extras) {
    }

    public record Grandfathered(List<GrandfatheredRow> rows, Map<String, Long> countByPlan, long total, boolean truncated) {
    }

    /** Where recorded terms give more than the plan's row today, in SQL (null on the terms side: see {@link PlanTerms}). */
    static final String MORE = "(s.terms_streams > c.max_active_streams OR s.terms_destinations > c.max_destinations "
            + "OR s.terms_max_height > c.max_resolution OR s.terms_max_fps > c.max_fps OR s.terms_storage_bytes > c.max_storage_bytes "
            + "OR (c.monthly_hours <> 0 AND (s.terms_monthly_hours = 0 OR s.terms_monthly_hours > c.monthly_hours)) "
            + "OR (c.monthly_traffic_gb IS NOT NULL AND (s.terms_traffic_gb IS NULL OR s.terms_traffic_gb > c.monthly_traffic_gb)) "
            + "OR (c.watermark AND s.terms_watermark = false))";

    static final String FROM = "FROM subscriptions s JOIN users u ON u.id = s.user_id JOIN plan_configs c ON c.plan_type = s.plan_type "
            + "WHERE s.status <> 'CANCELED' AND s.plan_type <> 'FREE' AND s.terms_plan = s.plan_type AND u.plan_type = s.plan_type "
            + "AND (CAST(:scope AS text) IS NULL OR u.email LIKE CAST(:scope AS text)) AND " + MORE;

    public static final int MAX_ROWS = 500;

    public Grandfathered grandfathered(String plan) {
        return grandfathered(plan, null);
    }

    /** Subscribers whose recorded terms give more than their plan does today; {@code plan} filters (null: all). */
    public Grandfathered grandfathered(String plan, String emailLike) {
        PlanType filter = plan == null || plan.isBlank() ? null : parsePlan(plan);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("scope", emailLike)
                .addValue("plan", filter == null ? null : filter.name());
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT s.plan_type, count(*) AS n " + FROM + " GROUP BY 1", p,
                rs -> {
                    counts.put(rs.getString("plan_type"), rs.getLong("n"));
                });
        Map<PlanType, PlanConfig> configs = new EnumMap<>(PlanType.class);
        for (PlanConfig c : plans.getAllPlans()) {
            configs.put(c.getPlanType(), c);
        }
        List<GrandfatheredRow> rows = jdbc.query("SELECT u.id, u.email, s.plan_type, s.billing_cycle, s.status, s.terms_captured_at, "
                        + "s.terms_streams, s.terms_destinations, s.terms_max_height, s.terms_max_fps, s.terms_storage_bytes, "
                        + "s.terms_monthly_hours, s.terms_traffic_gb, s.terms_watermark " + FROM
                        + " AND (CAST(:plan AS text) IS NULL OR s.plan_type = CAST(:plan AS text)) ORDER BY u.email LIMIT " + (MAX_ROWS + 1), p,
                (rs, i) -> {
                    PlanType type = PlanType.valueOf(rs.getString("plan_type"));
                    PlanTerms t = new PlanTerms();
                    t.setPlan(type);
                    t.setStreams((Integer) rs.getObject("terms_streams"));
                    t.setDestinations((Integer) rs.getObject("terms_destinations"));
                    t.setMaxHeight((Integer) rs.getObject("terms_max_height"));
                    t.setMaxFps((Integer) rs.getObject("terms_max_fps"));
                    t.setStorageBytes((Long) rs.getObject("terms_storage_bytes"));
                    t.setMonthlyHours((Integer) rs.getObject("terms_monthly_hours"));
                    t.setTrafficGb((Integer) rs.getObject("terms_traffic_gb"));
                    t.setWatermark((Boolean) rs.getObject("terms_watermark"));
                    java.time.OffsetDateTime at = rs.getObject("terms_captured_at", java.time.OffsetDateTime.class);
                    return new GrandfatheredRow(rs.getLong("id"), rs.getString("email"), type.name(), rs.getString("billing_cycle"),
                            rs.getString("status"), at == null ? null : at.toZonedDateTime(), extras(t, configs.get(type)));
                });
        boolean truncated = rows.size() > MAX_ROWS;
        long total = filter == null ? counts.values().stream().mapToLong(Long::longValue).sum() : counts.getOrDefault(filter.name(), 0L);
        return new Grandfathered(truncated ? rows.subList(0, MAX_ROWS) : rows, counts, total, truncated);
    }

    // ------------------------------------------------------------------ helpers

    /** Where the recorded terms give more than {@code plan} does today, e.g. "4 destinations (plan: 2)". */
    static List<String> extras(PlanTerms t, PlanConfig plan) {
        List<String> out = new ArrayList<>();
        if (plan == null) {
            return out;
        }
        if (t.getStreams() != null && t.getStreams() > plan.getMaxActiveStreams()) {
            out.add(t.getStreams() + " streams (plan: " + plan.getMaxActiveStreams() + ")");
        }
        if (t.getDestinations() != null && t.getDestinations() > plan.getMaxDestinations()) {
            out.add(t.getDestinations() + " destinations (plan: " + plan.getMaxDestinations() + ")");
        }
        if (t.getMaxHeight() != null && t.getMaxHeight() > plan.getMaxResolution()) {
            out.add(t.getMaxHeight() + "p (plan: " + plan.getMaxResolution() + "p)");
        }
        if (t.getMaxFps() != null && t.getMaxFps() > plan.getMaxFps()) {
            out.add(t.getMaxFps() + " fps (plan: " + plan.getMaxFps() + ")");
        }
        if (t.getStorageBytes() != null && t.getStorageBytes() > plan.getMaxStorageBytes()) {
            out.add(t.getStorageBytes() / GB + " GB storage (plan: " + plan.getMaxStorageBytes() / GB + " GB)");
        }
        if (t.getMonthlyHours() != null && plan.getMonthlyHours() != 0 && (t.getMonthlyHours() == 0 || t.getMonthlyHours() > plan.getMonthlyHours())) {
            out.add(hours(t.getMonthlyHours()) + " (plan: " + hours(plan.getMonthlyHours()) + ")");
        }
        if (plan.getMonthlyTrafficGb() != null && (t.getTrafficGb() == null || t.getTrafficGb() > plan.getMonthlyTrafficGb())) {
            out.add((t.getTrafficGb() == null ? "no streaming data limit" : t.getTrafficGb() + " GB streaming data") + " (plan: " + gbText(plan.getMonthlyTrafficGb()) + ")");
        }
        if (plan.isWatermark() && Boolean.FALSE.equals(t.getWatermark())) {
            out.add("no watermark (plan: watermark)");
        }
        return out;
    }

    /** What {@code after} gives less of than {@code before}. */
    static List<String> reductions(Limits before, Limits after) {
        List<String> out = new ArrayList<>();
        if (after.streams() < before.streams()) {
            out.add("streams " + before.streams() + " → " + after.streams());
        }
        if (after.destinations() < before.destinations()) {
            out.add("destinations " + before.destinations() + " → " + after.destinations());
        }
        if (after.maxHeight() < before.maxHeight()) {
            out.add("quality " + before.maxHeight() + "p → " + after.maxHeight() + "p");
        }
        if (after.maxFps() < before.maxFps()) {
            out.add("fps " + before.maxFps() + " → " + after.maxFps());
        }
        if (after.storageBytes() < before.storageBytes()) {
            out.add("storage " + before.storageBytes() / GB + " GB → " + after.storageBytes() / GB + " GB");
        }
        if (after.monthlyHours() != 0 && (before.monthlyHours() == 0 || after.monthlyHours() < before.monthlyHours())) {
            out.add("hours " + hours(before.monthlyHours()) + " → " + hours(after.monthlyHours()));
        }
        if (after.trafficGb() != null && (before.trafficGb() == null || after.trafficGb() < before.trafficGb())) {
            out.add("streaming data " + gbText(before.trafficGb()) + " → " + gbText(after.trafficGb()));
        }
        if (after.watermark() && !before.watermark()) {
            out.add("watermark comes back");
        }
        return out;
    }

    private record Ctx(User user, Subscription sub, PlanConfig current, PlanTerms terms) {
    }

    /** The account's live subscription, locked; its recorded terms, or the plan's limits when it has none yet. */
    private Ctx lock(Long userId) {
        User u = user(userId);
        Subscription s = subscriptions.lockByUserId(u.getId()).orElse(null);
        String why = why(u, Optional.ofNullable(s).filter(x -> x.getStatus() != Subscription.Status.CANCELED));
        if (why != null) {
            throw new ConflictException(why);
        }
        PlanConfig current = plans.getPlanConfig(u.getPlanType());
        PlanTerms t = s.getTerms() != null && s.getTerms().getPlan() == u.getPlanType() ? copy(s.getTerms()) : PlanTerms.of(current, now());
        return new Ctx(u, s, current, t);
    }

    private void save(Ctx c, PlanTerms t) {
        t.setPlan(c.user().getPlanType());
        if (t.getCapturedAt() == null) {
            t.setCapturedAt(now());
        }
        c.sub().setTerms(t);
        subscriptions.save(c.sub());
    }

    private static PlanTerms copy(PlanTerms x) {
        PlanTerms t = new PlanTerms();
        t.setPlan(x.getPlan());
        t.setStreams(x.getStreams());
        t.setDestinations(x.getDestinations());
        t.setMaxHeight(x.getMaxHeight());
        t.setMaxFps(x.getMaxFps());
        t.setStorageBytes(x.getStorageBytes());
        t.setMonthlyHours(x.getMonthlyHours());
        t.setTrafficGb(x.getTrafficGb());
        t.setWatermark(x.getWatermark());
        t.setCapturedAt(x.getCapturedAt());
        return t;
    }

    private Optional<Subscription> live(User u) {
        return subscriptions.findByUserId(u.getId()).filter(s -> s.getStatus() != Subscription.Status.CANCELED);
    }

    /** Null when recorded terms apply to this account; otherwise why not, in words. */
    private static String why(User u, Optional<Subscription> sub) {
        if (u.getPlanType() == PlanType.FREE) {
            return "Free accounts have no recorded limits: they get the Free plan's limits.";
        }
        if (sub.isEmpty()) {
            return "This account has no subscription record, so its plan's current limits apply. Set its plan (Change plan) to record limits.";
        }
        if (sub.get().getPlanType() != u.getPlanType()) {
            return "The subscription is on another plan than the account. Set the plan first.";
        }
        return null;
    }

    private User user(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User not found."));
    }

    private static PlanType parsePlan(String plan) {
        try {
            return PlanType.valueOf(plan.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown plan: " + plan);
        }
    }

    private static String describe(PlanTerms t) {
        Limits l = Limits.of(t);
        return l.streams() + " streams, " + l.destinations() + " destinations, " + l.maxHeight() + "p" + l.maxFps() + ", "
                + l.storageBytes() / GB + " GB storage, " + hours(l.monthlyHours()) + ", " + gbText(l.trafficGb()) + " data"
                + (l.watermark() ? ", watermark" : "");
    }

    static String hours(int h) {
        return h == 0 ? "no hour limit" : h + " h a month";
    }

    static String gbText(Integer gb) {
        return gb == null ? "no limit" : gb + " GB";
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void higher(boolean ok, String what, Object now) {
        if (!ok) {
            throw new IllegalArgumentException("That isn't more than they have now (" + what + ": " + now + "). To lower a limit, reset to the plan's limits.");
        }
    }

    private void record(String admin, String action, Long userId, String detail) {
        AdminAction a = new AdminAction();
        a.setAdminEmail(admin);
        a.setAction(action);
        a.setTargetType("user");
        a.setTargetId(String.valueOf(userId));
        a.setDetail(detail.length() > 4000 ? detail.substring(0, 4000) : detail);
        a.setCreatedAt(now());
        audit.save(a);
        log.info("Admin {} {} user {}: {}", admin, action, userId, detail);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3) {
            throw new IllegalArgumentException("Say why (it goes in the audit log).");
        }
        String r = reason.trim();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }
}
