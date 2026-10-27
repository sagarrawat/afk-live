package com.afklive.streamer;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.BusinessMetrics;
import com.afklive.streamer.service.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin > Business: the numbers from a seeded set of accounts (limited to this test's emails, since the
 * database is shared), the edge cases (no data, a plan set to end mid-month, yearly plans, grace days,
 * plans given by an admin, runs across a week boundary) and admin-only access.
 */
@SpringBootTest
class BusinessMetricsTest extends PostgresIntegrationTest {

    /** A Wednesday; its week starts Monday 2031-03-17. */
    static final ZonedDateTime NOW = ZonedDateTime.of(2031, 3, 19, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired WebApplicationContext context;
    @Autowired BusinessMetrics metrics;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanService plans;

    MockMvc mvc;
    String tag;
    final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM payment_audits WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private String scope() {
        return "%-" + tag + "@biz.example";
    }

    private static Timestamp at(String iso) {
        return Timestamp.from(ZonedDateTime.parse(iso).toInstant());
    }

    private User account(String name, String createdAt, String role) {
        User u = new User(name + "-" + tag + "@biz.example");
        if (role != null) {
            u.setRole(role);
        }
        u = users.save(u);
        created.add(u.getId());
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", createdAt == null ? null : at(createdAt), u.getId());
        return u;
    }

    private void wentLive(User u, String when) {
        jdbc.update("UPDATE users SET first_live_at = ? WHERE id = ?", at(when), u.getId());
    }

    /** An ended run; with {@code end} null it has no end time and its last heartbeat ends it. */
    private void run(User u, String start, String end, String heartbeat) {
        jdbc.update("INSERT INTO stream_jobs(user_id, start_time, end_time, heartbeat_at, is_live, status, title) VALUES (?, ?, ?, ?, FALSE, 'ENDED', ?)",
                u.getId(), at(start), end == null ? null : at(end), heartbeat == null ? null : at(heartbeat), "biz " + tag);
    }

    private void paid(User u) {
        jdbc.update("INSERT INTO payment_audits(user_id, transaction_id, amount, status, created_at) VALUES (?, ?, 100, 'COMPLETED', ?)",
                u.getId(), "biz-" + tag + "-" + u.getId(), at("2031-01-01T00:00:00Z"));
    }

    private void subscription(User u, PlanType plan, String cycle, String currency, String status, boolean endsAtPeriodEnd, String updatedAt) {
        jdbc.update("INSERT INTO subscriptions(user_id, plan_type, billing_cycle, currency, status, current_period_start, current_period_end, "
                        + "cancel_at_period_end, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                u.getId(), plan.name(), cycle, currency, status, at("2031-03-01T00:00:00Z"), at("2031-04-01T00:00:00Z"), endsAtPeriodEnd,
                at("2031-01-01T00:00:00Z"), at(updatedAt));
    }

    @Test
    void noDataGivesZerosAndNoRates() {
        BusinessMetrics.Report r = metrics.report(NOW, scope());
        assertThat(r.accounts().total()).isZero();
        assertThat(r.accounts().wentLivePercent()).isNull();
        assertThat(r.conversion().everPaidPercent()).isNull();
        assertThat(r.conversion().payingNow()).isZero();
        assertThat(r.mrr().inr()).isZero();
        assertThat(r.mrr().usd()).isZero();
        assertThat(r.plans()).isEmpty();
        assertThat(r.churn().thisMonth()).isZero();
        assertThat(r.churn().ratePercent()).isNull();
        assertThat(r.weeks()).hasSize(12).allSatisfy(w -> {
            assertThat(w.signUps()).isZero();
            assertThat(w.activeCreators()).isZero();
            assertThat(w.streamHours()).isZero();
        });
        assertThat(r.weeks().get(11).start()).isEqualTo(LocalDate.of(2031, 3, 17));
        assertThat(r.weeks().get(11).partial()).isTrue();
        assertThat(r.weeks().get(0).start()).isEqualTo(LocalDate.of(2030, 12, 30));
        assertThat(r.cohorts()).hasSize(8).allSatisfy(c -> {
            assertThat(c.accounts()).isZero();
            assertThat(c.activePercent()).containsOnlyNulls();
        });
    }

    @Test
    void countsSignUpsActiveCreatorsHoursAndCohorts() {
        User a1 = account("new", "2031-03-18T09:00:00Z", null);
        wentLive(a1, "2031-03-18T10:05:00Z");
        run(a1, "2031-03-18T10:00:00Z", "2031-03-18T12:00:00Z", null);                 // 2 h
        run(a1, "2031-03-19T09:00:00Z", null, "2031-03-19T10:00:00Z");                 // 1 h, ended by its last heartbeat
        User a2 = account("quiet", "2031-03-10T09:00:00Z", null);
        User a3 = account("regular", "2031-03-03T09:00:00Z", null);
        run(a3, "2031-03-04T10:00:00Z", "2031-03-04T11:00:00Z", null);                 // 1 h in week of 03-03
        run(a3, "2031-03-16T23:00:00Z", "2031-03-17T01:00:00Z", null);                 // 1 h each side of the week boundary
        run(a3, "2031-03-18T10:00:00Z", "2031-03-18T10:30:00Z", null);                 // 0.5 h
        run(a3, "2031-03-20T10:00:00Z", "2031-03-20T11:00:00Z", null);                 // after "now": not counted
        account("old", null, null);                                                    // made before created_at existed
        User admin = account("ops", "2031-03-18T09:00:00Z", "ROLE_ADMIN");             // admins are left out
        run(admin, "2031-03-18T10:00:00Z", "2031-03-18T20:00:00Z", null);

        BusinessMetrics.Report r = metrics.report(NOW, scope());
        assertThat(r.accounts().total()).isEqualTo(4);
        assertThat(r.accounts().undated()).isEqualTo(1);
        assertThat(r.accounts().wentLive()).isEqualTo(1);
        assertThat(r.accounts().wentLivePercent()).isEqualTo(25.0);

        BusinessMetrics.Week thisWeek = r.weeks().get(11);
        assertThat(thisWeek.signUps()).isEqualTo(1);
        assertThat(thisWeek.signUpsWentLive()).isEqualTo(1);
        assertThat(thisWeek.activeCreators()).isEqualTo(2);
        assertThat(thisWeek.streamHours()).isCloseTo(4.5, within(0.001));
        BusinessMetrics.Week lastWeek = r.weeks().get(10);
        assertThat(lastWeek.start()).isEqualTo(LocalDate.of(2031, 3, 10));
        assertThat(lastWeek.signUps()).isEqualTo(1);
        assertThat(lastWeek.signUpsWentLive()).isZero();
        assertThat(lastWeek.activeCreators()).isEqualTo(1);
        assertThat(lastWeek.streamHours()).isCloseTo(1.0, within(0.001));
        BusinessMetrics.Week twoAgo = r.weeks().get(9);
        assertThat(twoAgo.activeCreators()).isEqualTo(1);
        assertThat(twoAgo.streamHours()).isCloseTo(1.0, within(0.001));

        // Newest cohort first; null = that week hasn't started yet.
        assertThat(r.cohorts().get(0).start()).isEqualTo(LocalDate.of(2031, 3, 17));
        assertThat(r.cohorts().get(0).accounts()).isEqualTo(1);
        assertThat(r.cohorts().get(0).activePercent()).containsExactly(100.0, null, null, null, null, null, null, null);
        assertThat(r.cohorts().get(1).activePercent()).containsExactly(0.0, 0.0, null, null, null, null, null, null);
        assertThat(r.cohorts().get(2).start()).isEqualTo(LocalDate.of(2031, 3, 3));
        assertThat(r.cohorts().get(2).activePercent()).containsExactly(100.0, 100.0, 100.0, null, null, null, null, null);
        assertThat(r.cohorts().get(3).accounts()).isZero();
        assertThat(a2.getId()).isNotNull();
    }

    @Test
    void paidConversionMrrAndChurn() {
        String old = "2030-01-01T00:00:00Z";
        User monthly = account("monthly", old, null);
        paid(monthly);
        subscription(monthly, PlanType.ESSENTIALS, "MONTHLY", "INR", "ACTIVE", false, "2031-03-01T00:00:00Z");
        User yearlyEnding = account("yearly", old, null);                              // set to end mid-month: still paid through the period
        paid(yearlyEnding);
        subscription(yearlyEnding, PlanType.PRO, "YEARLY", "INR", "ACTIVE", true, "2031-03-12T00:00:00Z");
        User dollars = account("usd", old, null);
        paid(dollars);
        subscription(dollars, PlanType.ESSENTIALS, "MONTHLY", "USD", "ACTIVE", false, "2031-03-01T00:00:00Z");
        User grace = account("grace", old, null);
        paid(grace);
        subscription(grace, PlanType.ESSENTIALS, "MONTHLY", "INR", "PAST_DUE", false, "2031-03-15T00:00:00Z");
        User leftThisMonth = account("left", old, null);
        paid(leftThisMonth);
        subscription(leftThisMonth, PlanType.ESSENTIALS, "MONTHLY", "INR", "CANCELED", false, "2031-03-05T00:00:00Z");
        User leftLastMonth = account("leftfeb", old, null);
        paid(leftLastMonth);
        subscription(leftLastMonth, PlanType.PRO, "MONTHLY", "INR", "CANCELED", false, "2031-02-20T00:00:00Z");
        User comped = account("comped", old, null);                                   // given by an admin, never paid
        subscription(comped, PlanType.AGENCY, "MONTHLY", "INR", "ACTIVE", false, "2031-03-01T00:00:00Z");
        account("free", old, null);

        BusinessMetrics.Report r = metrics.report(NOW, scope());
        assertThat(r.accounts().total()).isEqualTo(8);
        assertThat(r.conversion().everPaid()).isEqualTo(6);
        assertThat(r.conversion().everPaidPercent()).isEqualTo(75.0);
        assertThat(r.conversion().payingNow()).isEqualTo(4);
        assertThat(r.conversion().payingNowPercent()).isEqualTo(50.0);
        assertThat(r.conversion().inGrace()).isEqualTo(1);
        assertThat(r.conversion().endingAtPeriodEnd()).isEqualTo(1);
        assertThat(r.conversion().givenByAdmin()).isEqualTo(1);

        PlanConfig creator = plans.getPlanConfig(PlanType.ESSENTIALS);
        PlanConfig pro = plans.getPlanConfig(PlanType.PRO);
        double expectedInr = creator.getPriceInrMonth() / 100.0 / 1.18 + pro.getPriceInrYear() / 100.0 / 12 / 1.18;
        assertThat(r.mrr().inr()).isCloseTo(expectedInr, within(0.01));
        assertThat(r.mrr().usd()).isCloseTo(creator.getPriceUsdMonth() / 100.0, within(0.01));
        assertThat(r.mrr().formula()).contains("1.18").contains("12");

        assertThat(r.plans()).extracting(p -> p.plan() + "/" + p.cycle() + "/" + p.currency() + "=" + p.accounts())
                .containsExactlyInAnyOrder("ESSENTIALS/MONTHLY/INR=2", "ESSENTIALS/MONTHLY/USD=1", "PRO/YEARLY/INR=1");
        BusinessMetrics.PlanRow creatorInr = r.plans().stream().filter(p -> p.plan().equals("ESSENTIALS") && p.currency().equals("INR")).findFirst().orElseThrow();
        assertThat(creatorInr.inGrace()).isEqualTo(1);
        assertThat(creatorInr.mrr()).isCloseTo(creator.getPriceInrMonth() / 100.0 / 1.18, within(0.01)); // the one in grace adds nothing

        assertThat(r.churn().month()).isEqualTo("2031-03");
        assertThat(r.churn().thisMonth()).isEqualTo(1);
        assertThat(r.churn().previousMonth()).isEqualTo("2031-02");
        assertThat(r.churn().lastMonth()).isEqualTo(1);
        assertThat(r.churn().ratePercent()).isEqualTo(20.0);
        assertThat(Arrays.asList(r.weeks().get(11).signUps(), r.weeks().get(11).activeCreators())).containsOnly(0L);
    }

    @Test
    void onlyAdminsCanReadBusinessNumbers() throws Exception {
        User creator = account("creator", "2031-03-18T09:00:00Z", null);
        mvc.perform(get("/api/admin/business").with(user(creator.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/business")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/admin/business").with(user("ops@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks.length()").value(12))
                .andExpect(jsonPath("$.cohorts.length()").value(8))
                .andExpect(jsonPath("$.mrr.formula").isString())
                .andExpect(jsonPath("$.weekStarts").value("Monday 00:00 UTC"));
    }
}
