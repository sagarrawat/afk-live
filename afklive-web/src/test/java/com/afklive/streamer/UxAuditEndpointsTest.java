package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.dto.StreamDtos.StopRequest;
import com.afklive.streamer.dto.StreamDtos.TargetRequest;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamCommand;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamCommandRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UsageRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.BillingService;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The endpoints behind the first UX audit fixes (DS-8, GL-3, BI-1). */
@SpringBootTest
class UxAuditEndpointsTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamCommandRepository commands;
    @Autowired MediaAssetRepository media;
    @Autowired UsageRepository usage;
    @Autowired LiveStreamService service;
    @Autowired StreamService streamService;

    MockMvc mvc;
    String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "ux-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
    }

    /** Runs started here must not be left for the worker tests (same database) to pick up. */
    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("ux-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private StreamDestination destination(String name, String key) {
        StreamDestination d = new StreamDestination(name, key, "RTMP", users.findByEmail(email).orElseThrow());
        return destinations.save(d);
    }

    private LiveStream ready(StreamDestination d) {
        LiveStream s = service.create(email, new CreateStream("Lofi 24/7", null, null, 720, 30));
        User u = users.findByEmail(email).orElseThrow();
        MediaAsset v = new MediaAsset(u, "rain.mp4", "key/" + UUID.randomUUID() + ".mp4", 100L, MediaAsset.ContentType.VIDEO);
        v.setDurationSeconds(60.0);
        v.setHasAudio(true);
        media.save(v);
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v.getId()));
        service.setTargets(email, s.getId(), List.of(new TargetRequest(d.getId(), true)));
        return s;
    }

    @Test
    void aDestinationALiveStreamSendsToCantBeDeleted() throws Exception {
        // Before: DELETE removed it (and the cascade unlinked it from the stream) while the run sent to it.
        StreamDestination yt = destination("Main channel", "abcd-efgh-ijkl");
        StreamDestination spare = destination("Spare", "spare-key-1234");
        LiveStream s = ready(yt);
        StreamJob job = service.goLive(email, s.getId(), null);

        mvc.perform(get("/api/destinations").with(user(email).roles("USER")))
                .andExpect(jsonPath("$[?(@.name == 'Main channel')].liveIn[0]").value("Lofi 24/7"))
                .andExpect(jsonPath("$[?(@.name == 'Spare')].liveIn.length()").value(0));
        mvc.perform(delete("/api/destinations/" + yt.getId()).with(user(email).roles("USER")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Main channel is sending to a live stream (Lofi 24/7). Stop the stream or turn this destination off for it first."));
        assertThat(destinations.findById(yt.getId())).isPresent();

        // One that isn't in use can go; so can this one once the stream has stopped.
        mvc.perform(delete("/api/destinations/" + spare.getId()).with(user(email).roles("USER")).with(csrf())).andExpect(status().isOk());
        streamService.finalizeJob(job.getId(), "Stopped by user");
        mvc.perform(delete("/api/destinations/" + yt.getId()).with(user(email).roles("USER")).with(csrf())).andExpect(status().isOk());
        assertThat(destinations.findById(yt.getId())).isEmpty();
    }

    @Test
    void anotherAccountsDestinationIsNotFound() throws Exception {
        StreamDestination yt = destination("Main channel", "abcd-efgh-ijkl");
        mvc.perform(delete("/api/destinations/" + yt.getId()).with(user("someone-else@example.com").roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(destinations.findById(yt.getId())).isPresent();
    }

    @Test
    void editingKeepsTheSavedKeyWhenNoneIsGivenAndALiveRunKeepsItsAddress() throws Exception {
        StreamDestination yt = destination("Main channel", "abcd-efgh-ijkl");
        LiveStream s = ready(yt);
        StreamJob job = service.goLive(email, s.getId(), null);

        // Rename only: the key box is write-only, empty means keep.
        mvc.perform(put("/api/destinations/" + yt.getId()).with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bhajans channel\",\"key\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bhajans channel"))
                .andExpect(jsonPath("$.keyHint").value("••••ijkl"))
                .andExpect(jsonPath("$.liveIn[0]").value("Lofi 24/7"));
        assertThat(destinations.findById(yt.getId()).orElseThrow().getStreamKey()).isEqualTo("abcd-efgh-ijkl");

        // A new key: saved, never echoed back whole; the run on air keeps the address it started with.
        mvc.perform(put("/api/destinations/" + yt.getId()).with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"new-key-9876\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyHint").value("••••9876"))
                .andExpect(jsonPath("$.liveIn[0]").value("Lofi 24/7"))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("new-key-9876"));
        List<StreamSpec.Destination> sending = StreamSpec.fromJson(jobs.findById(job.getId()).orElseThrow().getSpec()).destinations();
        assertThat(sending).extracting(StreamSpec.Destination::url).containsExactly("abcd-efgh-ijkl");
    }

    @Test
    void stopAfterThePassIsShownAndCanBeTakenBack() throws Exception {
        StreamDestination yt = destination("Main channel", "abcd-efgh-ijkl");
        LiveStream s = ready(yt);
        StreamJob job = service.goLive(email, s.getId(), null);
        mvc.perform(get("/api/streams/" + s.getId()).with(user(email).roles("USER")))
                .andExpect(jsonPath("$.live.finishAfterPass").value(false));

        service.stop(email, s.getId(), new StopRequest("AFTER_PLAYLIST", null, null));
        mvc.perform(get("/api/streams/" + s.getId()).with(user(email).roles("USER")))
                .andExpect(jsonPath("$.live.finishAfterPass").value(true));
        mvc.perform(get("/api/streams").with(user(email).roles("USER")))
                .andExpect(jsonPath("$[0].live.finishAfterPass").value(true));

        mvc.perform(delete("/api/streams/" + s.getId() + "/finish-after-pass").with(user(email).roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishAfterPass").value(false))
                .andExpect(jsonPath("$.state").value(org.hamcrest.Matchers.oneOf("STARTING", "LIVE")));
        // A worker that restarts or takes over keeps going, and the running studio gets the playlist again
        // (PlaylistFeed.setItems resets the pass cap); nothing restarts.
        StreamJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.isLive()).isTrue();
        assertThat(StreamSpec.fromJson(after.getSpec()).studio().finishAfterPass()).isFalse();
        List<StreamCommand> sent = commands.findNew(List.of(job.getId()), 0);
        assertThat(sent).extracting(StreamCommand::getType).containsExactly(StreamCommand.Type.FINISH_PASS, StreamCommand.Type.RELOAD);
        assertThat(StudioSpec.parse(sent.get(1).getPayload(), StudioSpec.class).finishAfterPass()).isFalse();

        // Taking it back again changes nothing.
        mvc.perform(delete("/api/streams/" + s.getId() + "/finish-after-pass").with(user(email).roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(commands.findNew(List.of(job.getId()), 0)).hasSize(2);
    }

    @Test
    void aStreamThatIsntLiveHasNothingToTakeBack() throws Exception {
        StreamDestination yt = destination("Main channel", "abcd-efgh-ijkl");
        LiveStream s = ready(yt);
        mvc.perform(delete("/api/streams/" + s.getId() + "/finish-after-pass").with(user(email).roles("USER")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("isn't live")));
    }

    @Test
    void meSaysHowManyHoursWereStreamedThisMonth() throws Exception {
        User u = users.findByEmail(email).orElseThrow();
        u.setPlanType(PlanType.FREE);
        users.save(u);
        usage.add(u.getId(), BillingService.month(ZonedDateTime.now(ZoneId.of("UTC"))), 6 * 3600 + 1800);
        mvc.perform(get("/api/me").with(user(email).roles("USER")))
                .andExpect(jsonPath("$.hoursUsed").value(6.5))
                .andExpect(jsonPath("$.plan.monthlyHours").value(10));
    }
}
