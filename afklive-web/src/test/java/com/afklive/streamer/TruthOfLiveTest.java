package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.AlertMonitor;
import com.afklive.streamer.service.DestinationTestService;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.NotificationService;
import com.afklive.streamer.service.StreamHealth;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.streaming.StreamStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RE-8 (live means the platform is receiving), RE-1/RE-3 (destination timeline and alerts) and GL-1
 * (Go Live tests destinations that haven't passed recently), on the web side.
 */
@SpringBootTest
class TruthOfLiveTest extends PostgresIntegrationTest {

    @Autowired LiveStreamService service;
    @Autowired AlertMonitor monitor;
    @Autowired DestinationTestService destinationTests;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamIncidentRepository incidents;
    @Autowired NotificationRepository notifications;
    @Autowired DestinationTestRepository tests;
    @Autowired StreamEventRepository events;
    @Autowired StreamService streamService;
    @Autowired WebApplicationContext context;

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("truth-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private String newUser() {
        String email = "truth-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
        return email;
    }

    private StreamDestination destination(String email, String name, String key) {
        StreamDestination d = new StreamDestination();
        d.setName(name);
        d.setStreamKey(key);
        d.setType("RTMP");
        d.setUser(users.findByEmail(email).orElseThrow());
        return destinations.save(d);
    }

    /** A stream with a video and the given destinations turned on. */
    private LiveStream stream(String email, StreamDestination... ds) {
        LiveStream s = service.create(email, new CreateStream("Lofi 24/7", null, null, 720, 30));
        MediaAsset v = new MediaAsset(users.findByEmail(email).orElseThrow(), "rain.mp4", "key/" + UUID.randomUUID() + ".mp4", 100L,
                MediaAsset.ContentType.VIDEO);
        v.setDurationSeconds(60.0);
        v.setHasAudio(true);
        v = media.save(v);
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v.getId()));
        service.setTargets(email, s.getId(), java.util.Arrays.stream(ds).map(d -> new TargetRequest(d.getId(), true)).toList());
        return s;
    }

    /** Puts the run's destinations in the given states, as the worker reports them. */
    private StreamJob onAir(StreamJob job, ZonedDateTime now, String... states) {
        job = jobs.findById(job.getId()).orElseThrow();
        job.setStatus(StreamStates.LIVE);
        job.setHeartbeatAt(now.plusMinutes(10)); // the worker keeps reporting through the test clock
        for (int i = 0; i < states.length; i++) {
            StreamJobDestination d = job.getDestinations().get(i);
            String[] parts = states[i].split(":", 2);
            d.setStatus(parts[0]);
            d.setLastError(parts.length > 1 ? parts[1] : null);
            d.setUpdatedAt(now);
        }
        return jobs.save(job);
    }

    private List<String> types(String email) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(users.findByEmail(email).orElseThrow().getId())
                .stream().map(Notification::getType).toList();
    }

    // ------------------------------------------------------------------ RE-8

    @Test
    void theBadgeSaysLiveOnlyWhenPlatformsReceiveTheStream() {
        String email = newUser();
        StreamDestination yt = destination(email, "My channel", "abcd-efgh-ijkl-mnop");
        StreamDestination tw = destination(email, "Twitch", "rtmp://8.8.4.4/app/live_123");
        LiveStream s = stream(email, yt, tw);
        StreamJob job = service.goLive(email, s.getId(), null);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));

        onAir(job, now, "CONNECTING", "CONNECTING");
        assertThat(service.view(email, s.getId()).live().reach()).isEqualTo(StreamHealth.REACH_CONNECTING);

        onAir(job, now, "LIVE", "LIVE");
        LiveState live = service.view(email, s.getId()).live();
        assertThat(live.state()).isEqualTo("LIVE");
        assertThat(live.reach()).isEqualTo(StreamHealth.REACH_ALL);
        assertThat(live.health().destinations()).extracting(DestinationHealth::message).containsExactly("On YouTube", "On Twitch");

        onAir(job, now, "LIVE", "RECONNECTING:Error opening output rtmp://8.8.4.4/app/live_123: Input/output error");
        live = service.view(email, s.getId()).live();
        assertThat(live.reach()).isEqualTo(StreamHealth.REACH_SOME);
        assertThat(live.health().destinations().get(1).reach()).isEqualTo(StreamHealth.DOWN);
        assertThat(live.health().destinations().get(1).message()).startsWith("Not reaching Twitch.").contains("refused the stream key");

        // The production case: never plain "Live" when every destination is failing.
        onAir(job, now, "RECONNECTING:Error opening output: Input/output error", "RECONNECTING:Connection refused");
        live = service.view(email, s.getId()).live();
        assertThat(live.state()).isEqualTo("LIVE");
        assertThat(live.reach()).isEqualTo(StreamHealth.REACH_NONE);
        assertThat(service.list(email).getFirst().live().reach()).isEqualTo(StreamHealth.REACH_NONE);
        // A first attempt that never gets there counts as down after the grace period.
        onAir(job, now.minusMinutes(1), "CONNECTING", "CONNECTING");
        assertThat(service.view(email, s.getId()).live().reach()).isEqualTo(StreamHealth.REACH_NONE);
    }

    @Test
    void theServerAddressPastedAsTheKeyIsExplained() {
        String email = newUser();
        StreamDestination yt = destination(email, "YouTube", "rtmp://8.8.8.8/live2");
        LiveStream s = stream(email, yt);
        StreamJob job = service.goLive(email, s.getId(), null);
        onAir(job, ZonedDateTime.now(ZoneId.of("UTC")), "RECONNECTING:Error opening output rtmp://8.8.8.8/live2: Input/output error");
        DestinationHealth d = service.view(email, s.getId()).live().health().destinations().getFirst();
        assertThat(d.message()).contains("looks like a server address");
        assertThat(d.toString()).doesNotContain("rtmp://8.8.8.8/live2/");
    }

    @Test
    void nobodyReceivingForAMinuteIsAnOutageWithAnAlert() {
        String email = newUser();
        LiveStream s = stream(email, destination(email, "YouTube", "abcd-efgh-ijkl-mnop"));
        StreamJob job = service.goLive(email, s.getId(), null);
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        onAir(job, t, "RECONNECTING:Error opening output: Input/output error");

        monitor.check(t);
        monitor.check(t.plusSeconds(30));
        assertThat(types(email)).isEmpty();
        monitor.check(t.plusSeconds(61));
        assertThat(types(email)).containsExactly(NotificationService.Types.STREAM_DOWN);
        Notification n = notifications.findTop50ByUserIdOrderByCreatedAtDesc(users.findByEmail(email).orElseThrow().getId()).getFirst();
        assertThat(n.getTitle()).isEqualTo("Lofi 24/7 isn't reaching anyone");
        assertThat(n.getBody()).contains("YouTube: YouTube refused the stream key");
        assertThat(incidents.findAllByJobIdAndResolvedAtIsNull(job.getId())).extracting(StreamIncident::getKind).containsExactly("UNREACHED");
        monitor.check(t.plusSeconds(400));
        assertThat(types(email)).as("one alert per outage, and the single destination doesn't add its own")
                .containsExactly(NotificationService.Types.STREAM_DOWN);

        // It counts against uptime like any outage.
        Uptime up = service.uptime(email, s.getId(), t.plusSeconds(400));
        assertThat(up.outages()).hasSize(1);

        onAir(job, t.plusSeconds(410), "LIVE");
        monitor.check(t.plusSeconds(420));
        assertThat(types(email)).containsExactly(NotificationService.Types.STREAM_RECOVERED, NotificationService.Types.STREAM_DOWN);
        assertThat(incidents.findAllByJobIdAndResolvedAtIsNull(job.getId())).isEmpty();
    }

    @Test
    void aFlappingDestinationAlertsOnceAnHour() {
        String email = newUser();
        LiveStream s = stream(email, destination(email, "YouTube", "abcd-efgh-ijkl-mnop"),
                destination(email, "Twitch", "rtmp://8.8.4.4/app/live_123"));
        StreamJob job = service.goLive(email, s.getId(), null);
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        onAir(job, t, "LIVE", "RECONNECTING:Connection reset by peer");
        monitor.check(t);
        monitor.check(t.plusMinutes(5).plusSeconds(1));
        onAir(job, t.plusMinutes(6), "LIVE", "LIVE");
        monitor.check(t.plusMinutes(6));
        onAir(job, t.plusMinutes(7), "LIVE", "RECONNECTING:Connection reset by peer");
        monitor.check(t.plusMinutes(7));
        monitor.check(t.plusMinutes(13));
        assertThat(types(email)).containsExactly(NotificationService.Types.DESTINATION_RECOVERED, NotificationService.Types.DESTINATION_DOWN);
    }

    // ------------------------------------------------------------------ RE-1 timeline

    @Test
    void theTimelineShowsWhatHappenedToEachDestination() throws Exception {
        String email = newUser();
        LiveStream s = stream(email, destination(email, "YouTube", "abcd-efgh-ijkl-mnop"));
        StreamJob job = service.goLive(email, s.getId(), null);
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        events.save(new StreamEvent(job.getId(), t, StreamEvent.Kinds.DEST_FAILING, 0, "YouTube",
                "Not reaching YouTube. YouTube refused the stream key. Retrying."));
        StreamEvent later = events.save(new StreamEvent(job.getId(), t.plusSeconds(40), StreamEvent.Kinds.DEST_RECOVERED, 0, "YouTube",
                "Reaching YouTube again after 40 s."));
        events.bump(later.getId(), t.plusSeconds(41));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/streams/" + s.getId() + "/timeline").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("DEST_RECOVERED"))
                .andExpect(jsonPath("$[0].level").value("green"))
                .andExpect(jsonPath("$[0].repeats").value(2))
                .andExpect(jsonPath("$[1].level").value("red"))
                .andExpect(jsonPath("$[1].message").value("Not reaching YouTube. YouTube refused the stream key. Retrying."));
        String other = newUser();
        mvc.perform(get("/api/streams/" + s.getId() + "/timeline").with(user(other).roles("USER")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ GL-1

    private DestinationTest finishedTest(StreamDestination d, DestinationTest.Status status, String message, ZonedDateTime at) {
        DestinationTest t = new DestinationTest();
        t.setUserId(d.getUser().getId());
        t.setDestinationId(d.getId());
        t.setUrl(d.getStreamKey());
        t.setStatus(status);
        t.setMessage(message);
        t.setCreatedAt(at.minusSeconds(15));
        t.setStartedAt(at.minusSeconds(12));
        t.setFinishedAt(at);
        return tests.save(t);
    }

    @Test
    void goLiveDoesntWaitForDestinationsThatPassedToday() throws Exception {
        String email = newUser();
        StreamDestination yt = destination(email, "YouTube", "abcd-efgh-ijkl-mnop");
        LiveStream s = stream(email, yt);
        finishedTest(yt, DestinationTest.Status.OK, "ok", ZonedDateTime.now(ZoneId.of("UTC")).minusHours(3));
        long before = tests.count();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/api/streams/" + s.getId() + "/destination-checks").with(user(email).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.checks[0].state").value("PASSED"))
                .andExpect(jsonPath("$.checks[0].platform").value("YouTube"));
        assertThat(tests.count()).as("no new test").isEqualTo(before);
    }

    @Test
    void aChangedOrStaleKeyIsTestedAgainAndAFailureBlocksWithTheReason() {
        String email = newUser();
        User u = users.findByEmail(email).orElseThrow();
        StreamDestination yt = destination(email, "YouTube", "abcd-efgh-ijkl-mnop");
        LiveStream s = stream(email, yt);
        finishedTest(yt, DestinationTest.Status.OK, "ok", ZonedDateTime.now(ZoneId.of("UTC")).minusHours(1));
        yt.setStreamKey("wxyz-wxyz-wxyz-wxyz"); // changed since it passed
        destinations.save(yt);

        DestinationChecks first = service.destinationChecks(email, s.getId(), true);
        assertThat(first.ready()).isFalse();
        assertThat(first.checks().getFirst().state()).isEqualTo(DestinationTestService.TESTING);
        Long testId = first.checks().getFirst().testId();
        assertThat(tests.findById(testId).orElseThrow().getUrl()).isEqualTo("wxyz-wxyz-wxyz-wxyz");
        // Pressing Go Live again (or polling) reuses the running test.
        assertThat(service.destinationChecks(email, s.getId(), true).checks().getFirst().testId()).isEqualTo(testId);
        assertThat(service.destinationChecks(email, s.getId(), false).checks().getFirst().state()).isEqualTo(DestinationTestService.TESTING);

        // The worker reports a refusal: Go Live shows it, and doesn't re-run it straight away.
        tests.claim(testId, "w", ZonedDateTime.now(ZoneId.of("UTC")));
        tests.finish(testId, DestinationTest.Status.FAILED, "YouTube refused the stream key.", ZonedDateTime.now(ZoneId.of("UTC")));
        DestinationChecks failed = service.destinationChecks(email, s.getId(), true);
        assertThat(failed.ready()).isFalse();
        assertThat(failed.checks().getFirst().state()).isEqualTo(DestinationTestService.FAILED);
        assertThat(failed.checks().getFirst().message()).contains("refused");
        assertThat(failed.checks().getFirst().testId()).isEqualTo(testId);
        assertThat(u.getId()).isNotNull();
    }

    @Test
    void aTestNeverConnectsToAKeyThatIsLive() {
        String email = newUser();
        StreamDestination yt = destination(email, "YouTube", "abcd-efgh-ijkl-mnop");
        LiveStream s = stream(email, yt);
        User u = users.findByEmail(email).orElseThrow();
        // A Go Live check is waiting for a worker when the creator presses "Go live anyway".
        Long pending = service.destinationChecks(email, s.getId(), true).checks().getFirst().testId();
        // Another test of the same key was claimed just before the run started.
        Long running = service.destinationChecks(email, s.getId(), true).checks().getFirst().testId();
        assertThat(running).isEqualTo(pending);
        service.goLive(email, s.getId(), null);

        // The worker that starts the run cancels the pending test before connecting.
        assertThat(destinationTests.clearTestsFor(u.getId(), List.of("abcd-efgh-ijkl-mnop"), Duration.ofSeconds(1))).isTrue();
        DestinationTest t = tests.findById(pending).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(DestinationTest.Status.FAILED);
        assertThat(t.getMessage()).contains("went live");

        // A test that a worker claims anyway sees the live run and never connects.
        DestinationTest late = new DestinationTest();
        late.setUserId(u.getId());
        late.setDestinationId(yt.getId());
        late.setUrl("abcd-efgh-ijkl-mnop");
        late.setCreatedAt(ZonedDateTime.now(ZoneId.of("UTC")));
        late = tests.save(late);
        tests.claim(late.getId(), "w", ZonedDateTime.now(ZoneId.of("UTC")));
        long started = System.nanoTime();
        destinationTests.run(late.getId());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(tests.findById(late.getId()).orElseThrow().getMessage()).contains("went live");
        // And a test of another key isn't held up.
        assertThat(destinationTests.clearTestsFor(u.getId(), List.of("other-key"), Duration.ofSeconds(1))).isTrue();
    }
}
