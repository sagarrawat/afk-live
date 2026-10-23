package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.StatusSignalRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
import com.afklive.streamer.service.StatusService;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RE-7: the public status page and the incidents admins post to it. */
@SpringBootTest
class StatusPageTest extends PostgresIntegrationTest {

    static final String ADMIN = "status-admin@example.com";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired WorkerStatusRepository workers;
    @Autowired StatusSignalRepository signals;
    @Autowired AdminActionRepository audit;
    @Autowired StatusService statusService;
    @Autowired StreamService streamService;

    MockMvc mvc;
    User customer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = new User("stat-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        customer.setFullName("Priya Secretname");
        customer.setPlanType(PlanType.PRO);
        customer = users.save(customer);
        workers.deleteAll();
        signals.deleteAll();
        fresh();
    }

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("stat-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
        workers.deleteAll();
    }

    /** The status is cached for a few seconds per instance; tests want the current answer. */
    private void fresh() {
        ReflectionTestUtils.setField(statusService, "cached", null);
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC"));
    }

    private void workerReports(String id, ZonedDateTime at) {
        workers.report(id, at.minusHours(2), at, 1, 20, 37.5, 4L << 30, 8L << 30);
    }

    private StreamJob liveJob(String worker) {
        StreamJob job = new StreamJob(customer, "key", "video.mp4", null, null, true, 0,
                "Secret Lofi Channel", "d", "private", now().minusHours(1), "YouTube", false);
        job.setStatus("LIVE");
        job.setDesiredState("RUN");
        job.setWorkerId(worker);
        job.setHeartbeatAt(now());
        return jobs.save(job);
    }

    @Test
    void anyoneCanReadItWithoutSigningIn() throws Exception {
        workerReports("worker-public-1", now());
        mvc.perform(get("/api/public/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").exists())
                .andExpect(jsonPath("$.components[?(@.id == 'website')].status").value("operational"))
                .andExpect(jsonPath("$.components[?(@.id == 'streams')].status").exists())
                .andExpect(jsonPath("$.components[?(@.id == 'phone')].status").exists());
        mvc.perform(get("/status")).andExpect(status().isOk()).andExpect(forwardedUrl("/site/status.html"));
    }

    @Test
    void neverShowsAccountsStreamsWorkersOrCounts() throws Exception {
        workerReports("worker-private-name", now());
        liveJob("worker-private-name");
        String body = mvc.perform(get("/api/public/status")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(customer.getEmail()).doesNotContain("Secretname").doesNotContain("Secret Lofi")
                .doesNotContain("worker-private-name").doesNotContain("37.5").doesNotContain("streams\":1")
                .doesNotContainIgnoringCase("count");
    }

    @Test
    void streamServersAreDownWithNoWorkerReportingAndUpWithOne() throws Exception {
        workerReports("worker-old", now().minusMinutes(10));
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.components[?(@.id == 'streams')].status").value("outage"))
                .andExpect(jsonPath("$.overall").value("outage"));
        workerReports("worker-new", now());
        fresh();
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.components[?(@.id == 'streams')].status").value(not("outage")));
    }

    @Test
    void aStreamWhoseWorkerStoppedReportingIsDegraded() throws Exception {
        workerReports("worker-alive", now());
        StreamJob j = liveJob("worker-gone");
        j.setHeartbeatAt(now().minusMinutes(5));
        jobs.save(j);
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.components[?(@.id == 'streams')].status").value("degraded"));
    }

    @Test
    void phoneStreamingSaysWhatWasMeasured() throws Exception {
        workerReports("worker-p", now());
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.components[?(@.id == 'phone')].status").value("no_data"));
        // The ingest server calls its auth hook (here a publish to an unknown key: denied, but the server is up).
        mvc.perform(post("/internal/ingest/auth").param("secret", "test-auth-secret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"publish\",\"path\":\"live/nope\"}"))
                .andExpect(status().isUnauthorized());
        fresh();
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.components[?(@.id == 'phone')].status").value("operational"));
    }

    @Test
    void aWrongSecretOnTheHookDoesNotCountAsTheIngestServer() throws Exception {
        workerReports("worker-q", now());
        mvc.perform(post("/internal/ingest/auth").param("secret", "wrong").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"publish\",\"path\":\"live/nope\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.components[?(@.id == 'phone')].status").value("no_data"));
    }

    @Test
    void onlyAdminsPostIncidents() throws Exception {
        String body = "{\"title\":\"Streams reconnecting\",\"status\":\"investigating\",\"message\":\"Looking into it\"}";
        mvc.perform(post("/api/admin/status/incidents").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/admin/status/incidents").with(user(customer.getEmail())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/status/incidents").with(user(customer.getEmail()))).andExpect(status().isForbidden());
    }

    @Test
    void adminsPostAnIncidentWithUpdatesAndThePublicSeesItWithoutTheirEmail() throws Exception {
        workerReports("worker-i", now());
        String title = "Streams reconnecting " + UUID.randomUUID().toString().substring(0, 6);
        String created = mvc.perform(post("/api/admin/status/incidents").with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"status\":\"investigating\",\"message\":\"Some streams dropped.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("investigating"))
                .andExpect(jsonPath("$.updates.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll("^\\{\"id\":(\\d+).*$", "$1"));

        String pub = mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.overall").value(not("operational")))
                .andExpect(jsonPath("$.incidents[?(@.title == '" + title + "')].status").value("investigating"))
                .andReturn().getResponse().getContentAsString();
        assertThat(pub).doesNotContain(ADMIN).doesNotContain("createdBy");

        mvc.perform(post("/api/admin/status/incidents/" + id + "/updates").with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"resolved\",\"message\":\"All streams back.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andExpect(jsonPath("$.updates.length()").value(2))
                .andExpect(jsonPath("$.updates[0].message").value("All streams back."));
        mvc.perform(get("/api/public/status"))
                .andExpect(jsonPath("$.incidents[?(@.title == '" + title + "')].resolvedAt").exists());
        mvc.perform(get("/api/admin/status/incidents").with(user(ADMIN).roles("ADMIN")))
                .andExpect(jsonPath("$[?(@.incident.title == '" + title + "')].createdBy").value(ADMIN));
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("incident", String.valueOf(id))).hasSize(2);
    }

    @Test
    void incidentsNeedATitleAKnownStatusAndAMessage() throws Exception {
        mvc.perform(post("/api/admin/status/incidents").with(user(ADMIN).roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Outage\",\"status\":\"panicking\",\"message\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/status/incidents").with(user(ADMIN).roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Outage\",\"status\":\"identified\",\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/status/incidents/999999/updates").with(user(ADMIN).roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"resolved\",\"message\":\"done\"}"))
                .andExpect(status().isNotFound());
    }
}
