package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StreamStates;
import com.afklive.streamer.streaming.StudioSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LiveStreamServiceTest extends PostgresIntegrationTest {

    @Autowired LiveStreamService service;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamCommandRepository commands;
    @Autowired LiveStreamRepository streams;
    @Autowired com.afklive.streamer.service.StreamService streamService;

    /** Runs started here must not be left for the worker tests (same database) to pick up. */
    @org.junit.jupiter.api.AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("ls-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private String newUser(PlanType plan) {
        String email = "ls-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(plan);
        users.save(u);
        return email;
    }

    private MediaAsset asset(String email, MediaAsset.ContentType type, String title) {
        MediaAsset m = new MediaAsset(users.findByEmail(email).orElseThrow(), title, "key/" + UUID.randomUUID() + ".mp4", 100L, type);
        m.setDurationSeconds(60.0);
        m.setHasAudio(true);
        return media.save(m);
    }

    private StreamDestination destination(String email, String key) {
        StreamDestination d = new StreamDestination();
        d.setName("YouTube");
        d.setStreamKey(key);
        d.setType("RTMP");
        d.setUser(users.findByEmail(email).orElseThrow());
        return destinations.save(d);
    }

    /** A stream ready to go live: two videos, one destination. */
    private LiveStream ready(String email) {
        LiveStream s = service.create(email, new CreateStream("Lofi 24/7", null, null, 720, 30));
        MediaAsset v1 = asset(email, MediaAsset.ContentType.VIDEO, "rain.mp4");
        MediaAsset v2 = asset(email, MediaAsset.ContentType.VIDEO, "night.mp4");
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v1.getId(), v2.getId()));
        StreamDestination d = destination(email, "abcd-efgh-ijkl-mnop");
        service.setTargets(email, s.getId(), List.of(new TargetRequest(d.getId(), true)));
        return s;
    }

    @Test
    void qualityIsCappedByThePlanWithTheReason() {
        String email = newUser(PlanType.FREE);
        assertThatThrownBy(() -> service.create(email, new CreateStream("Too sharp", null, null, 1080, 30)))
                .hasMessageContaining("720p");
        assertThatThrownBy(() -> service.create(email, new CreateStream("Too fast", null, null, 720, 60)))
                .hasMessageContaining("60 fps");
        LiveStream ok = service.create(email, new CreateStream("Fine", "PLAYLIST", "VERTICAL", 720, 30));
        assertThat(ok.getOrientation()).isEqualTo(LiveStream.Orientation.VERTICAL);
        assertThat(service.qualityOptions(email)).filteredOn(QualityOption::allowed).extracting(QualityOption::height).containsOnly(720);
    }

    @Test
    void playlistsOnlyTakeTheOwnersFilesOfTheRightKind() {
        String email = newUser(PlanType.ESSENTIALS);
        String other = newUser(PlanType.ESSENTIALS);
        LiveStream s = service.create(email, new CreateStream("Mine", null, null, 1080, 30));
        MediaAsset theirs = asset(other, MediaAsset.ContentType.VIDEO, "theirs.mp4");
        MediaAsset song = asset(email, MediaAsset.ContentType.AUDIO, "song.mp3");
        MediaAsset clip = asset(email, MediaAsset.ContentType.VIDEO, "clip.mp4");

        assertThatThrownBy(() -> service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(theirs.getId())))
                .hasMessageContaining("isn't in your library");
        assertThatThrownBy(() -> service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(song.getId())))
                .hasMessageContaining("audio");
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(clip.getId(), clip.getId()));
        service.setItems(email, s.getId(), StreamItem.Kind.AUDIO, List.of(song.getId()));

        StreamView v = service.view(email, s.getId());
        assertThat(v.playlist()).extracting(MediaRef::title).containsExactly("clip.mp4", "clip.mp4");
        assertThat(v.totalRuntimeSeconds()).isEqualTo(120.0);
        assertThat(v.music()).extracting(MediaRef::title).containsExactly("song.mp3");
        assertThatThrownBy(() -> service.view(other, s.getId())).hasMessageContaining("not found");
    }

    @Test
    void preflightNamesEachProblemAndWhereToFixIt() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = service.create(email, new CreateStream("Empty", null, null, 720, 30));
        Preflight pf = service.preflight(email, s.getId());
        assertThat(pf.ok()).isFalse();
        assertThat(pf.checks()).filteredOn(c -> !c.ok()).extracting(Check::id).contains("playlist", "destinations");
        assertThat(pf.checks()).filteredOn(c -> c.id().equals("playlist")).extracting(Check::fix).containsExactly("playlist");

        LiveStream good = ready(email);
        assertThat(service.preflight(email, good.getId()).ok()).isTrue();

        // A destination pointing inside our network is refused before going live.
        StreamDestination bad = destination(email, "rtmp://127.0.0.1/live/x");
        service.setTargets(email, good.getId(), List.of(new TargetRequest(bad.getId(), true)));
        assertThat(service.preflight(email, good.getId()).ok()).isFalse();
    }

    @Test
    void goingLiveCreatesOneStudioRunAndASecondStartIsRefused() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        assertThat(service.view(email, s.getId()).live().state()).isEqualTo("DRAFT");

        StreamJob job = service.goLive(email, s.getId(), null);
        StreamSpec spec = StreamSpec.fromJson(job.getSpec());
        assertThat(spec.streamId()).isEqualTo(s.getId());
        assertThat(spec.studio().videos()).extracting(StudioSpec.Media::title).containsExactly("rain.mp4", "night.mp4");
        assertThat(spec.studio().videos()).allMatch(m -> m.key().startsWith("key/"));
        assertThat(spec.destinations()).hasSize(1);
        assertThat(job.getStreamId()).isEqualTo(s.getId());
        assertThat(service.view(email, s.getId()).live().state()).isEqualTo("STARTING");

        assertThatThrownBy(() -> service.goLive(email, s.getId(), null)).hasMessageContaining("already live");

        // Stop now with no worker running it: ended right away.
        LiveState after = service.stop(email, s.getId(), new StopRequest("NOW", null, null));
        assertThat(after.state()).isEqualTo("ENDED");
        assertThat(jobs.findById(job.getId()).orElseThrow().isLive()).isFalse();
    }

    @Test
    void theEssentialsPlanRunsOneStreamAtATime() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream a = ready(email);
        LiveStream b = ready(email);
        service.goLive(email, a.getId(), null);
        Preflight pf = service.preflight(email, b.getId());
        assertThat(pf.ok()).isFalse();
        assertThat(pf.checks()).extracting(Check::id).contains("concurrency");
    }

    @Test
    void liveChangesBecomeCommandsForTheWorker() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        StreamJob job = service.goLive(email, s.getId(), null);
        // Pretend a worker owns it, so a stop is left to the worker.
        job.setWorkerId("w1");
        job.setHeartbeatAt(ZonedDateTime.now(ZoneId.of("UTC")));
        jobs.save(job);

        service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting(null, "TEXT", true, 0.05, 0.05, 0, 0.05,
                "Now playing: {title}", null, null, "#ffffff", "#99000000", 1, null, null, 0, null)));
        String overlayId = service.view(email, s.getId()).overlays().getFirst().id();
        service.switchScene(email, s.getId(), "BRB");
        service.setOverlayVisible(email, s.getId(), overlayId, false);
        service.skip(email, s.getId());
        service.update(email, s.getId(), new UpdateStream(null, null, null, null, "SHUFFLE", null, null, null, null, null, null));
        service.stop(email, s.getId(), new StopRequest("AFTER_PLAYLIST", null, null));

        List<StreamCommand> sent = commands.findNew(List.of(job.getId()), 0);
        assertThat(sent).extracting(StreamCommand::getType).containsExactly(
                StreamCommand.Type.RELOAD, StreamCommand.Type.SCENE, StreamCommand.Type.OVERLAY,
                StreamCommand.Type.SKIP, StreamCommand.Type.RELOAD, StreamCommand.Type.FINISH_PASS);
        assertThat(sent.get(1).getPayload()).isEqualTo("BRB");
        assertThat(StudioSpec.parse(sent.get(4).getPayload(), StudioSpec.class).order()).isEqualTo("SHUFFLE");
        // The hidden overlay stays hidden next time.
        assertThat(service.view(email, s.getId()).overlays().getFirst().visible()).isFalse();
        // And a worker that restarts or takes over builds the latest settings, including the pending stop.
        StudioSpec persisted = StreamSpec.fromJson(jobs.findById(job.getId()).orElseThrow().getSpec()).studio();
        assertThat(persisted.order()).isEqualTo("SHUFFLE");
        assertThat(persisted.overlays().getFirst().visible()).isFalse();
        assertThat(persisted.finishAfterPass()).isTrue();
    }

    @Test
    void stopAtUsesTheCreatorsTimeZone() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        StreamJob job = service.goLive(email, s.getId(), null);
        job.setWorkerId("w1");
        job.setHeartbeatAt(ZonedDateTime.now(ZoneId.of("UTC")));
        jobs.save(job);
        String at = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusHours(2).toLocalDateTime().withSecond(0).withNano(0).toString();
        LiveState st = service.stop(email, s.getId(), new StopRequest("AT", at, "Asia/Kolkata"));
        assertThat(st.stopAt()).isNotNull();
        assertThat(st.stopAt().withZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDateTime().toString()).startsWith(at.substring(0, 16));
        service.cancelScheduledStop(email, s.getId());
        assertThat(service.view(email, s.getId()).live().stopAt()).isNull();
    }

    @Test
    void overlaysAreValidated() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = service.create(email, new CreateStream("O", null, null, 720, 30));
        assertThatThrownBy(() -> service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting("a", "LASER", true,
                0, 0, 0, 0, null, null, null, null, null, 1, null, null, 0, null)))).hasMessageContaining("Unknown overlay type");
        assertThatThrownBy(() -> service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting("a", "IMAGE", true,
                0, 0, 0.2, 0, null, 999999L, null, null, null, 1, null, null, 0, null)))).hasMessageContaining("Pick an image");
        assertThatThrownBy(() -> service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting("a", "TEXT", true,
                0, 0, 0, 0, "hi", null, null, "red", null, 1, null, null, 0, null)))).hasMessageContaining("Colours");
        List<StudioSpec.OverlaySetting> saved = service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting(null, "clock",
                true, 5, -1, 0, 0, null, null, null, null, null, 0, "Asia/Kolkata", null, 0, null)));
        assertThat(saved.getFirst().x()).isEqualTo(1.0);
        assertThat(saved.getFirst().y()).isEqualTo(0.0);
        assertThat(saved.getFirst().type()).isEqualTo("CLOCK");
    }

    @Test
    void aStreamCantBeDeletedWhileLive() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        service.goLive(email, s.getId(), null);
        assertThatThrownBy(() -> service.delete(email, s.getId())).hasMessageContaining("Stop the stream");
        assertThat(jobs.findAllByStreamIdAndIsLiveTrue(s.getId()).getFirst().getStatus()).isEqualTo(StreamStates.STARTING);
    }

    @Test
    void twoSimultaneousStartsCantBothPassTheOneStreamLimit() throws Exception {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream a = ready(email);
        LiveStream b = ready(email);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> threads = new java.util.ArrayList<>();
        for (LiveStream s : List.of(a, b)) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    service.goLive(email, s.getId(), null);
                    started.incrementAndGet();
                } catch (Exception ignored) {
                    // the one that loses the race is refused by pre-flight
                }
            }));
        }
        for (Thread t : threads) {
            t.join(20_000);
        }
        assertThat(started.get()).isEqualTo(1);
        assertThat(jobs.countByUserEmailAndIsLiveTrue(email)).isEqualTo(1);
    }

    @Test
    void picturesettingsCantChangeWhileLiveAndUnknownStreamsAreNotFound() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        service.goLive(email, s.getId(), null);
        assertThatThrownBy(() -> service.update(email, s.getId(), new UpdateStream(null, null, 1080, null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.afklive.streamer.exception.ConflictException.class);
        // Other settings still apply live.
        service.update(email, s.getId(), new UpdateStream("Renamed", null, null, null, null, null, null, null, null, 0.5, null));
        assertThatThrownBy(() -> service.view(email, 987654321L)).isInstanceOf(com.afklive.streamer.exception.NotFoundException.class);
    }

    @Test
    void aFinishedPlaylistStartsFromTheTopNextTime() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        StreamJob job = service.goLive(email, s.getId(), null);
        streams.saveResumePositionForTest(s.getId(), "{\"itemId\":\"1|k\",\"offsetSeconds\":10,\"loopsCompleted\":0}");
        streamService.finalizeJob(job.getId(), "Playlist finished");
        assertThat(streams.findById(s.getId()).orElseThrow().getResumePosition()).isNull();
    }
}
