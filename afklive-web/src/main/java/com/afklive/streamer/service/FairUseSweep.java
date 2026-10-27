package com.afklive.streamer.service;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FO-5 fair use, applied: every 5 minutes each web instance works out every account's streaming data this month
 * against its plan's allowance (plus grants), and
 * <ul>
 *   <li>tells the creator once per month at 80%, 100% and 110% (in-app, and push and email as their alert settings
 *   allow). The {@code traffic_notices (user, month, step)} insert is the claim, so with two web instances only
 *   the one whose insert lands sends it; passing several steps between two checks sends only the highest;</li>
 *   <li>past 100%, turns off every destination of the account's live runs but the first, through the same live
 *   command as a creator's own toggle (the first destination's connection is never touched);</li>
 *   <li>back under 100% (a grant, an upgrade, a new month), turns them on again for accounts it paused this month or
 *   last.</li>
 * </ul>
 * The 110% step (new runs and restarts at 720p 30) is applied at go-live ({@link LiveStreamService}): a running
 * stream can't change quality without a reconnect, so it keeps its own. Every step is idempotent, so two instances
 * applying the same one at the same moment change nothing twice.
 */
@Service
@Slf4j
public class FairUseSweep {

    static final Duration CHECK_EVERY = Duration.ofMinutes(5);
    public static final String TYPE = NotificationService.Types.STREAMING_DATA;

