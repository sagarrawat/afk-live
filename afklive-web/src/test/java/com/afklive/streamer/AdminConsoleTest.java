package com.afklive.streamer;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.WorkerSampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin console (AD-1, AD-2, AD-3): overview, users columns, search, server history and its sampler. */
@SpringBootTest
class AdminConsoleTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired LiveStreamRepository streams;
    @Autowired PaymentAuditRepository payments;
    @Autowired WorkerStatusRepository workers;
    @Autowired StreamService streamService;
    @Autowired WorkerSampler sampler;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    User customer;
    String tag;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        customer = new User("con-" + tag + "@example.com");
        customer.setFullName("Meera " + tag);
        customer.setPlanType(PlanType.FREE);
        customer = users.save(customer);
    }

    @AfterEach
    void cleanUp() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("con-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
        jdbc.update("DELETE FROM worker_samples WHERE worker_id LIKE 'con-%'");
        jdbc.update("DELETE FROM stream_workers WHERE worker_id LIKE 'con-%'");
    }

    private static RequestPostProcessor admin() {
        return user("ops@example.com").roles("ADMIN");
    }

    private StreamJob liveJob(User u, Long streamId) {
        StreamJob job = new StreamJob(u, "key", "video.mp4", null, null, true, 0,
                "Run " + tag, "d", "private", ZonedDateTime.now(ZoneOffset.UTC).minusHours(1), "YouTube");
        job.setStatus("LIVE");
        job.setDesiredState("RUN");
        job.setWorkerId("con-worker");
        job.setStreamId(streamId);
        job.setHeartbeatAt(ZonedDateTime.now(ZoneOffset.UTC));
        return jobs.save(job);
    }

    @Test
    void onlyAdminsCanUseTheConsoleEndpoints() throws Exception {
        for (String path : new String[] {"/api/admin/attention", "/api/admin/search?q=abc", "/api/admin/servers/history", "/api/admin/users"}) {
            mvc.perform(get(path).with(user(customer.getEmail()))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(admin())).andExpect(status().isOk());
        }
    }

    @Test
    void usersTableHasPlanSignUpLastLiveHoursAndPaidAndFilters() throws Exception {
        // An older account without created_at, a paying one with a payment by id and one only by email.
        jdbc.update("UPDATE users SET created_at = NULL WHERE id = ?", customer.getId());
        User payer = new User("con-pay-" + tag + "@example.com");
        payer.setPlanType(PlanType.PRO);
        payer = users.save(payer);
        PaymentAudit byId = new PaymentAudit("MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), payer.getEmail(), 149_900L, "COMPLETED");
        byId.setUser(payer);
        payments.save(byId);
        payments.save(new PaymentAudit("MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), payer.getEmail(), 100L, "COMPLETED"));
        payments.save(new PaymentAudit("MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), payer.getEmail(), 999L, "FAILED"));
        jdbc.update("INSERT INTO usage_months(user_id, month, stream_seconds) VALUES (?, to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM'), 5400)", payer.getId());
        StreamJob run = liveJob(payer, null);

        mvc.perform(get("/api/admin/users").param("q", "con-pay-" + tag).with(admin()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].plan").value("PRO"))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0].lastLiveAt").exists())
                .andExpect(jsonPath("$.content[0].liveStreams").value(1))
                .andExpect(jsonPath("$.content[0].hoursThisMonth").value(1.5))
                .andExpect(jsonPath("$.content[0].paid[0].currency").value("INR"))
                .andExpect(jsonPath("$.content[0].paid[0].amount").value(150_000));
        mvc.perform(get("/api/admin/users").param("q", customer.getEmail()).with(admin()))
                .andExpect(jsonPath("$.content[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.content[0].lastLiveAt").doesNotExist())
                .andExpect(jsonPath("$.content[0].paid.length()").value(0));

        mvc.perform(get("/api/admin/users").param("q", tag).param("filter", "paying").with(admin()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(payer.getEmail()));
        mvc.perform(get("/api/admin/users").param("q", tag).param("filter", "live").with(admin()))
                .andExpect(jsonPath("$.content[*].email").value(hasItem(payer.getEmail())))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/admin/users").param("q", tag).param("filter", "never").with(admin()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(customer.getEmail()));
        mvc.perform(get("/api/admin/users").param("filter", "nonsense").with(admin())).andExpect(status().isBadRequest());
        streamService.finalizeJob(run.getId(), "test");
    }

    @Test
    void searchFindsUsersStreamsAndRuns() throws Exception {
        LiveStream s = new LiveStream();
        s.setUser(customer);
        s.setName("Rain sounds " + tag);
        s = streams.save(s);
        StreamJob run = liveJob(customer, s.getId());

        mvc.perform(get("/api/admin/search").param("q", "MEERA " + tag).with(admin()))
                .andExpect(jsonPath("$.users[0].email").value(customer.getEmail()));
        mvc.perform(get("/api/admin/search").param("q", "rain sounds " + tag).with(admin()))
                .andExpect(jsonPath("$.streams[0].id").value(s.getId()))
                .andExpect(jsonPath("$.streams[0].liveJobId").value(run.getId()))
                .andExpect(jsonPath("$.streams[0].userEmail").value(customer.getEmail()));
        mvc.perform(get("/api/admin/search").param("q", "#" + run.getId()).with(admin()))
                .andExpect(jsonPath("$.runs[0].jobId").value(run.getId()))
                .andExpect(jsonPath("$.runs[0].live").value(true));
        // LIKE wildcards are literal.
        mvc.perform(get("/api/admin/search").param("q", "%%").with(admin()))
                .andExpect(jsonPath("$.users.length()").value(0));
    }

    @Test
    void overviewListsWhatNeedsAttentionWorstFirst() throws Exception {
        StreamJob run = liveJob(customer, null);
        run.setSpeed(0.8);
        jobs.save(run);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO stream_incidents(job_id, kind, target, started_at) VALUES (?, 'UNREACHED', '', ?)",
                run.getId(), Timestamp.from(now.minusMinutes(3).toInstant()));
        PaymentAudit failed = new PaymentAudit("MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), customer.getEmail(), 49_900L, "FAILED");
        failed.setUser(customer);
        payments.save(failed);

        mvc.perform(get("/api/admin/attention").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attention[?(@.jobId == " + run.getId() + " && @.kind == 'NOT_REACHING')].level").value(hasItem("red")))
                .andExpect(jsonPath("$.attention[?(@.jobId == " + run.getId() + " && @.kind == 'SLOW')].level").value(hasItem("amber")))
                .andExpect(jsonPath("$.attention[?(@.kind == 'PAYMENT_FAILED')].link").value(hasItem("/studio/admin/users/" + customer.getId())))
                .andExpect(jsonPath("$.attention[0].level").value("red"))
                .andExpect(jsonPath("$.health.green").exists())
                .andExpect(jsonPath("$.signUpsToday").exists())
                .andExpect(jsonPath("$.firstGoLivesToday").exists());
    }

    @Test
    void samplerWritesEachWorkerOncePerMinuteAcrossInstances() throws Exception {
        // Days ahead, so workers other tests left reporting aren't fresh at this time.
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).plusDays(3).truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        workers.report("con-w1", now.minusHours(1), now.minusSeconds(10), 2, 20, 55.0, 2L << 30, 8L << 30);
        workers.report("con-w2", now.minusHours(1), now.minusSeconds(5), 0, 20, 5.0, 1L << 30, 8L << 30);
        workers.report("con-gone", now.minusHours(2), now.minusMinutes(10), 0, 20, 1.0, 1L, 2L); // stopped reporting

        // Two web instances sampling the same minute at the same time: the minute is written once.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> one = () -> sampler.sample(now);
        Future<Integer> a = pool.submit(one);
        Future<Integer> b = pool.submit(one);
        int written = a.get() + b.get();
        pool.shutdown();
        assertThat(written).isEqualTo(2);
        assertThat(sampler.sample(now.plusSeconds(20))).isZero(); // same minute again
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_samples WHERE worker_id LIKE 'con-%'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_samples WHERE worker_id = 'con-gone'", Long.class)).isZero();

        // The next minute is a new sample.
        workers.report("con-w1", now.minusHours(1), now.plusSeconds(50), 2, 20, 97.0, 3L << 30, 8L << 30);
        assertThat(sampler.sample(now.plusSeconds(60))).isGreaterThanOrEqualTo(1);
        jdbc.update("UPDATE worker_samples SET at = at - interval '3 days' WHERE worker_id LIKE 'con-%'");
        mvc.perform(get("/api/admin/servers/history").param("hours", "6").with(admin()))
                .andExpect(jsonPath("$[?(@.worker == 'con-w1')].bucketSeconds").value(hasItem(60)))
                .andExpect(jsonPath("$[?(@.worker == 'con-w1')].points[*].cpu").value(hasItem(97.0)))
                .andExpect(jsonPath("$[?(@.worker == 'con-w1')].points[*].memory").value(hasItem(25.0)));
    }

    @Test
    void samplesArePrunedAfterSevenDays() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO worker_samples(worker_id, at, cpu_percent, streams) VALUES ('con-old', ?, 10, 0), ('con-new', ?, 10, 0)",
                Timestamp.from(now.minusDays(7).minusMinutes(1).toInstant()), Timestamp.from(now.minusDays(6).toInstant()));
        assertThat(sampler.prune(now)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_samples WHERE worker_id = 'con-old'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_samples WHERE worker_id = 'con-new'", Long.class)).isEqualTo(1);
    }
}
