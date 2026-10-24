package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.DestinationTestService;
import com.afklive.streamer.service.LiveStreamService;
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
 * RE-8 ("Live" means the platform is receiving), RE-9 (the timeline) and GL-1 (Go live tests the
 * destinations first), on the web side. The worker side is in StreamRunnerTest and StreamWorkerIntegrationTest.
 */
@SpringBootTest
class TruthOfLiveTest extends PostgresIntegrationTest {

    static final String YOUTUBE_KEY = "abcd-efgh-ijkl-mnop";
    static final String TWITCH = "rtmp://live.twitch.tv/app/live_123_abc";

    @Autowired LiveStreamService service;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamService streamService;
    @Autowired DestinationTestRepository tests;
    @Autowired StreamEventRepository events;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "tol-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.PRO);
        users.save(u);
        com.afklive.streamer.util.PublicDnsForTests.on(); // the Twitch address must pass Go live's checks offline
    }

    /** Runs started here must not be left for the worker tests (same database) to pick up. */
    @AfterEach
    void endRuns() {
        com.afklive.streamer.util.PublicDnsForTests.off();
        // Nor its pending destination tests: another test's worker would claim them first.
        Long userId = users.findByEmail(email).orElseThrow().getId();
        tests.deleteAll(tests.findAll().stream().filter(t -> userId.equals(t.getUserId())).toList());
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("tol-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private StreamDestination destination(String name, String key) {
        StreamDestination d = new StreamDestination();
        d.setName(name);
        d.setStreamKey(key);
        d.setType("RTMP");
        d.setUser(users.findByEmail(email).orElseThrow());
        return destinations.save(d);
    }

    private LiveStream stream(String name, StreamDestination... ds) {
        LiveStream s = service.create(email, new CreateStream(name, null, null, 720, 30));
        MediaAsset v = new MediaAsset(users.findByEmail(email).orElseThrow(), "rain.mp4", "key/" + UUID.randomUUID() + ".mp4", 100L,
                MediaAsset.ContentType.VIDEO);
        v.setDurationSeconds(60.0);
        v = media.save(v);
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v.getId()));
        service.setTargets(email, s.getId(), java.util.Arrays.stream(ds).map(d -> new TargetRequest(d.getId(), true)).toList());
        return s;
    }

    /** A run on air (the encoder is fine); each destination's reach as given, null leaves it unreported. */
    private StreamJob onAir(LiveStream s, String... reach) {
        StreamJob job = service.goLive(email, s.getId(), null);
        job = jobs.findById(job.getId()).orElseThrow();
        job.setStatus(StreamStates.LIVE);
        job.setWorkerId("w1");
        job.setHeartbeatAt(ZonedDateTime.now(ZoneId.of("UTC")));
        for (int i = 0; i < reach.length; i++) {
            StreamJobDestination d = job.getDestinations().get(i);
            d.setStatus("ON".equals(reach[i]) ? "LIVE" : "RECONNECTING");
            d.setReach(reach[i]);
            d.setReachReason("NOT_REACHING".equals(reach[i]) ? "av_interleaved_write_frame(): Broken pipe" : null);
        }
        return jobs.save(job);
    }

    private void setReach(StreamJob job, int position, String reach, String reason) {
        StreamJob j = jobs.findById(job.getId()).orElseThrow();
        StreamJobDestination d = j.getDestinations().get(position);
        d.setReach(reach);
        d.setReachReason(reason);
        jobs.save(j);
    }

    private LiveState live(LiveStream s) {
        return service.view(email, s.getId()).live();
    }

    @Test
    void theBadgeSaysWhetherPlatformsAreReceivingNotWhetherOurEncoderRuns() {
        LiveStream s = stream("Lofi radio", destination("Main channel", YOUTUBE_KEY), destination("Twitch", TWITCH));
        StreamJob job = onAir(s, "CONNECTING", "CONNECTING");
        assertThat(live(s).state()).isEqualTo("LIVE");
        assertThat(live(s).reach()).as("nothing confirmed yet").isEqualTo("CONNECTING");

        setReach(job, 0, "ON", null);
        LiveState l = live(s);
        assertThat(l.reach()).isEqualTo("ALL");
        assertThat(l.health().destinations()).extracting(DestinationHealth::message)
                .containsExactly("On YouTube", "Connecting to Twitch");
        assertThat(l.health().destinations()).extracting(DestinationHealth::platform).containsExactly("YouTube", "Twitch");

        setReach(job, 1, "NOT_REACHING", "[rtmp @ 0x55d0] av_interleaved_write_frame(): Broken pipe");
        l = live(s);
        assertThat(l.reach()).as("Live with problems").isEqualTo("SOME");
        DestinationHealth twitch = l.health().destinations().get(1);
        assertThat(twitch.reach()).isEqualTo("NOT_REACHING");
        assertThat(twitch.level()).isEqualTo("red");
        assertThat(twitch.message()).isEqualTo("Not reaching Twitch. Twitch refused the stream key. Copy the key again from Twitch and save it.");
        assertThat(l.health().overall()).isEqualTo("red");

        setReach(job, 0, "NOT_REACHING", "Connection refused");
        l = live(s);
        assertThat(l.reach()).as("Not reaching anyone").isEqualTo("NONE");
        assertThat(l.health().destinations().get(0).message())
                .isEqualTo("Not reaching YouTube. Couldn't reach the server. Check the server address.");
        // Never the key itself: messages and platform names only.
        assertThat(l.toString()).doesNotContain(YOUTUBE_KEY).doesNotContain("live_123_abc");
    }

    @Test
    void aServerAddressPastedAsTheKeyIsExplainedAsSuch() {
        // Production, October 2026: the app said "Live" while YouTube got nothing.
        LiveStream s = stream("Pasted", destination("YouTube", "rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2"));
        onAir(s, "NOT_REACHING");
        DestinationHealth d = live(s).health().destinations().getFirst();
        assertThat(live(s).reach()).isEqualTo("NONE");
        assertThat(d.message()).startsWith("Not reaching YouTube. This address has a second server address inside it");
    }

    @Test
    void aWorkerOfThePreviousReleaseStillGetsAnHonestBadge() {
        // Expand then contract: the old worker writes no reach; its LIVE / RECONNECTING with an error stand in.
        LiveStream s = stream("Old worker", destination("YouTube", YOUTUBE_KEY));
        StreamJob job = onAir(s);
        StreamJob j = jobs.findById(job.getId()).orElseThrow();
        j.getDestinations().getFirst().setStatus("RECONNECTING");
        j.getDestinations().getFirst().setLastError("Connection reset by peer");
        jobs.save(j);
        assertThat(live(s).reach()).isEqualTo("NONE");
        j = jobs.findById(job.getId()).orElseThrow();
        j.getDestinations().getFirst().setStatus("LIVE");
        j.getDestinations().getFirst().setLastError(null);
        jobs.save(j);
        assertThat(live(s).reach()).isEqualTo("ALL");
    }

    @Test
    void theTimelineListsDestinationEventsNewestFirstWithTheirLevel() throws Exception {
        LiveStream s = stream("Timeline", destination("YouTube", YOUTUBE_KEY));
        StreamJob job = onAir(s, "ON");
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("UTC"));
        events.save(new StreamEvent(job.getId(), t, StreamEvent.Kinds.REACHED, 0, "YouTube", "On YouTube."));
        StreamEvent loop = events.save(new StreamEvent(job.getId(), t.plusSeconds(60), StreamEvent.Kinds.RECONNECTING, 0, "YouTube",
                "Reconnecting to YouTube. YouTube refused the stream key."));
        events.bump(loop.getId(), t.plusSeconds(70));
        events.save(new StreamEvent(job.getId(), t.plusSeconds(90), StreamEvent.Kinds.RECOVERED, 0, "YouTube", "Back on YouTube after 30 s."));

        List<TimelineEvent> list = service.timeline(email, s.getId());
        assertThat(list).extracting(TimelineEvent::kind).containsExactly("RECOVERED", "RECONNECTING", "REACHED");
        assertThat(list).extracting(TimelineEvent::level).containsExactly("green", "amber", "green");
        assertThat(list.get(1).repeats()).isEqualTo(2);

        mvc.perform(get("/api/streams/" + s.getId() + "/timeline").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Back on YouTube after 30 s."));
    }

    // ------------------------------------------------------------------ GL-1

    private DestinationTest test(StreamDestination d, String url, DestinationTest.Status status, String message, ZonedDateTime finished) {
        DestinationTest t = new DestinationTest();
        t.setUserId(users.findByEmail(email).orElseThrow().getId());
        t.setDestinationId(d.getId());
        t.setUrl(url);
        t.setStatus(status);
        t.setMessage(message);
        t.setCreatedAt(finished == null ? ZonedDateTime.now(ZoneId.of("UTC")) : finished.minusSeconds(12));
        t.setFinishedAt(finished);
        return tests.save(t);
    }

    private long testCount(StreamDestination d) {
        return tests.findTop10ByDestinationIdOrderByIdDesc(d.getId()).size();
    }

    @Test
    void aDestinationThatPassedRecentlyWithTheSameKeyGoesLiveWithoutWaiting() {
        StreamDestination yt = destination("YouTube", YOUTUBE_KEY);
        LiveStream s = stream("Fresh", yt);
        test(yt, YOUTUBE_KEY, DestinationTest.Status.OK, "Connected", ZonedDateTime.now(ZoneId.of("UTC")).minusHours(3));

        DestinationChecks c = service.destinationChecks(email, s.getId(), true);
        assertThat(c.ready()).isTrue();
        assertThat(c.checks()).extracting(DestinationTestService.Check::state).containsExactly("PASSED");
        assertThat(testCount(yt)).as("no new test").isEqualTo(1);
    }

    @Test
    void anUntestedChangedOrStaleDestinationIsTestedBeforeGoingLive() {
        StreamDestination yt = destination("YouTube", YOUTUBE_KEY);
        StreamDestination tw = destination("Twitch", TWITCH);
        LiveStream s = stream("Untested", yt, tw);
        // YouTube passed, but with the key it had before; Twitch passed two days ago.
        test(yt, "old-key-1234", DestinationTest.Status.OK, "Connected", ZonedDateTime.now(ZoneId.of("UTC")).minusHours(1));
        test(tw, TWITCH, DestinationTest.Status.OK, "Connected", ZonedDateTime.now(ZoneId.of("UTC")).minusDays(2));

        DestinationChecks c = service.destinationChecks(email, s.getId(), true);
        assertThat(c.ready()).isFalse();
        assertThat(c.checks()).extracting(DestinationTestService.Check::state).containsExactly("TESTING", "TESTING");
        assertThat(c.checks().getFirst().message()).isEqualTo("Sending a 10-second test to YouTube…");
        assertThat(tests.findTop10ByDestinationIdOrderByIdDesc(yt.getId()).getFirst().getStatus()).isEqualTo(DestinationTest.Status.PENDING);

        // Pressing Go live again while they run reuses the tests.
        service.destinationChecks(email, s.getId(), true);
        assertThat(testCount(yt)).isEqualTo(2);
        assertThat(testCount(tw)).isEqualTo(2);
    }

    @Test
    void aFailedTestBlocksWithItsReasonUntilRetried() throws Exception {
        StreamDestination yt = destination("YouTube", YOUTUBE_KEY);
        LiveStream s = stream("Failing", yt);
        test(yt, YOUTUBE_KEY, DestinationTest.Status.FAILED, "YouTube refused the stream key. Copy the key again from YouTube and save it.",
                ZonedDateTime.now(ZoneId.of("UTC")).minusSeconds(10));

        mvc.perform(post("/api/streams/" + s.getId() + "/destination-checks").with(user(email)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.checks[0].state").value("FAILED"))
                .andExpect(jsonPath("$.checks[0].platform").value("YouTube"))
                .andExpect(jsonPath("$.checks[0].message").value("YouTube refused the stream key. Copy the key again from YouTube and save it."));
        assertThat(testCount(yt)).as("shown again, not re-run, right after it failed").isEqualTo(1);
        mvc.perform(get("/api/streams/" + s.getId() + "/destination-checks").with(user(email)))
                .andExpect(jsonPath("$.checks[0].state").value("FAILED"));
    }

    @Test
    void aKeyThatALiveRunUsesIsNeverTested() {
        StreamDestination yt = destination("YouTube", YOUTUBE_KEY);
        LiveStream first = stream("On air", yt);
        LiveStream second = stream("Same key", yt);
        onAir(first, "ON");

        DestinationChecks same = service.destinationChecks(email, first.getId(), true);
        assertThat(same.checks()).extracting(DestinationTestService.Check::state).containsExactly("SKIPPED");
        assertThat(same.ready()).isTrue();

        DestinationChecks other = service.destinationChecks(email, second.getId(), true);
        assertThat(other.ready()).isFalse();
        assertThat(other.checks().getFirst().state()).isEqualTo("FAILED");
        assertThat(other.checks().getFirst().message()).contains("Another of your streams is live on this YouTube key");
        assertThat(testCount(yt)).as("no test touched the key on air").isZero();
    }
}
