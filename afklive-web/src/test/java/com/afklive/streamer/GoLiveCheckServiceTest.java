package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.GoLiveCheck;
import com.afklive.streamer.service.GoLiveCheckService;
import com.afklive.streamer.service.GoLiveCheckService.CheckView;
import com.afklive.streamer.service.GoLiveCheckService.FlagView;
import com.afklive.streamer.service.GoLiveCheckWeekly;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GR-9: the go-live check against the real database: flags, one-tap fixes, keeping a flag, and the weekly notice. */
@SpringBootTest
class GoLiveCheckServiceTest extends PostgresIntegrationTest {

    @Autowired GoLiveCheckService checks;
    @Autowired GoLiveCheckWeekly weekly;
    @Autowired LiveStreamService streams;
    @Autowired LiveStreamRepository streamRepo;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamJobRepository jobs;
    @Autowired EngagementActivityRepository engagement;
    @Autowired NotificationRepository notifications;
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String newUser() {
        String email = "glc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
        return email;
    }

    private MediaAsset asset(String email, MediaAsset.ContentType type, String title) {
        MediaAsset m = new MediaAsset(users.findByEmail(email).orElseThrow(), title, "key/" + UUID.randomUUID(), 100L, type);
        m.setDurationSeconds(60.0);
        m.setHasAudio(type != MediaAsset.ContentType.IMAGE);
        return media.save(m);
    }