    private final NamedParameterJdbcTemplate jdbc;
    private final FairUse fairUse;
    private final CostMeter meter;
    private final PlanService plans;
    private final UserRepository users;
    private final NotificationService notifications;
    private final LiveStreamService streams;
    private final String role;
    private final boolean auto;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fair-use");
        t.setDaemon(true);
        return t;
    });

    public FairUseSweep(NamedParameterJdbcTemplate jdbc, FairUse fairUse, CostMeter meter, PlanService plans, UserRepository users,
                        NotificationService notifications, LiveStreamService streams,
                        @Value("${app.streaming.role:all}") String role, @Value("${app.fair-use.auto:true}") boolean auto) {
        this.jdbc = jdbc;
        this.fairUse = fairUse;
        this.meter = meter;
        this.plans = plans;
        this.users = users;
        this.notifications = notifications;
        this.streams = streams;
        this.role = role;
        this.auto = auto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("worker".equalsIgnoreCase(role) || !auto) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                sweep(ZonedDateTime.now(ZoneOffset.UTC));
            } catch (Exception e) {
                log.warn("Fair use check failed: {}", e.getMessage());
            }
        }, 3, CHECK_EVERY.toMinutes(), TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** What one check did, for tests and the log. */
    public record Result(int accounts, int notices, int paused, int restored) {
    }

    /** Checks every account on a plan with an allowance, and every account paused this month or last. */
    public Result sweep(ZonedDateTime now) {
        String month = BillingService.month(now);
        Set<PlanType> capped = new HashSet<>();
        for (PlanConfig c : plans.getAllPlans()) {
            if (c.getMonthlyTrafficGb() != null) {
                capped.add(c.getPlanType());
            }
        }
        Set<Long> ids = new LinkedHashSet<>(pausedSince(now));
        Map<Long, Double> used = capped.isEmpty() ? Map.of() : meter.gbByAccount(now, null);
        Map<Long, Integer> grants = capped.isEmpty() ? Map.of() : fairUse.grants(month);
        if (!capped.isEmpty()) {
            ids.addAll(used.keySet());
        }
        int notices = 0;
        int paused = 0;
        int restored = 0;
        int accounts = 0;
        for (Long id : ids) {
            User u = users.findById(id).orElse(null);
            if (u == null) {
                continue;
            }
            accounts++;
            FairUse.Status s = FairUse.statusOf(plans.getPlanConfig(u.getPlanType()), month, used.getOrDefault(id, 0.0),
                    grants.getOrDefault(id, 0), now);
            if (!capped.contains(u.getPlanType())) {
                s = fairUse.status(u, now); // paused earlier on a plan that has no allowance now: worked out on its own
            }
            Result r = apply(u, s);
            notices += r.notices();
            paused += r.paused();
            restored += r.restored();
        }
        if (notices + paused + restored > 0) {
            log.info("Fair use: {} accounts checked, {} notices, {} runs paused to one destination, {} restored",
                    accounts, notices, paused, restored);
        }
        return new Result(accounts, notices, paused, restored);
    }

    /** One account now (after an admin grant or a plan change), without waiting for the next check. */
    public Result sweepAccount(User u, ZonedDateTime now) {
        return apply(u, fairUse.status(u, now));
    }

    Result apply(User u, FairUse.Status s) {
        int notices = notify(u, s) ? 1 : 0;
        int paused = 0;
        int restored = 0;
        boolean pausedThisMonth = noticeExists(u.getId(), s.month(), FairUseModel.CAP);
        boolean pausedLastMonth = noticeExists(u.getId(), BillingService.month(s.resetsAt().minusMonths(2)), FairUseModel.CAP);
        for (long[] run : liveRuns(u.getId())) {
            try {
                if (s.extrasPaused()) {
                    paused += streams.pauseExtraDestinations(run[0], run[1] == 0 ? null : run[1]) ? 1 : 0;
                } else if (pausedThisMonth || pausedLastMonth) {
                    restored += streams.restoreDestinations(run[0], run[1] == 0 ? null : run[1]) ? 1 : 0;
                }
            } catch (Exception e) {
                log.warn("Fair use for run {} failed: {}", run[0], e.getMessage());
            }
        }
        fairUse.forget(u.getId());
        return new Result(1, notices, paused, restored);
    }

    /** The account's live runs that aren't stopping: {job id, stream id or 0}. */
    private List<long[]> liveRuns(long userId) {
        List<long[]> out = new ArrayList<>();
        jdbc.query("SELECT id, COALESCE(stream_id, 0) AS stream_id FROM stream_jobs WHERE user_id = :u AND is_live "
                        + "AND COALESCE(desired_state, '') <> 'STOP'",
                new MapSqlParameterSource("u", userId), rs -> {
                    out.add(new long[] {rs.getLong("id"), rs.getLong("stream_id")});
                });
        return out;
    }

    /** Accounts whose extra destinations were paused this month or last (to restore them when they're back under). */
    private List<Long> pausedSince(ZonedDateTime now) {
        String month = BillingService.month(now);
        String last = BillingService.month(now.withZoneSameInstant(ZoneOffset.UTC).withDayOfMonth(1).minusDays(1));
        return jdbc.queryForList("SELECT DISTINCT user_id FROM traffic_notices WHERE step = :s AND month IN (:m, :l)",
                new MapSqlParameterSource().addValue("s", FairUseModel.CAP).addValue("m", month).addValue("l", last), Long.class);
    }

    private boolean noticeExists(long userId, String month, int step) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM traffic_notices WHERE user_id = :u AND month = :m AND step = :s",
                new MapSqlParameterSource().addValue("u", userId).addValue("m", month).addValue("s", step), Integer.class);
        return n != null && n > 0;
    }

    /**
     * Claims every step the account has reached this month and tells the creator about the highest one this call
     * claimed. True when it sent one.
     */
    public boolean notify(User u, FairUse.Status s) {
        Integer claimed = null;
        for (int step : FairUseModel.reached(s.usedGb(), s.allowanceGb())) {
            if (claim(u.getId(), s.month(), step, s.usedGb(), s.allowanceGb())) {
                claimed = step;
            }
        }
        if (claimed == null) {
            return false;
        }
        Message m = message(claimed, s, plans.getPlanConfig(u.getPlanType()).getMaxDestinations() > 1);
        try {
            notifications.notify(u, new NotificationService.Alert(TYPE, claimed >= FairUseModel.CAP ? Notification.Level.WARNING
                    : Notification.Level.INFO, null, m.title(), m.body(), "/studio/settings/billing",
                    "traffic:" + s.month() + ":" + claimed, Duration.ofDays(40), true));
        } catch (Exception e) {
            log.warn("Streaming data alert for user {} failed: {}", u.getId(), e.getMessage());
        }
        return true;
    }

    /** True for the one caller (of any instance) whose insert recorded this step for the month. */
    boolean claim(long userId, String month, int step, double gb, Double allowanceGb) {
        try {
            return jdbc.update("INSERT INTO traffic_notices(user_id, month, step, gb_sent, allowance_gb) VALUES (:u, :m, :s, :gb, :a) "
                            + "ON CONFLICT (user_id, month, step) DO NOTHING",
                    new MapSqlParameterSource().addValue("u", userId).addValue("m", month).addValue("s", step).addValue("gb", gb)
                            .addValue("a", allowanceGb == null ? 0 : allowanceGb)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public record Message(String title, String body) {
    }

    /** The creator's alert for a step, in plain words with what happens next. */
    public static Message message(int step, FairUse.Status s, boolean multiDestination) {
        String used = gb(s.usedGb()) + " of " + gb(s.allowanceGb() == null ? 0 : s.allowanceGb()) + " GB";
        String until = s.resetsAt().format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH));
        String more = " Upgrade your plan for more, or contact support if you need a little extra this month.";
        return switch (step) {
            case FairUseModel.WARN -> new Message("You've used 80% of this month's streaming data",
                    "Your streams have sent " + used + " this month. Nothing changes yet. At 100%, "
                            + (multiDestination ? "each stream keeps going to its first destination only" : "your streams keep going")
                            + " until " + until + "." + more);
            case FairUseModel.CAP -> new Message("Streaming data used up: your streams keep going",
                    "Your streams have sent " + used + " this month. They stay live"
                            + (multiDestination ? ", each to its first destination only; other destinations pause" : "")
                            + " until " + until + ". If it goes past 110%, streams you start after that are sent in 720p."
                            + more);
            default -> new Message("New streams will start in 720p this month",
                    "Your streams have sent " + used + " this month. Streams that are live keep their quality; any stream you "
                            + "start or restart before " + until + " goes out in 720p." + more);
        };
    }

    private static String gb(double v) {
        return String.format(Locale.ENGLISH, v >= 100 ? "%,.0f" : "%,.1f", v);
    }
}
