package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.AdminConsoleService;
import com.afklive.streamer.service.CostMeter;
import com.afklive.streamer.service.CostModel;
import com.afklive.streamer.service.NotificationService;
import com.afklive.streamer.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FO-3 / FO-5: the cost report from real rows, admin-only access, and traffic alerts claimed once across instances. */
@SpringBootTest
class CostMeteringTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired CostMeter meter;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired PlanService plans;
    @Autowired NotificationService notifications;

    MockMvc mvc;
    String tag;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private User account(String prefix, PlanType plan, String role) {
        User u = new User(prefix + "-" + tag + "@example.com");
        u.setPlanType(plan);
        if (role != null) {
            u.setRole(role);
        }
        return users.save(u);
    }

    /** An ended run between {@code from} and {@code to} whose destinations sent {@code bytes} in all. */
    private StreamJob endedRun(User u, ZonedDateTime from, ZonedDateTime to, long... bytes) {
        StreamJob job = new StreamJob(u, "key", "video.mp4", null, null, false, 0, "Run " + tag, "d", "private", from, "YouTube");
        job.setStatus("ENDED");
        job.setDesiredState("STOP");
        job.setEndTime(to);
        job.setWorkerId("cost-worker-" + tag);
        job.setSpec("{\"maxHeight\":1080,\"studio\":{\"height\":1080,\"fps\":60}}");
        for (int i = 0; i < bytes.length; i++) {
            StreamJobDestination d = new StreamJobDestination(job, i, "YouTube " + i);
            d.setBytesSent(bytes[i]);
            job.getDestinations().add(d);
        }
        return jobs.save(job);
    }

    private long alertsFor(User u, String month, int threshold) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND dedupe_key = ?", Long.class,
                u.getId(), "transfer:" + month + ":" + threshold);
        return n == null ? 0 : n;
    }

    @Test
    void onlyAdminsCanReadCostsAndCopies() throws Exception {
        User creator = account("cost-user", PlanType.FREE, null);
        for (String path : new String[] {"/api/admin/costs", "/api/admin/copies"}) {
            mvc.perform(get(path).with(user(creator.getEmail()))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("ops@example.com").roles("ADMIN"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/costs").with(user("ops@example.com").roles("ADMIN")))
                .andExpect(jsonPath("$.cost.inputs.includedGb").value(5000.0))
                .andExpect(jsonPath("$.cost.inputs.includedDefault").value(true))
                .andExpect(jsonPath("$.cost.inputs.currency").value("INR"))
                .andExpect(jsonPath("$.cost.inputs.pricePerGb").value(0.85))
                .andExpect(jsonPath("$.cost.totals.hours").isNumber())
                .andExpect(jsonPath("$.cost.margins").isArray())
                .andExpect(jsonPath("$.copies").doesNotExist());
        mvc.perform(get("/api/admin/copies").with(user("ops@example.com").roles("ADMIN")))
                .andExpect(jsonPath("$.keepDays").value(30))
                .andExpect(jsonPath("$.kinds").isArray());
    }

    @Test
    void aRunsBytesCountOnlyForItsTimeInsideTheMonth() {
        User u = account("cost-run", PlanType.PRO, null);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC);
        // 4 hours, half of it last month, two destinations that sent 4 GiB in all.
        StreamJob straddling = endedRun(u, monthStart.minusHours(2), monthStart.plusHours(2), 3L << 30, 1L << 30);
        StreamJob lastMonth = endedRun(u, monthStart.minusHours(5), monthStart.minusHours(4), 1L << 30);

        List<Long> ids = List.of(straddling.getId(), lastMonth.getId());
        var mine = meter.runsThisMonth(now).stream().filter(r -> ids.contains(r.jobId())).toList();
        assertThat(mine).hasSize(1);
        CostModel.Slice s = mine.getFirst().slice();
        assertThat(s.seconds()).isCloseTo(2 * 3600, within(1.0));
        assertThat(s.bytes() / CostModel.GIB).isCloseTo(2.0, within(0.001));
        assertThat(s.plan()).isEqualTo("PRO");
        assertThat(s.height()).isEqualTo(1080);
        assertThat(s.fps()).isEqualTo(60);

        // The report includes it under its plan, quality, account and server.
        CostMeter.Report r = meter.report(now);
        assertThat(r.cost().byQuality()).extracting(CostModel.Group::key).contains("1080p 60 fps");
        assertThat(r.byServer()).extracting(CostMeter.TrafficRow::key).contains("cost-worker-" + tag);
        assertThat(r.topAccounts()).anySatisfy(a -> {
            assertThat(a.key()).isEqualTo(u.getEmail());
            assertThat(a.gb()).isCloseTo(2.0, within(0.001));
        });
    }

    @Test
    void eachThresholdAlertsAdminsOncePerMonthEvenWithTwoWebInstances() throws Exception {
        User admin = account("cost-admin", PlanType.FREE, "ROLE_ADMIN");
        User creator = account("cost-creator", PlanType.FREE, null);
        CostMeter other = new CostMeter(named, plans, notifications, users, "", "INR", "1000", "", "web", false);
        String m = "t" + tag.substring(0, 6); // a month key unique to this run (7 characters, like "2026-10")

        // Under 70%: nothing.
        assertThat(meter.alertIfCrossed(m, 600, 1000)).isNull();

        // Both instances notice 75% at the same moment: one alert.
        List<Integer> results = race(() -> meter.alertIfCrossed(m, 750, 1000), () -> other.alertIfCrossed(m, 750, 1000));
        assertThat(results).containsExactlyInAnyOrder(70, null);
        assertThat(alertsFor(admin, m, 70)).isEqualTo(1);
        assertThat(alertsFor(creator, m, 70)).isZero(); // admins only

        // Later checks at 80%: nothing new.
        assertThat(meter.alertIfCrossed(m, 800, 1000)).isNull();
        assertThat(other.alertIfCrossed(m, 800, 1000)).isNull();

        // 92%: the 90% alert, once.
        results = race(() -> meter.alertIfCrossed(m, 920, 1000), () -> other.alertIfCrossed(m, 920, 1000));
        assertThat(results).containsExactlyInAnyOrder(90, null);
        assertThat(alertsFor(admin, m, 90)).isEqualTo(1);
        assertThat(alertsFor(admin, m, 70)).isEqualTo(1);

        // A new month starts over; jumping straight past 90% sends only the 90% alert and uses up 70% too.
        String next = "n" + tag.substring(0, 6);
        assertThat(meter.alertIfCrossed(next, 950, 1000)).isEqualTo(90);
        assertThat(meter.alertIfCrossed(next, 960, 1000)).isNull();
        assertThat(alertsFor(admin, next, 70)).isZero();
        assertThat(alertsFor(admin, next, 90)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_alerts WHERE month = ?", Integer.class, next)).isEqualTo(2);

        // Nothing included: no budget, no alerts.
        assertThat(meter.alertIfCrossed("z" + tag.substring(0, 6), 5000, 0)).isNull();
    }

    @SafeVarargs
    private static List<Integer> race(Callable<Integer>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (Callable<Integer> c : calls) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theOverviewSaysWhenTrafficPassesSeventyPercent() {
        String month = "2026-10";
        assertThat(AdminConsoleService.transferAttention(new CostMeter.Use(month, 600, 1000, ZonedDateTime.now()))).isNull();
        AdminConsoleService.Attention amber = AdminConsoleService.transferAttention(new CostMeter.Use(month, 700, 1000, ZonedDateTime.now()));
        assertThat(amber.kind()).isEqualTo(AdminConsoleService.TRANSFER);
        assertThat(amber.level()).isEqualTo("amber");
        assertThat(amber.title()).contains("70%");
        assertThat(amber.link()).isEqualTo("/studio/admin/billing");
        assertThat(AdminConsoleService.transferAttention(new CostMeter.Use(month, 905, 1000, ZonedDateTime.now())).level()).isEqualTo("red");
        assertThat(AdminConsoleService.transferAttention(new CostMeter.Use(month, 905, 0, ZonedDateTime.now()))).isNull();
    }

    @Test
    void theBudgetCheckUsesThisMonthsTraffic() {
        // A huge run this month on a 1,000 GB budget (a meter of its own): the check alerts at 90%.
        User admin = account("cost-admin2", PlanType.FREE, "ROLE_ADMIN");
        User u = account("cost-big", PlanType.PRO, null);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime from = now.minusMinutes(30).isBefore(now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC))
                ? now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC) : now.minusMinutes(30);
        endedRun(u, from, now.minusSeconds(1), 2000L << 30);
        CostMeter small = new CostMeter(named, plans, notifications, users, "", "INR", "1000", "", "web", false);
        String month = com.afklive.streamer.service.BillingService.month(now);
        jdbc.update("DELETE FROM transfer_alerts WHERE month = ?", month);
        try {
            assertThat(small.use(now).gb()).isGreaterThanOrEqualTo(1999.0);
            assertThat(small.checkTransferBudget(now)).isEqualTo(90);
            assertThat(alertsFor(admin, month, 90)).isEqualTo(1);
            assertThat(small.checkTransferBudget(now)).isNull();
        } finally {
            jdbc.update("DELETE FROM transfer_alerts WHERE month = ?", month);
            jdbc.update("UPDATE stream_job_destinations SET bytes_sent = 0 WHERE job_id IN (SELECT id FROM stream_jobs WHERE user_id = ?)", u.getId());
        }
    }
}
