package com.afklive.streamer;

import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.AlertMonitor;
import com.afklive.streamer.service.NotificationService;
import com.afklive.streamer.service.PlanService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.streaming.StreamStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NT-1 / NT-2 / SC-5: when creators hear about problems, how often, and through which API. */
@SpringBootTest
class AlertsTest extends PostgresIntegrationTest {

    @Autowired AlertMonitor monitor;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamIncidentRepository incidents;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService notificationService;
    @Autowired UserRepository users;
    @Autowired StreamService streamService;
    @Autowired LiveStreamRepository streams;
    @Autowired PlanService plans;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    User owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = new User("alerts-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        owner.setPlanType(PlanType.ESSENTIALS);
        owner = users.save(owner);
    }

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("alerts-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private StreamJob liveJob(String status, ZonedDateTime now) {
        StreamJob j = new StreamJob(owner, "k", null, null, null, true, 0, "Lofi radio", null, null, now.minusMinutes(10), "YouTube", false);
        j.setStatus(status);
        j.setDesiredState(StreamStates.RUN);
        j.setWorkerId("w1");
        j.setHeartbeatAt(now);
        j.setLastBillingTime(now);
        StreamJobDestination d = new StreamJobDestination(j, 0, "YouTube");
        d.setStatus("LIVE");
        j.getDestinations().add(d);
        return jobs.save(j);
    }

    private List<String> types() {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(owner.getId()).stream().map(Notification::getType).toList();
    }

    @Test
    void aStreamThatStaysDownForAMinuteAlertsOnceAndAgainWhenItRecovers() {
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = liveJob(StreamStates.RECONNECTING, t);

        monitor.check(t);
        assertThat(types()).isEmpty(); // a short hiccup isn't an outage
        monitor.check(t.plusSeconds(30));
        assertThat(types()).isEmpty();
        monitor.check(t.plusSeconds(65));
        assertThat(types()).containsExactly(NotificationService.Types.STREAM_DOWN);
        monitor.check(t.plusSeconds(95));
        assertThat(types()).hasSize(1); // not repeated while it's still down

        job = jobs.findById(job.getId()).orElseThrow();
        job.setStatus(StreamStates.LIVE);
        job.setHeartbeatAt(t.plusSeconds(120)); // its worker is reporting again
        jobs.save(job);
        monitor.check(t.plusSeconds(125));
        assertThat(types()).containsExactly(NotificationService.Types.STREAM_RECOVERED, NotificationService.Types.STREAM_DOWN);
    }

    @Test
    void severalWebInstancesAlertOnce() {
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        liveJob(StreamStates.RECONNECTING, t);
        AlertMonitor other = new AlertMonitor(jobs, incidents, streams, users, plans, notificationService,
                notifications, "web");
        monitor.check(t);
        other.check(t);
        monitor.check(t.plusSeconds(61));
        other.check(t.plusSeconds(61));
        assertThat(types()).containsExactly(NotificationService.Types.STREAM_DOWN);
    }

    @Test
    void oneDestinationDownIsReportedWithoutTheWholeStream() {
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = liveJob(StreamStates.LIVE, t);
        StreamJobDestination d = job.getDestinations().getFirst();
        d.setStatus("RECONNECTING");
        d.setLastError("Connection reset by peer");
        job.setHeartbeatAt(t.plusSeconds(60));
        jobs.save(job);
        monitor.check(t);
        monitor.check(t.plusSeconds(61));
        List<Notification> list = notifications.findTop50ByUserIdOrderByCreatedAtDesc(owner.getId());
        assertThat(list).extracting(Notification::getType).containsExactly(NotificationService.Types.DESTINATION_DOWN);
        assertThat(list.getFirst().getTitle()).isEqualTo("Lofi radio isn't reaching YouTube");
    }

    @Test
    void aFailedRunAlertsAtOnceAndClosesItsIncidents() {
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = liveJob(StreamStates.RECONNECTING, t);
        monitor.check(t.minusSeconds(61));
        monitor.check(t);
        streamService.finalizeJob(job.getId(), "Stream kept failing", true);
        assertThat(types()).contains(NotificationService.Types.STREAM_FAILED);
        assertThat(incidents.findAllByJobIdAndResolvedAtIsNull(job.getId())).isEmpty();
    }

    @Test
    void aPhoneOutOfSignalForTwoMinutesAlertsButBrbHiccupsDont() {
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = liveJob(StreamStates.LIVE, t);
        job.setInputLive(false);
        job.setHeartbeatAt(t.plusSeconds(120));
        jobs.save(job);
        monitor.check(t);
        monitor.check(t.plusSeconds(90));
        assertThat(types()).isEmpty();
        monitor.check(t.plusSeconds(121));
        assertThat(types()).containsExactly(NotificationService.Types.PHONE_LOST);
    }

    @Autowired com.afklive.streamer.service.LiveStreamService liveStreams;
    @Autowired com.afklive.streamer.service.AdminService admin;

