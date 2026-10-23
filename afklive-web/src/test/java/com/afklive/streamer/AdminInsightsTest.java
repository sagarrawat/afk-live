package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.QuotaAudit;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.QuotaAuditRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.streaming.StreamWorker;
import com.afklive.streamer.streaming.WorkerStatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** YT-4 (quota), AD-2 (worker CPU and memory, cost per stream hour) in the admin API. */
@SpringBootTest
@TestPropertySource(properties = {"app.admin.monthly-server-cost=12000", "app.youtube.daily-quota=10000"})
class AdminInsightsTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired QuotaAuditRepository quota;
    @Autowired WorkerStatusRepository workers;
    @Autowired WorkerStatusReporter reporter;
    @Autowired StreamWorker worker;
    @Autowired StreamService streamService;

    MockMvc mvc;
    User customer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = new User("ins-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        customer.setPlanType(PlanType.FREE);
        customer = users.save(customer);
    }

    @AfterEach
    void cleanUp() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("ins-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
        workers.deleteAll();
    }

    @Test
    void onlyAdminsSeeQuotaAndCost() throws Exception {
        mvc.perform(get("/api/admin/quota").with(user(customer.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/cost").with(user(customer.getEmail()))).andExpect(status().isForbidden());
    }

    @Test
    void quotaShowsTodaysUnitsByApiAgainstTheDailyLimit() throws Exception {
        int before = Integer.parseInt(mvc.perform(get("/api/admin/quota").with(user("a@x").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString().replaceAll("^\\{\"usedToday\":(\\d+).*$", "$1"));
        quota.save(new QuotaAudit("videos.insert", 1600, customer, "SUCCESS"));
        quota.save(new QuotaAudit("liveBroadcasts.list", 1, customer, "SUCCESS"));
        mvc.perform(get("/api/admin/quota").with(user("a@x").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedToday").value(before + 1601))
                .andExpect(jsonPath("$.dailyLimit").value(10000))
                .andExpect(jsonPath("$.percent").value(greaterThanOrEqualTo(16)))
                .andExpect(jsonPath("$.resetsAt").exists())
                .andExpect(jsonPath("$.breakdown[?(@.api == 'videos.insert')].units").exists());
    }

    @Test
    void workersReportCpuAndMemoryAndIdleOnesAreListed() throws Exception {
        reporter.report();
        assertThat(workers.findById(worker.workerId())).hasValueSatisfying(w -> {
            assertThat(w.getCapacity()).isEqualTo(worker.capacity());
            assertThat(w.getMemoryTotalBytes()).isPositive();
            assertThat(w.getMemoryUsedBytes()).isBetween(0L, w.getMemoryTotalBytes());
        });
        mvc.perform(get("/api/admin/workers").with(user("a@x").roles("ADMIN")))
                .andExpect(jsonPath("$[?(@.worker == '" + worker.workerId() + "')].healthy").value(true))
                .andExpect(jsonPath("$[?(@.worker == '" + worker.workerId() + "')].memoryTotalBytes").exists())
                .andExpect(jsonPath("$[?(@.worker == '" + worker.workerId() + "')].capacity").value(worker.capacity()));
    }

    @Test
    void aWorkerThatStoppedReportingIsUnhealthy() throws Exception {
        ZonedDateTime old = ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(5);
        workers.report("worker-quiet", old.minusHours(1), old, 0, 20, 12.0, 1L, 2L);
        mvc.perform(get("/api/admin/workers").with(user("a@x").roles("ADMIN")))
                .andExpect(jsonPath("$[?(@.worker == 'worker-quiet')].healthy").value(false))
                .andExpect(jsonPath("$[?(@.worker == 'worker-quiet')].cpuPercent").value(12.0));
    }

    @Test
    void costPerStreamHourDividesTheMonthlyBillByLast30DaysHours() throws Exception {
        String before = mvc.perform(get("/api/admin/cost").with(user("a@x").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString();
        double hoursBefore = Double.parseDouble(before.replaceAll("^.*\"streamHours30d\":([0-9.]+).*$", "$1"));
        // A 10-hour run that ended an hour ago, and one that ended before the 30-day window (doesn't count).
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        StreamJob a = new StreamJob(customer, "k", "v.mp4", null, null, false, 0, "A", "d", "private", now.minusHours(11), "YouTube", false);
        a.setEndTime(now.minusHours(1));
        a.setStatus("ENDED");
        jobs.save(a);
        StreamJob old = new StreamJob(customer, "k", "v.mp4", null, null, false, 0, "B", "d", "private", now.minusDays(40), "YouTube", false);
        old.setEndTime(now.minusDays(35));
        old.setStatus("ENDED");
        jobs.save(old);
        String after = mvc.perform(get("/api/admin/cost").with(user("a@x").roles("ADMIN")))
                .andExpect(jsonPath("$.monthlyServerCost").value(12000.0))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.costPerStreamHour").exists())
                .andReturn().getResponse().getContentAsString();
        double hoursAfter = Double.parseDouble(after.replaceAll("^.*\"streamHours30d\":([0-9.]+).*$", "$1"));
        assertThat(hoursAfter - hoursBefore).isBetween(9.95, 10.05);
        double perHour = Double.parseDouble(after.replaceAll("^.*\"costPerStreamHour\":([0-9.]+).*$", "$1"));
        assertThat(perHour).isCloseTo(12000 / hoursAfter, org.assertj.core.data.Offset.offset(0.01));
    }
}
