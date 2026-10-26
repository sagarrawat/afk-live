package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
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
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.VideoBitrates;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FO-4: a run goes live at its owner's plan's bitrate, recorded in its spec, and keeps it whatever happens
 * while it's live: settings edits rebuild the spec, the plan changes, a worker reloads.
 */
@SpringBootTest(properties = {
        "app.streaming.bitrate.free.720=2500",
        "app.streaming.bitrate.pro.720=3500",
        "app.streaming.bitrate.essentials.720=900" // below the floor: 1500
})
class BitratePerPlanTest extends PostgresIntegrationTest {

    @Autowired LiveStreamService service;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamCommandRepository commands;
    @Autowired StreamService streamService;
    @Autowired VideoBitrates bitrates;

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("kbps-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private String newUser(PlanType plan) {
        String email = "kbps-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(plan);
        users.save(u);
        return email;
    }

    private MediaAsset video(String email, String title) {
        MediaAsset m = new MediaAsset(users.findByEmail(email).orElseThrow(), title, "key/" + UUID.randomUUID() + ".mp4", 100L,
                MediaAsset.ContentType.VIDEO);
        m.setDurationSeconds(60.0);
        m.setHasAudio(true);
        return media.save(m);
    }

    private LiveStream ready(String email, int fps) {
        LiveStream s = service.create(email, new CreateStream("Lofi 24/7", null, null, 720, fps));
        MediaAsset v1 = video(email, "rain.mp4");
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v1.getId()));
        StreamDestination d = new StreamDestination();
        d.setName("YouTube");
        d.setStreamKey("abcd-efgh-ijkl-mnop");
        d.setType("RTMP");
        d.setUser(users.findByEmail(email).orElseThrow());
        d = destinations.save(d);
        service.setTargets(email, s.getId(), List.of(new TargetRequest(d.getId(), true)));
        return s;
    }

    private Integer recorded(Long jobId) {
        return StreamSpec.fromJson(jobs.findById(jobId).orElseThrow().getSpec()).studio().videoKbps();
    }

    @Test
    void theConfiguredValuesAreReadPerPlanWithTheFloorApplied() {
        assertThat(bitrates.videoKbps(PlanType.FREE, 720, 30)).isEqualTo(2500);
        assertThat(bitrates.videoKbps(PlanType.ESSENTIALS, 720, 30)).isEqualTo(1500);
        assertThat(bitrates.videoKbps(PlanType.AGENCY, 720, 30)).isEqualTo(4000);
        assertThat(bitrates.videoKbps(PlanType.FREE, 1080, 30)).isEqualTo(6000);
    }

    @Test
    void eachRunGoesLiveAtItsPlansBitrateRecordedInItsSpec() {
        String free = newUser(PlanType.FREE);
        StreamJob f = service.goLive(free, ready(free, 30).getId(), null);
        assertThat(recorded(f.getId())).isEqualTo(2500);

        String pro = newUser(PlanType.PRO);
        StreamJob p = service.goLive(pro, ready(pro, 60).getId(), null);
        assertThat(recorded(p.getId())).isEqualTo(5250); // 60 fps: 1.5x

        String agency = newUser(PlanType.AGENCY);
        StreamJob a = service.goLive(agency, ready(agency, 30).getId(), null);
        assertThat(recorded(a.getId())).isEqualTo(4000); // no value of its own: the built-in ladder
    }

    @Test
    void aPlanChangeAndLiveEditsNeverChangeARunningStreamsBitrate() {
        String email = newUser(PlanType.FREE);
        LiveStream s = ready(email, 30);
        StreamJob job = service.goLive(email, s.getId(), null);
        assertThat(recorded(job.getId())).isEqualTo(2500);

        // Upgraded while live (Pro: 3500 for new runs).
        User u = users.findByEmail(email).orElseThrow();
        u.setPlanType(PlanType.PRO);
        users.save(u);

        // A playlist edit while live rebuilds the run's spec and sends the studio a RELOAD.
        MediaAsset more = video(email, "night.mp4");
        List<Long> ids = service.view(email, s.getId()).playlist().stream().map(r -> r.id()).collect(java.util.stream.Collectors.toList());
        ids.add(more.getId());
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, ids);
        assertThat(recorded(job.getId())).isEqualTo(2500);
        StreamCommand reload = commands.findAll().stream()
                .filter(c -> c.getJobId().equals(job.getId()) && c.getType() == StreamCommand.Type.RELOAD)
                .max(Comparator.comparing(StreamCommand::getId)).orElseThrow();
        assertThat(StudioSpec.parse(reload.getPayload(), StudioSpec.class).videoKbps()).isEqualTo(2500);

        // "Stop after this pass" and taking it back rebuild the spec too.
        service.stop(email, s.getId(), new com.afklive.streamer.dto.StreamDtos.StopRequest("AFTER_PLAYLIST", null, null));
        assertThat(recorded(job.getId())).isEqualTo(2500);
        service.cancelFinishAfterPass(email, s.getId());
        assertThat(recorded(job.getId())).isEqualTo(2500);

        // The next run is at the new plan's bitrate.
        streamService.finalizeJob(job.getId(), "test");
        StreamJob next = service.goLive(email, s.getId(), null);
        assertThat(recorded(next.getId())).isEqualTo(3500);
    }
}