    @Test
    void aRunSlowerThanRealTimeForAMinuteAlertsOnceWithTheFix() {
        // RE-10, production 24 Oct 2026: 1080p60 ran at 0.8x for hours and nothing told anyone.
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        owner.setPlanType(PlanType.AGENCY);
        owner = users.save(owner);
        LiveStream s = liveStreams.create(owner.getEmail(), new com.afklive.streamer.dto.StreamDtos.CreateStream("Night drive", null, null, 1080, 60));
        StreamJob job = liveJob(StreamStates.LIVE, t);
        job.setStreamId(s.getId());
        job.setSpeed(0.9); // a dip that clears within the minute isn't reported
        job.setHeartbeatAt(t.plusSeconds(600)); // the worker keeps reporting throughout
        jobs.save(job);
        monitor.check(t);
        setSpeed(job, 1.0);
        monitor.check(t.plusSeconds(30));
        setSpeed(job, 0.8);
        monitor.check(t.plusSeconds(40));
        monitor.check(t.plusSeconds(90));
        assertThat(types()).isEmpty();

        monitor.check(t.plusSeconds(101)); // slow for 61 s
        List<Notification> list = notifications.findTop50ByUserIdOrderByCreatedAtDesc(owner.getId());
        assertThat(list).extracting(Notification::getType).containsExactly(NotificationService.Types.STREAM_SLOW);
        assertThat(list.getFirst().getTitle()).isEqualTo("Night drive is running slower than real time");
        assertThat(list.getFirst().getBody()).contains("1080p 60 fps", "0.80x", "play slowly", "720p 30 fps");
        assertThat(list.getFirst().getLink()).isEqualTo("/streams/" + s.getId() + "/settings");
        monitor.check(t.plusSeconds(130));
        assertThat(types()).hasSize(1); // not repeated while it stays slow

        // Recorded where creators and admins look: the stream's live state and the admin console.
        assertThat(liveStreams.view(owner.getEmail(), s.getId()).live().slowSince().toInstant()).isEqualTo(t.plusSeconds(40).toInstant());
        assertThat(admin.liveStreams(owner.getEmail(), null, null)).singleElement()
                .satisfies(r -> assertThat(r.problem()).contains("Slower than real time (0.80x)", "720p 30 fps"));

        // Back to real time: the all-clear in the app (no email), and the incident closes.
        setSpeed(job, 1.0);
        monitor.check(t.plusSeconds(160));
        assertThat(types()).containsExactly(NotificationService.Types.STREAM_SPEED_OK, NotificationService.Types.STREAM_SLOW);
        assertThat(incidents.findAllByJobIdAndResolvedAtIsNull(job.getId())).isEmpty();
        assertThat(liveStreams.view(owner.getEmail(), s.getId()).live().slowSince()).isNull();

        // Slow again within hours: back on the record, but the creator isn't emailed twice.
        setSpeed(job, 0.8);
        monitor.check(t.plusSeconds(200));
        monitor.check(t.plusSeconds(270));
        assertThat(incidents.findAllByJobIdAndResolvedAtIsNull(job.getId())).extracting(StreamIncident::getKind).containsExactly("SLOW");
        assertThat(types()).filteredOn(NotificationService.Types.STREAM_SLOW::equals).hasSize(1);
    }

    private void setSpeed(StreamJob job, double speed) {
        StreamJob j = jobs.findById(job.getId()).orElseThrow();
        j.setSpeed(speed);
        jobs.save(j);
    }

    @Test
    void aFileTheWorkerSkippedIsReportedOnceADay() {
        // PL-7: the worker lists skipped files with its heartbeat; the creator hears about each once.
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = liveJob(StreamStates.LIVE, t);
        job.setNowPlaying("{\"title\":\"rain.mp4\",\"failed\":[{\"mediaId\":42,\"title\":\"broken.mov\",\"reason\":\"Invalid data\"}]}");
        jobs.save(job);
        monitor.check(t);
        monitor.check(t.plusSeconds(30));
        assertThat(types()).containsExactly(NotificationService.Types.ITEM_FAILED);
        assertThat(notifications.findTop50ByUserIdOrderByCreatedAtDesc(owner.getId()).getFirst().getTitle()).isEqualTo("broken.mov was skipped");
    }

    @Test
    void alertPreferencesPerKind() throws Exception {
        // NT-3.
        mvc.perform(get("/api/notifications/preferences").with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("STREAM"))
                .andExpect(jsonPath("$[0].email").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/notifications/preferences")
                        .with(user(owner.getEmail()).roles("USER")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"PHONE\":{\"email\":false,\"push\":true},\"NOPE\":{\"email\":false,\"push\":false}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='PHONE')].email").value(false));
        assertThat(users.findById(owner.getId()).orElseThrow().getAlertPreferences()).contains("PHONE").doesNotContain("NOPE");
    }

    @Test
    void repeatsAreSuppressedWithinTheirWindow() {
        NotificationService.Alert a = new NotificationService.Alert(NotificationService.Types.STORAGE_ALMOST_FULL,
                Notification.Level.WARNING, null, "Your library is 85% full", "Free space.", "/library", "storage",
                java.time.Duration.ofDays(1), true);
        assertThat(notificationService.notify(owner, a)).isPresent();
        assertThat(notificationService.notify(owner, a)).isEmpty();
    }

    @Test
    void theAlertListApi() throws Exception {
        notificationService.notify(owner, new NotificationService.Alert("TEST", Notification.Level.INFO, null, "Hello", "Body",
                null, null, null, false));
        mvc.perform(get("/api/notifications").with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.emailAlerts").value(true))
                .andExpect(jsonPath("$.items[0].title").value("Hello"));
        mvc.perform(post("/api/notifications/read-all").with(user(owner.getEmail()).roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/notifications/settings").with(user(owner.getEmail()).roles("USER")).with(csrf())
                        .contentType("application/json").content("{\"emailAlerts\":false}"))
                .andExpect(jsonPath("$.emailAlerts").value(false));
        mvc.perform(get("/api/notifications").with(user(owner.getEmail()).roles("USER")))
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.emailAlerts").value(false));
    }
}
