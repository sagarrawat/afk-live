package com.afklive.streamer;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.ScheduledStream;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.ScheduledStreamRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.StreamService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class StreamLifecycleIntegrationTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired MediaAssetRepository assets;
    @Autowired StreamDestinationRepository destinations;
    @Autowired ScheduledStreamRepository scheduled;
    @Autowired StreamService streamService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private User account(String email) {
        return users.findByEmail(email).orElseGet(() -> {
            User u = new User(email);
            u.setPlanType(PlanType.FREE);
            return users.save(u);
        });
    }

    private StreamJob liveJob(User owner) {
        StreamJob job = new StreamJob(owner, "key", "video.mp4", null, null, true, 999_999,
                "t", "d", "private", ZonedDateTime.now(ZoneId.of("UTC")).minusHours(2), "YouTube", false);
        job.setLastBillingTime(ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(30));
        return jobs.save(job);
    }

    @Test
    void jobsLeftLiveByAPreviousRunAreClosedOnStartupWithoutBillingDowntime() {
        User owner = account("restart@example.com");
        StreamJob stale = liveJob(owner);
        ZonedDateTime lastBilled = stale.getLastBillingTime();

        streamService.reconcileStaleJobs();

        StreamJob after = jobs.findById(stale.getId()).orElseThrow();
        assertThat(after.isLive()).isFalse();
        assertThat(after.getEndReason()).isEqualTo("Server restarted");
        // Ended at the last billed moment, not "now": downtime isn't charged.
        assertThat(java.time.Duration.between(lastBilled.toInstant(), after.getEndTime().toInstant()).abs().toMillis()).isLessThan(1);
        assertThat(users.findByEmail("restart@example.com").orElseThrow().getUnpaidBalance()).isZero();
    }

    @Test
    void stoppingAJobThisServerDidNotStartNeverSignalsItsPid() {
        User owner = account("pid@example.com");
        StreamJob stale = liveJob(owner); // pid 999999 is not ours

        streamService.stopStream(stale.getId(), "pid@example.com");

        StreamJob after = jobs.findById(stale.getId()).orElseThrow();
        assertThat(after.isLive()).isFalse();
        assertThat(after.getEndReason()).isEqualTo("Stopped by user");
    }

    @Test
    void stopAtMustBeInTheFutureAndOnlyForYourOwnLiveStream() {
        User owner = account("stopat@example.com");
        StreamJob job = liveJob(owner);
        ZonedDateTime later = ZonedDateTime.now().plusHours(1);

        streamService.setStopAt(job.getId(), "stopat@example.com", later);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStopAt()).isNotNull();

        assertThatThrownBy(() -> streamService.setStopAt(job.getId(), "someone@example.com", later))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> streamService.setStopAt(job.getId(), "stopat@example.com", ZonedDateTime.now().minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scheduledStreamsMustUseYourOwnVideoAndDestinations() throws Exception {
        User owner = account("owner@example.com");
        User other = account("other@example.com");
        MediaAsset othersVideo = assets.save(new MediaAsset(other, "theirs.mp4", "key-theirs", 10L, MediaAsset.ContentType.VIDEO));
        MediaAsset myVideo = assets.save(new MediaAsset(owner, "mine.mp4", "key-mine", 10L, MediaAsset.ContentType.VIDEO));
        StreamDestination othersDest = destinations.save(new StreamDestination("Theirs", "their-key", "RTMP", other));
        StreamDestination myDest = destinations.save(new StreamDestination("Mine", "my-key", "RTMP", owner));
        String when = ZonedDateTime.now().plusHours(2).toOffsetDateTime().toString();

        mvc.perform(post("/api/stream/schedule").with(user("owner@example.com").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoKey\":\"key-theirs\",\"destinationIds\":[" + myDest.getId() + "],\"scheduledTime\":\"" + when + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/stream/schedule").with(user("owner@example.com").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoKey\":\"key-mine\",\"destinationIds\":[" + othersDest.getId() + "],\"scheduledTime\":\"" + when + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/stream/schedule").with(user("owner@example.com").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoKey\":\"key-mine\",\"destinationIds\":[" + myDest.getId() + "],\"scheduledTime\":\"" + when + "\"}"))
                .andExpect(status().isOk());

        ScheduledStream saved = scheduled.findByUserEmail("owner@example.com").get(0);
        assertThat(saved.getVideoKey()).isEqualTo("key-mine");
        assertThat(saved.getDestinations()).extracting(StreamDestination::getStreamKey).containsExactly("my-key");
        assertThat(saved.getStatus()).isEqualTo(ScheduledStream.StreamStatus.PENDING);
        assertThat(othersVideo.getId()).isNotNull();
    }

    @Test
    void aScheduledStreamIsClaimedOnlyOnce() {
        User owner = account("claim@example.com");
        ScheduledStream s = new ScheduledStream();
        s.setUser(owner);
        s.setScheduledTime(ZonedDateTime.now().minusMinutes(1));
        s.setStatus(ScheduledStream.StreamStatus.PENDING);
        s = scheduled.save(s);

        assertThat(scheduled.claim(s.getId())).isEqualTo(1);
        assertThat(scheduled.claim(s.getId())).isZero();
    }
}