    private LiveStream stream(String email, int videos, boolean stillVideos) {
        LiveStream s = streams.create(email, new CreateStream("Lofi radio", null, null, 720, 30));
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < videos; i++) {
            MediaAsset v = asset(email, MediaAsset.ContentType.VIDEO, "video" + i + ".mp4");
            media.setStillPicture(v.getId(), stillVideos);
            ids.add(v.getId());
        }
        streams.setItems(email, s.getId(), StreamItem.Kind.VIDEO, ids);
        return s;
    }

    private static List<String> open(CheckView v) {
        return v.flags().stream().filter(f -> !f.dismissed()).map(FlagView::id).toList();
    }

    @Test
    void oneStillVideoOnLoopIsFlaggedAndTheOverlayFixAppliesOnce() {
        String email = newUser();
        LiveStream s = stream(email, 1, true);

        CheckView v = checks.check(email, s.getId());
        assertThat(open(v)).containsExactly(GoLiveCheck.ONE_FILE_LOOP, GoLiveCheck.STILL_PICTURE);
        assertThat(v.musicNote()).contains("can't tell whether YouTube will claim");
        assertThat(v.checking()).isFalse();

        CheckView fixed = checks.fix(email, s.getId(), GoLiveCheck.STILL_PICTURE);
        assertThat(open(fixed)).containsExactly(GoLiveCheck.ONE_FILE_LOOP);
        checks.fix(email, s.getId(), GoLiveCheck.STILL_PICTURE); // a second tap doesn't add a second clock
        List<StudioSpec.OverlaySetting> overlays = streams.view(email, s.getId()).overlays();
        assertThat(overlays).extracting(StudioSpec.OverlaySetting::type).containsExactly("CLOCK", "TEXT");
        assertThat(overlays.get(1).text()).isEqualTo("Now playing: {title}");
    }

    @Test
    void videosNotYetMeasuredAreNeverGuessed() {
        String email = newUser();
        LiveStream s = streams.create(email, new CreateStream("Rain", null, null, 720, 30));
        MediaAsset v1 = asset(email, MediaAsset.ContentType.VIDEO, "a.mp4");
        MediaAsset v2 = asset(email, MediaAsset.ContentType.VIDEO, "b.mp4");
        media.setStillPicture(v1.getId(), true); // v2 not measured yet
        streams.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v1.getId(), v2.getId()));
        CheckView v = checks.check(email, s.getId());
        assertThat(open(v)).isEmpty();
        assertThat(v.checking()).isTrue();
    }

    @Test
    void aMisleadingLiveTitleGetsAnHonestOneAndKeepingItIsRemembered() {
        String email = newUser();
        LiveStream s = stream(email, 3, false);
        CheckView v = checks.setTitleAndThumbnail(email, s.getId(), "🔴 LIVE Lofi beats to study", null, false);
        assertThat(open(v)).containsExactly(GoLiveCheck.TITLE_LIVE);
        assertThat(v.flags().getFirst().suggestion()).isEqualTo("Lofi beats to study · 24/7 replay");

        // Keep it (the creator really is there), then change their mind.
        CheckView kept = checks.dismiss(email, s.getId(), GoLiveCheck.TITLE_LIVE, true);
        assertThat(open(kept)).isEmpty();
        assertThat(kept.flags()).singleElement().extracting(FlagView::dismissed).isEqualTo(true);
        assertThat(open(checks.dismiss(email, s.getId(), GoLiveCheck.TITLE_LIVE, false))).containsExactly(GoLiveCheck.TITLE_LIVE);

        CheckView fixed = checks.fix(email, s.getId(), GoLiveCheck.TITLE_LIVE);
        assertThat(fixed.youtubeTitle()).isEqualTo("Lofi beats to study · 24/7 replay");
        assertThat(open(fixed)).isEmpty();

        assertThatThrownBy(() -> checks.setTitleAndThumbnail(email, s.getId(), "x".repeat(101), null, false))
                .hasMessageContaining("100 characters");
        assertThatThrownBy(() -> checks.dismiss(email, s.getId(), "made-up", true)).hasMessageContaining("Unknown");
    }

    @Test
    void theThumbnailMustBeAPictureFromTheCreatorsOwnLibrary() {
        String email = newUser();
        String other = newUser();
        LiveStream s = stream(email, 3, false);
        MediaAsset theirs = asset(other, MediaAsset.ContentType.IMAGE, "theirs.png");
        MediaAsset video = asset(email, MediaAsset.ContentType.VIDEO, "clip.mp4");
        assertThatThrownBy(() -> checks.setTitleAndThumbnail(email, s.getId(), null, theirs.getId(), false))
                .hasMessageContaining("Pick a picture");
        assertThatThrownBy(() -> checks.setTitleAndThumbnail(email, s.getId(), null, video.getId(), false))
                .hasMessageContaining("Pick a picture");

        MediaAsset small = asset(email, MediaAsset.ContentType.IMAGE, "thumb.png");
        small.setWidth(480);
        small.setHeight(270);
        media.save(small);
        CheckView v = checks.setTitleAndThumbnail(email, s.getId(), null, small.getId(), false);
        assertThat(v.thumbnail().mediaId()).isEqualTo(small.getId());
        // The file isn't in storage here, so contrast can't be measured: size is still checked, contrast never guessed.
        assertThat(open(v)).containsExactly(GoLiveCheck.THUMB_SMALL);
        assertThat(checks.setTitleAndThumbnail(email, s.getId(), null, null, true).thumbnail()).isNull();
    }

    @Test
    void repeatedRepliesAreFlaggedAndTheFixStopsAutomaticReplies() {
        String email = newUser();
        LiveStream s = stream(email, 3, false);
        User u = users.findByEmail(email).orElseThrow();
        u.setAutoReplyEnabled(true);
        users.save(u);
        StreamJob job = new StreamJob(u, "k", null, null, null, true, 0, "Lofi radio", null, null,
                ZonedDateTime.now(ZoneId.of("UTC")), "YouTube", false);
        job.setStreamId(s.getId());
        job.setAutoReplyEnabled(true);
        job.setDesiredState("STOP"); // not for the worker tests sharing this database
        job = jobs.save(job);
        for (int i = 0; i < 3; i++) {
            EngagementActivity a = new EngagementActivity();
            a.setUser(u);
            a.setActionType("LIVE_REPLY");
            a.setContent("Thanks for watching!");
            a.setTimestamp(LocalDateTime.now().minusHours(i));
            engagement.save(a);
        }
        assertThat(open(checks.check(email, s.getId()))).containsExactly(GoLiveCheck.REPEATED_MESSAGE);

        checks.fix(email, s.getId(), GoLiveCheck.REPEATED_MESSAGE);
        assertThat(users.findByEmail(email).orElseThrow().isAutoReplyEnabled()).isFalse();
        StreamJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.isAutoReplyEnabled()).isFalse();
        assertThat(after.getDesiredState()).as("nothing else on the run changes").isEqualTo("STOP");
        jobs.delete(after);
    }

    @Test
    void theWeeklyCheckSendsOneQuietNoticePerStreamPerWeek() {
        String email = newUser();
        LiveStream flagged = stream(email, 1, true);
        LiveStream fine = stream(email, 4, false);
        LiveStream neverLive = stream(email, 1, true);
        User u = users.findByEmail(email).orElseThrow();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        for (LiveStream s : List.of(flagged, fine)) {
            StreamJob j = new StreamJob(u, "k", null, null, null, false, 0, s.getName(), null, null, now.minusDays(2), "YouTube", false);
            j.setStreamId(s.getId());
            j.setEndTime(now.minusDays(1));
            j.setStatus("ENDED");
            jobs.save(j);
        }

        weekly.run(now);
        weekly.run(now.plusHours(1)); // another hour, or another instance: no second notice
        List<Notification> sent = notifications.findTop50ByUserIdOrderByCreatedAtDesc(u.getId());
        assertThat(sent).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(GoLiveCheckWeekly.TYPE);
            assertThat(n.getStreamId()).isEqualTo(flagged.getId());
            assertThat(n.getTitle()).isEqualTo("Weekly check for Lofi radio: 2 things to look at");
            assertThat(n.getEmailedAt()).isNull();
        });

        // Both flags kept: next week there's nothing open, so nothing is sent.
        checks.dismiss(email, flagged.getId(), GoLiveCheck.ONE_FILE_LOOP, true);
        checks.dismiss(email, flagged.getId(), GoLiveCheck.STILL_PICTURE, true);
        weekly.run(now.plusDays(8));
        assertThat(notifications.findTop50ByUserIdOrderByCreatedAtDesc(u.getId())).hasSize(1);
        assertThat(neverLive.getId()).isNotNull();
    }

    @Test
    void theApiIsOwnerOnlyAndNeverBlocksAnything() throws Exception {
        String email = newUser();
        String other = newUser();
        LiveStream s = stream(email, 1, true);
        mvc.perform(get("/api/streams/" + s.getId() + "/check").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.flags[0].help").value(GoLiveCheck.HELP_LOOPS));
        mvc.perform(get("/api/streams/" + s.getId() + "/check").with(user(other))).andExpect(status().isNotFound());
        mvc.perform(put("/api/streams/" + s.getId() + "/check/title").with(user(other)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"youtubeTitle\":\"hijack\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/streams/" + s.getId() + "/check/title").with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"youtubeTitle\":\"Lofi beats LIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.youtubeTitle").value("Lofi beats LIVE"));
        mvc.perform(post("/api/streams/" + s.getId() + "/check/title-live/dismiss").with(user(email)).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/streams/" + s.getId() + "/check/one-file-loop/fix").with(user(email)).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(streamRepo.findById(s.getId()).orElseThrow().getCheckDismissed()).isEqualTo(GoLiveCheck.TITLE_LIVE);
        // The check is advice: going live doesn't consult it.
        assertThat(streams.preflight(email, s.getId()).checks()).noneMatch(c -> c.id().startsWith("one-file") || c.id().startsWith("still"));
    }
}
