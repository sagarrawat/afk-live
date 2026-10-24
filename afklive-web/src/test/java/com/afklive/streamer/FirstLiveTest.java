package com.afklive.streamer;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamEvent;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.Subscription;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.NotificationRepository;
import com.afklive.streamer.repository.StreamEventRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.FirstLiveService;
import com.afklive.streamer.service.ReferralService;
import com.afklive.streamer.service.SiteFunnelService;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ON-8: "first live confirmed" recorded once per account; GR-18: the invite credit it triggers. */
@SpringBootTest
class FirstLiveTest extends PostgresIntegrationTest {

    @Autowired FirstLiveService firstLive;
    @Autowired ReferralService referrals;
    @Autowired SiteFunnelService funnel;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamEventRepository events;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired NotificationRepository notifications;
    @Autowired StreamService streamService;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> liveJobs = new ArrayList<>();

    @AfterEach
    void endRuns() {
        for (Long id : liveJobs) {
            streamService.finalizeJob(id, "test");
        }
        liveJobs.clear();
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MILLIS);
    }

    private User newUser() {
        User u = new User("first-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        u.setPlanType(PlanType.FREE);
        return users.save(u);
    }

    private StreamJob run(User u, boolean live) {
        StreamJob job = jobs.save(new StreamJob(u, "key", "video.mp4", null, null, live, 0, "t", "d", "private", now(), "YouTube"));
        if (live) {
            liveJobs.add(job.getId());
        }
        return job;
    }

    /** The worker's RE-9 timeline line for a destination that turned ON (RE-8). */
    private void reached(User u, ZonedDateTime at) {
        events.save(new StreamEvent(run(u, false).getId(), at, StreamEvent.Kinds.REACHED, 0, "YouTube", "On YouTube."));
    }

    private ZonedDateTime firstLiveAt(Long userId) {
        java.sql.Timestamp t = jdbc.queryForObject("SELECT first_live_at FROM users WHERE id = ?", java.sql.Timestamp.class, userId);
        return t == null ? null : t.toInstant().atZone(ZoneId.of("UTC"));
    }

    private int bonusDays(Long userId) {
        return users.bonusDays(userId);
    }

    private List<Notification> credits(Long userId) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> ReferralService.NOTICE_TYPE.equals(n.getType())).toList();
    }

    private static <T> List<T> together(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theFirstReachedDestinationIsRecordedOnceEvenWhenTwoSweepsRunAtOnce() throws Exception {
        User u = newUser();
        ZonedDateTime first = now().minusMinutes(5);
        reached(u, first);
        reached(u, first.plusMinutes(2)); // a second destination, later

        List<List<Long>> results = together(2, firstLive::recordFirstLives);

        long claims = results.stream().flatMap(List::stream).filter(u.getId()::equals).count();
        assertThat(claims).as("exactly one sweep records the account").isEqualTo(1);
        assertThat(firstLiveAt(u.getId())).isEqualTo(first);
        // Later runs and sweeps never move it.
        reached(u, now());
        assertThat(firstLive.recordFirstLives()).doesNotContain(u.getId());
        assertThat(firstLiveAt(u.getId())).isEqualTo(first);
    }

    @Test
    void aDestinationOnInALiveRunCountsEvenWithoutItsTimelineLine() {
        User on = newUser();
        StreamJob job = run(on, true);
        jdbc.update("INSERT INTO stream_job_destinations (job_id, position, name, reach) VALUES (?, 0, 'YouTube', 'ON')", job.getId());
        User trying = newUser();
        StreamJob other = run(trying, true);
        jdbc.update("INSERT INTO stream_job_destinations (job_id, position, name, reach) VALUES (?, 0, 'YouTube', 'NOT_REACHING')", other.getId());
        events.save(new StreamEvent(other.getId(), now(), StreamEvent.Kinds.NOT_REACHING, 0, "YouTube", "Not reaching YouTube."));

        List<Long> recorded = firstLive.recordFirstLives();

        assertThat(recorded).contains(on.getId()).doesNotContain(trying.getId());
        assertThat(firstLiveAt(on.getId())).isNotNull();
        assertThat(firstLiveAt(trying.getId())).as("pressing Go live isn't a confirmed live").isNull();
    }

    @Test
    void anInvitedCreatorsFirstLiveCreditsBothSidesOnceWithAnAuditRowAndANotice() throws Exception {
        User inviter = newUser(); // on Free: the month is saved for their next paid period
        User joined = newUser();  // on a paid plan: the month extends it
        ZonedDateTime end = now().plusDays(10);
        Subscription s = new Subscription();
        s.setUserId(joined.getId());
        s.setPlanType(PlanType.ESSENTIALS);
        s.setCycle(Subscription.Cycle.MONTHLY);
        s.setStatus(Subscription.Status.ACTIVE);
        s.setCurrentPeriodStart(end.minusMonths(1));
        s.setCurrentPeriodEnd(end);
        subscriptions.save(s);
        assertThat(referrals.recordSignup(joined.getEmail(), referrals.code(inviter.getId()))).isTrue();

        firstLive.sweep();
        assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE referred_id = ?", String.class, joined.getId()))
                .as("nothing is paid before the first live").isEqualTo(ReferralService.JOINED);

        reached(joined, now());
        firstLive.recordFirstLives();
        List<Integer> credited = together(2, referrals::creditConfirmed);

        assertThat(credited.stream().mapToInt(Integer::intValue).sum()).as("credited exactly once").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE referred_id = ?", String.class, joined.getId())).isEqualTo(ReferralService.CREDITED);
        assertThat(jdbc.queryForObject("SELECT confirmed_live_at IS NOT NULL AND credited_at IS NOT NULL FROM referrals WHERE referred_id = ?",
                Boolean.class, joined.getId())).isTrue();
        assertThat(bonusDays(inviter.getId())).isEqualTo(ReferralService.FREE_MONTH_DAYS);
        assertThat(subscriptions.findByUserId(joined.getId()).orElseThrow().getCurrentPeriodEnd())
                .isEqualTo(end.plusDays(ReferralService.FREE_MONTH_DAYS));
        assertThat(users.findById(joined.getId()).orElseThrow().getPlanType()).isEqualTo(PlanType.ESSENTIALS);
        Long referralId = jdbc.queryForObject("SELECT id FROM referrals WHERE referred_id = ?", Long.class, joined.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_actions WHERE action = 'REFERRAL_CREDIT' AND target_id = ?",
                Integer.class, String.valueOf(referralId))).isEqualTo(1);

        assertThat(credits(inviter.getId())).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("You earned a free month");
            assertThat(n.getBody()).contains("saved on your account").doesNotContain(joined.getEmail());
        });
        assertThat(credits(joined.getId())).singleElement().satisfies(n ->
                assertThat(n.getBody()).contains("now runs until").doesNotContain(inviter.getEmail()));

        // Later sweeps change nothing.
        reached(joined, now());
        firstLive.sweep();
        assertThat(referrals.creditConfirmed()).isZero();
        assertThat(bonusDays(inviter.getId())).isEqualTo(ReferralService.FREE_MONTH_DAYS);
        assertThat(credits(inviter.getId())).hasSize(1);
        assertThat(credits(joined.getId())).hasSize(1);

        ReferralService.Summary summary = referrals.summary(inviter.getId());
        assertThat(summary.credited()).isEqualTo(1);
        assertThat(summary.waitingForFirstLive()).isZero();
        assertThat(summary.payoutsLive()).isTrue();
        assertThat(summary.freeDaysSaved()).isEqualTo(ReferralService.FREE_MONTH_DAYS);
    }

    @Test
    void aSelfReferralIsNeverRecordedSoNeverPaid() {
        User u = newUser();
        assertThat(referrals.recordSignup(u.getEmail(), referrals.code(u.getId()))).isFalse();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO referrals (referrer_id, referred_id) VALUES (?, ?)", u.getId(), u.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        reached(u, now());
        firstLive.sweep();

        assertThat(firstLiveAt(u.getId())).isNotNull();
        assertThat(bonusDays(u.getId())).isZero();
        assertThat(credits(u.getId())).isEmpty();
    }

    @Test
    void aDisabledAccountWaitsForItsCredit() {
        User inviter = newUser();
        User joined = newUser();
        referrals.recordSignup(joined.getEmail(), referrals.code(inviter.getId()));
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", inviter.getId());
        reached(joined, now());
        firstLive.sweep();
        assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE referred_id = ?", String.class, joined.getId())).isEqualTo(ReferralService.JOINED);
        assertThat(bonusDays(joined.getId())).isZero();
    }

    @Test
    void theFunnelsFirstLiveStepCountsEachAccountOnceOnItsFirstDay() {
        firstLive.sweep(); // other tests' accounts first, so only these count below
        long before = funnel.daily(1).getFirst().firstReached();
        User a = newUser();
        User b = newUser();
        User yesterday = newUser();
        reached(a, now());
        reached(a, now()); // a second run the same day: still one account
        reached(b, now());
        reached(yesterday, now().minusDays(1));
        reached(yesterday, now()); // already live yesterday: not a first live today
        firstLive.sweep();

        List<SiteFunnelService.Day> days = funnel.daily(2);
        assertThat(days.getFirst().day()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(days.getFirst().firstReached() - before).isEqualTo(2);
        assertThat(days.get(1).firstReached()).isGreaterThanOrEqualTo(1);
    }
}
