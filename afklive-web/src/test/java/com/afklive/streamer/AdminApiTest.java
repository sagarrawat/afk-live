package com.afklive.streamer;

import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.Subscription;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AD-1 / AD-3: the admin console API. */
@SpringBootTest
class AdminApiTest extends PostgresIntegrationTest {

    static final String ADMIN = "admin-ops@example.com";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentAuditRepository payments;
    @Autowired AdminActionRepository audit;
    @Autowired StreamService streamService;

    MockMvc mvc;
    User customer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = new User("adm-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        customer.setFullName("Aarti Sharma");
        customer.setPlanType(PlanType.FREE);
        customer = users.save(customer);
    }

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("adm-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN).roles("ADMIN");
    }

    private StreamJob liveJob(String worker) {
        StreamJob job = new StreamJob(customer, "key", "video.mp4", null, null, true, 0,
                "Lofi 24/7", "d", "private", ZonedDateTime.now(ZoneId.of("UTC")).minusHours(1), "YouTube");
        job.setStatus("LIVE");
        job.setDesiredState("RUN");
        job.setWorkerId(worker);
        job.setHeartbeatAt(worker == null ? null : ZonedDateTime.now(ZoneId.of("UTC")));
        return jobs.save(job);
    }

    private String json(String s) {
        return s.replace('\'', '"');
    }

    @Test
    void onlyAdminsCanUseIt() throws Exception {
        mvc.perform(get("/api/admin/live").with(user(customer.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/live").with(admin())).andExpect(status().isOk());
    }

    @Test
    void liveListShowsWorkerAndHealthAndCanBeFiltered() throws Exception {
        StreamJob j = liveJob("worker-a");
        mvc.perform(get("/api/admin/live").param("q", customer.getEmail()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(j.getId()))
                .andExpect(jsonPath("$[0].worker").value("worker-a"))
                .andExpect(jsonPath("$[0].streamName").value("Lofi 24/7"))
                .andExpect(jsonPath("$[0].health").exists());
        mvc.perform(get("/api/admin/live").param("q", customer.getEmail()).param("worker", "worker-b").with(admin()))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/admin/workers").with(admin()))
                .andExpect(jsonPath("$[?(@.worker == 'worker-a')].streams").exists());
    }

    @Test
    void stoppingNeedsAReasonAndIsAudited() throws Exception {
        StreamJob j = liveJob(null); // no worker: ends right away
        mvc.perform(post("/api/admin/jobs/" + j.getId() + "/stop").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/jobs/" + j.getId() + "/stop").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest()); // malformed bodies are 400s, not 500s
        mvc.perform(post("/api/admin/jobs/" + j.getId() + "/stop").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'Copyright complaint'}")))
                .andExpect(status().isOk());
        StreamJob after = jobs.findById(j.getId()).orElseThrow();
        assertThat(after.isLive()).isFalse();
        assertThat(after.getEndReason()).contains("Copyright complaint");
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("job", String.valueOf(j.getId())))
                .anyMatch(a -> a.getAction().equals("STREAM_STOP") && a.getAdminEmail().equals(ADMIN));
    }

    @Test
    void movingReleasesTheStreamToAnotherWorker() throws Exception {
        StreamJob j = liveJob("worker-a");
        ZonedDateTime beat = jobs.findById(j.getId()).orElseThrow().getHeartbeatAt();
        mvc.perform(post("/api/admin/jobs/" + j.getId() + "/move").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'Draining worker-a'}")))
                .andExpect(status().isOk());

        StreamJob after = jobs.findById(j.getId()).orElseThrow();
        assertThat(after.getWorkerId()).isNull();
        assertThat(after.isLive()).isTrue();
        // the old worker's next heartbeat fails, so it lets go
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        assertThat(jobs.heartbeat(j.getId(), "worker-a", beat, now, "LIVE", null, null, null, null, null)).isZero();
        // other workers can claim it; worker-a leaves it alone for a minute
        assertThat(jobs.findUnclaimedIds("worker-a", now.minusMinutes(1))).doesNotContain(j.getId());
        assertThat(jobs.findUnclaimedIds("worker-b", now.minusMinutes(1))).contains(j.getId());
        assertThat(jobs.findUnclaimedIds("worker-a", now.plusMinutes(2))).contains(j.getId()); // only worker left: takes it back
        assertThat(jobs.claim(j.getId(), "worker-b", now)).isEqualTo(1);

        // moving a stream that's not on a worker any more is a conflict
        jobs.release(j.getId(), "worker-b", now);
        mvc.perform(post("/api/admin/jobs/" + j.getId() + "/move").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'again'}")))
                .andExpect(status().isConflict());
    }

    @Test
    void findAUserAndSeeEverything() throws Exception {
        liveJob("worker-a");
        mvc.perform(get("/api/admin/users").param("q", "aarti sharma").with(admin()))
                .andExpect(jsonPath("$.content[0].email").value(customer.getEmail()));
        mvc.perform(get("/api/admin/users/" + customer.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.hoursIncluded").value(10))
                .andExpect(jsonPath("$.subscription").doesNotExist());
    }

    @Test
    void planChangesFollowTheSubscriptionAndAreAudited() throws Exception {
        String until = ZonedDateTime.now(ZoneId.of("UTC")).plusDays(30).toString();
        mvc.perform(post("/api/admin/users/" + customer.getId() + "/plan").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'plan':'PRO','until':'" + until + "','reason':'Partner comp'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.history[0].action").value("PLAN_SET"));
        Subscription s = subscriptions.findByUserId(customer.getId()).orElseThrow();
        assertThat(s.getPlanType()).isEqualTo(PlanType.PRO);

        mvc.perform(post("/api/admin/users/" + customer.getId() + "/extend").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'days':7,'reason':'Outage on 3 Oct'}")))
                .andExpect(status().isOk());
        assertThat(subscriptions.findByUserId(customer.getId()).orElseThrow().getCurrentPeriodEnd())
                .isAfter(ZonedDateTime.now(ZoneId.of("UTC")).plusDays(36));

        // a paid plan needs an end date
        mvc.perform(post("/api/admin/users/" + customer.getId() + "/plan").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'plan':'PRO','reason':'no date'}")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/users/" + customer.getId() + "/plan").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'plan':'FREE','reason':'Comp over'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.subscription.status").value("CANCELED"));
    }

    @Test
    void disablingAnAccountStopsItsStreams() throws Exception {
        StreamJob j = liveJob(null);
        mvc.perform(post("/api/admin/users/" + customer.getId() + "/enabled").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'enabled':false,'reason':'Abuse report'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(jobs.findById(j.getId()).orElseThrow().isLive()).isFalse();
    }

    @Test
    void refundsAreRecorded() throws Exception {
        PaymentAudit p = new PaymentAudit("MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                customer.getEmail(), 49_900L, "COMPLETED");
        p.setUser(customer);
        payments.save(p);
        mvc.perform(post("/api/admin/payments/" + p.getTransactionId() + "/refund").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'amount':60000,'reason':'Double charge'}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/payments/" + p.getTransactionId() + "/refund").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'amount':49900,'endPlan':true,'reason':'Double charge'}")))
                .andExpect(status().isOk());
        assertThat(payments.findByTransactionId(p.getTransactionId()).orElseThrow().getStatus()).isEqualTo("REFUNDED");
        mvc.perform(get("/api/admin/users/" + customer.getId()).with(admin()))
                .andExpect(jsonPath("$.payments[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.history[0].action").value("PAYMENT_REFUND"));
        mvc.perform(get("/api/admin/audit").with(admin())).andExpect(jsonPath("$.content[0].action").exists());
    }
}
