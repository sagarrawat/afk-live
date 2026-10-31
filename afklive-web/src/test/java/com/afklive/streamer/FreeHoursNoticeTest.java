package com.afklive.streamer;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.NotificationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UsageRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.BillingService;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BI-1 / ON-9: a Free account is told in the app once a month at 70% and at 100% of its hours; paid plans never are. */
@SpringBootTest
class FreeHoursNoticeTest extends PostgresIntegrationTest {

    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired UsageRepository usage;
    @Autowired NotificationRepository notifications;
    @Autowired StreamService streamService;

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }

    private User account(String email, PlanType plan) {
        User u = new User(email);
        u.setPlanType(plan);
        return users.save(u);
    }

    /** On air, last metered 30 minutes ago: the next metering tick adds about 1,800 seconds. */
    private StreamJob liveJob(User owner) {
        StreamJob job = new StreamJob(owner, "key", "video.mp4", null, null, true, 999_999,
                "t", "d", "private", now().minusHours(2), "YouTube");
        job.setLastBillingTime(now().minusMinutes(30));
        job.setStatus("LIVE");
        return jobs.save(job);
    }

    private List<Notification> hoursNotices(User u) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(u.getId()).stream()
                .filter(n -> n.getDedupeKey() != null && n.getDedupeKey().startsWith("free-hours")).toList();
    }

    @Test
    void seventyPercentIsToldOnceAMonthFromMetering() {
        User u = account("hours70@example.com", PlanType.FREE);
        String month = BillingService.month(now());
        usage.add(u.getId(), month, 6 * 3600L + 1800L); // 6.5 h; the tick brings it to about 7 h
        StreamJob job = liveJob(u);

        streamService.processPeriodicBilling();
        streamService.processPeriodicBilling();
        streamService.notifyHours(u, now());

        List<Notification> sent = hoursNotices(u);
        assertThat(sent).hasSize(1);
        Notification n = sent.get(0);
        assertThat(n.getDedupeKey()).isEqualTo("free-hours-70:" + month);
        assertThat(n.getTitle()).isEqualTo("3 hours left this month");
        assertThat(n.getBody()).contains("of your 10 free hours").contains("Creator plan");
        assertThat(n.getLink()).isEqualTo("/studio/settings/billing");
        assertThat(n.getLevel()).isEqualTo(Notification.Level.INFO);
        assertThat(jobs.findById(job.getId()).orElseThrow().getDesiredState()).isNotEqualTo("STOP");
        streamService.finalizeJob(job.getId(), "test");
    }

    @Test
    void underSeventyPercentNothingIsSent() {
        User u = account("hours60@example.com", PlanType.FREE);
        usage.add(u.getId(), BillingService.month(now()), 6 * 3600L);

        streamService.notifyHours(u, now());

        assertThat(hoursNotices(u)).isEmpty();
    }

    @Test
    void usedUpIsToldOnceAndOnlyTheHighestStepWhenBothArePassedAtOnce() {
        User u = account("hours100@example.com", PlanType.FREE);
        String month = BillingService.month(now());
        usage.add(u.getId(), month, 10 * 3600L);

        streamService.notifyHours(u, now());
        streamService.notifyHours(u, now());

        List<Notification> sent = hoursNotices(u);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getDedupeKey()).isEqualTo("free-hours:" + month);
        assertThat(sent.get(0).getTitle()).isEqualTo("Free streaming hours used up");
        assertThat(sent.get(0).getLevel()).isEqualTo(Notification.Level.WARNING);
    }

    @Test
    void seventyThenOneHundredGivesTwoNoticesAndANewMonthStartsOver() {
        User u = account("hoursmonths@example.com", PlanType.FREE);
        ZonedDateTime now = now();
        String month = BillingService.month(now);
        usage.add(u.getId(), month, 7 * 3600L);
        streamService.notifyHours(u, now);
        usage.add(u.getId(), month, 3 * 3600L);
        streamService.notifyHours(u, now);
        assertThat(hoursNotices(u)).extracting(Notification::getDedupeKey)
                .containsExactlyInAnyOrder("free-hours-70:" + month, "free-hours:" + month);

        ZonedDateTime next = now.withDayOfMonth(1).plusMonths(1).plusHours(1);
        String nextMonth = BillingService.month(next);
        usage.add(u.getId(), nextMonth, 7 * 3600L + 600L);
        streamService.notifyHours(u, next);
        streamService.notifyHours(u, next);
        assertThat(hoursNotices(u)).extracting(Notification::getDedupeKey).contains("free-hours-70:" + nextMonth).hasSize(3);
    }

    @Test
    void paidPlansWithoutAnHourLimitAreNeverTold() {
        User u = account("hourspaid@example.com", PlanType.ESSENTIALS);
        usage.add(u.getId(), BillingService.month(now()), 300 * 3600L);
        StreamJob job = liveJob(u);

        streamService.processPeriodicBilling();
        streamService.notifyHours(u, now());

        assertThat(hoursNotices(u)).isEmpty();
        streamService.finalizeJob(job.getId(), "test");
    }
}
