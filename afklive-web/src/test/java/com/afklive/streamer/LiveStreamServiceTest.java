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
    @Autowired com.afklive.streamer.repository.UsageRepository usage;
    @Autowired LiveStreamRepository streams;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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
    void goingLiveCreatesOneStudioRunAndASecondStartGetsTheSameRun() {
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

        // A second press (by hand) gets the run that's starting; a scheduled start is told it's already live.
        assertThat(service.goLive(email, s.getId(), null).getId()).isEqualTo(job.getId());
        assertThatThrownBy(() -> service.goLive(email, s.getId(), null, List.<Long>of())).hasMessageContaining("already live");
        assertThat(jobs.findAllByStreamIdAndIsLiveTrue(s.getId())).hasSize(1);

        // Stop now with no worker running it: ended right away.
        LiveState after = service.stop(email, s.getId(), new StopRequest("NOW", null, null));
        assertThat(after.state()).isEqualTo("ENDED");
        assertThat(jobs.findById(job.getId()).orElseThrow().isLive()).isFalse();
    }

    @Test
    void destinationsPerStreamAreLimitedByThePlan() {
        String email = newUser(PlanType.FREE);
        LiveStream s = service.create(email, new CreateStream("One place", null, null, 720, 30));
        StreamDestination a = destination(email, "aaaa-bbbb-cccc-dddd");
        StreamDestination b = destination(email, "eeee-ffff-gggg-hhhh");
        assertThatThrownBy(() -> service.setTargets(email, s.getId(), List.of(new TargetRequest(a.getId(), true), new TargetRequest(b.getId(), true))))
                .hasMessageContaining("1 destination");
        // a second destination may be saved switched off
        service.setTargets(email, s.getId(), List.of(new TargetRequest(a.getId(), true), new TargetRequest(b.getId(), false)));

        String creator = newUser(PlanType.ESSENTIALS);
        LiveStream c = service.create(creator, new CreateStream("Two places", null, null, 1080, 30));
        StreamDestination x = destination(creator, "aaaa-bbbb-cccc-dddd");
        StreamDestination y = destination(creator, "eeee-ffff-gggg-hhhh");
        service.setTargets(creator, c.getId(), List.of(new TargetRequest(x.getId(), true), new TargetRequest(y.getId(), true)));
    }

    @Test
    void freeStreamsCarryTheWatermarkAndPaidOnesDont() {
        String free = newUser(PlanType.FREE);
        StreamJob job = service.goLive(free, ready(free).getId(), null);
        assertThat(StreamSpec.fromJson(job.getSpec()).studio().overlays())
                .anyMatch(o -> o.id().equals("afk-watermark") && "AFK Live".equals(o.text()));

        String paid = newUser(PlanType.ESSENTIALS);
        StreamJob paidJob = service.goLive(paid, ready(paid).getId(), null);
        assertThat(StreamSpec.fromJson(paidJob.getSpec()).studio().overlays()).noneMatch(o -> o.id().equals("afk-watermark"));
    }

    @Test
    void usedUpFreeHoursBlockGoingLive() {
        String email = newUser(PlanType.FREE);
        LiveStream s = ready(email);
        User u = users.findByEmail(email).orElseThrow();
        usage.add(u.getId(), com.afklive.streamer.service.BillingService.month(java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"))), 10 * 3600L);
        assertThat(service.preflight(email, s.getId()).checks()).anyMatch(c -> c.id().equals("hours") && !c.ok());
        assertThatThrownBy(() -> service.goLive(email, s.getId(), null)).hasMessageContaining("hours");
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
        // Spec updates made with a query are encrypted too (the column converter applies to the parameter).
        assertThat(jdbc.queryForObject("SELECT spec FROM stream_jobs WHERE id = ?", String.class, job.getId())).startsWith("enc:v2:");
    }

    @Test
    void destinationsTurnedOnAndOffWhileLiveReachTheWorkerWithoutMovingTheOthers() {
        // DS-5: before, the change was saved but the running stream kept its old destinations.
        String email = newUser(PlanType.PRO);
        LiveStream s = ready(email);
        StreamDestination yt = destinations.findAll().stream()
                .filter(d -> d.getUser().getEmail().equals(email)).findFirst().orElseThrow();
        StreamJob job = service.goLive(email, s.getId(), null);
        StreamDestination fb = destination(email, "rtmps://live-api-s.facebook.com:443/rtmp/fb-key");

        service.setTargets(email, s.getId(), List.of(new TargetRequest(yt.getId(), true), new TargetRequest(fb.getId(), true)));
        List<StreamSpec.Destination> after = StreamSpec.fromJson(jobs.findById(job.getId()).orElseThrow().getSpec()).destinations();
        assertThat(after).extracting(StreamSpec.Destination::off).containsExactly(false, false);
        assertThat(after.get(1).url()).endsWith("fb-key");

        service.setTargets(email, s.getId(), List.of(new TargetRequest(yt.getId(), false), new TargetRequest(fb.getId(), true)));
        after = StreamSpec.fromJson(jobs.findById(job.getId()).orElseThrow().getSpec()).destinations();
        assertThat(after).extracting(StreamSpec.Destination::off).containsExactly(true, false); // positions kept

        List<StreamCommand> sent = commands.findNew(List.of(job.getId()), 0).stream()
                .filter(c -> c.getType() == StreamCommand.Type.TARGETS).toList();
        assertThat(sent).hasSize(2);
        assertThat(StreamSpec.destinationsFromJson(sent.get(1).getPayload())).extracting(StreamSpec.Destination::off)
                .containsExactly(true, false);
        // The turned-off destination drops out of health instead of showing as broken.
        assertThat(service.view(email, s.getId()).live().health().destinations()).hasSize(1);

        assertThatThrownBy(() -> service.setTargets(email, s.getId(), List.of(new TargetRequest(fb.getId(), false))))
                .hasMessageContaining("at least one destination");
    }

    @Test
    void duplicatingCopiesTheSetupButNotWhereItGoesOrWhen() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        service.update(email, s.getId(), new UpdateStream(null, null, null, null, "SHUFFLE", null, null, null, null, null, null));
        service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting(null, "TEXT", true, 0.05, 0.05, 0, 0.05,
                "lofi rain", null, null, "#ffffff", "#99000000", 1, null, null, 0, null)));

        LiveStream copy = service.duplicate(email, s.getId());
        var view = service.view(email, copy.getId());
        assertThat(view.name()).isEqualTo("Lofi 24/7 (copy)");
        assertThat(view.playbackOrder()).isEqualTo("SHUFFLE");
        assertThat(view.playlist()).extracting(m -> m.title()).containsExactly("rain.mp4", "night.mp4");
        assertThat(view.overlays()).extracting(StudioSpec.OverlaySetting::text).containsExactly("lofi rain");
        assertThat(view.destinations()).hasSize(1).allMatch(d -> !d.enabled()); // same channel, switched off
        assertThat(view.schedules()).isEmpty();
        // The original is untouched.
        assertThat(service.view(email, s.getId()).destinations()).allMatch(d -> d.enabled());
    }

    @Autowired com.afklive.streamer.repository.StreamIncidentRepository incidents;

    @Test
    void uptimeIsTheShareOfOnAirTimeWithoutAnOutage() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertThat(service.uptime(email, s.getId(), now).windows()).allMatch(w -> w.percent() == null); // never on air

        StreamJob job = service.goLive(email, s.getId(), null);
        // Live for the last 10 hours, with a 30-minute outage 2 hours ago.
        job.setStartTime(now.minusHours(10));
        jobs.save(job);
        incidents.open(job.getId(), "DOWN", "", now.minusHours(2));
        incidents.resolve(job.getId(), "DOWN", "", now.minusHours(2).plusMinutes(30));
        // Another creator's run must not count.
        String other = newUser(PlanType.ESSENTIALS);
        StreamJob old = service.goLive(other, ready(other).getId(), null);

        var up = service.uptime(email, s.getId(), now);
        var day = up.windows().getFirst();
        assertThat(day.label()).isEqualTo("24 hours");
        assertThat(day.onAirSeconds()).isEqualTo(10 * 3600);
        assertThat(day.downSeconds()).isEqualTo(30 * 60);
        assertThat(day.percent()).isEqualTo(95.0);
        assertThat(up.outages()).hasSize(1);
        assertThat(old.getStreamId()).isNotEqualTo(s.getId());
    }

    @Test
    void reconnectIsACommandForALiveStreamOnly() {
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        assertThatThrownBy(() -> service.reconnect(email, s.getId())).hasMessageContaining("isn't live");
        StreamJob job = service.goLive(email, s.getId(), null);
        service.reconnect(email, s.getId());
        assertThat(commands.findNew(List.of(job.getId()), 0)).extracting(StreamCommand::getType).containsExactly(StreamCommand.Type.RECONNECT);
    }

    @Test
    void fitAndCrossfadeReachTheWorkerAndItemFitsSurviveReordering() {
        // PL-6 / AU-3.
        String email = newUser(PlanType.ESSENTIALS);
        LiveStream s = ready(email);
        service.setPresentation(email, s.getId(), new com.afklive.streamer.dto.StreamDtos.Presentation("blur", 6));
        assertThatThrownBy(() -> service.setPresentation(email, s.getId(), new com.afklive.streamer.dto.StreamDtos.Presentation(null, 25)))
                .hasMessageContaining("0 to 20");
        assertThatThrownBy(() -> service.setPresentation(email, s.getId(), new com.afklive.streamer.dto.StreamDtos.Presentation("stretch", null)))
                .hasMessageContaining("FIT, FILL or BLUR");
        var view = service.view(email, s.getId());
        assertThat(view.fit()).isEqualTo("BLUR");
        assertThat(view.crossfadeSeconds()).isEqualTo(6);

        service.setItemFit(email, s.getId(), 1, "FILL"); // night.mp4
        List<Long> ids = view.playlist().stream().map(m -> m.id()).toList();
        service.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(ids.get(1), ids.get(0))); // reorder
        view = service.view(email, s.getId());
        assertThat(view.playlist()).extracting(m -> m.title() + ":" + m.fit()).containsExactly("night.mp4:FILL", "rain.mp4:null");

        StudioSpec spec = service.studioSpec(streams.findById(s.getId()).orElseThrow(), null);
        assertThat(spec.fit()).isEqualTo("BLUR");
        assertThat(spec.crossfadeSeconds()).isEqualTo(6.0);
        assertThat(spec.videos()).extracting(StudioSpec.Media::fit).containsExactly("FILL", null);
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
    void aDoubleTapOnGoLiveCreatesOneRunAndBothTapsGetIt() throws Exception {
        // Production, 24 Oct 2026: jobs 19 and 20 were created for one stream within a second and
        // both were claimed. Going live is idempotent per stream: every tap gets the same run.
        String email = newUser(PlanType.AGENCY);
        LiveStream s = ready(email);
        int taps = 4;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(taps);
        List<Long> runIds = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < taps; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    runIds.add(service.goLive(email, s.getId(), null).getId());
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        for (Thread t : threads) {
            t.join(30_000);
        }
        assertThat(errors).isEmpty();
        assertThat(jobs.findAllByStreamIdAndIsLiveTrue(s.getId())).hasSize(1);
        assertThat(runIds).hasSize(taps).containsOnly(runIds.getFirst());
    }

    @Autowired WorkerStatusRepository workerReports;

    /** Only this worker report counts (other tests leave theirs in the shared database). */
    private void onlyWorker(int cpus, int streams, int capacity, double cpuPercent) {
        jdbc.update("DELETE FROM stream_workers");
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        workerReports.report("afklive-1", now.minusHours(1), now, streams, capacity, cpuPercent, 1L, 2L, cpus);
    }

    @Test
    void preflightWarnsWhenTheQualityWontKeepUpOnTheServersAndOffersOneThatFits() {
        // GL-7, production 24 Oct 2026: Agency allows 1080p60, but the 4-CPU worker was 92 % busy.
        String email = newUser(PlanType.AGENCY);
        LiveStream s = ready(email);
        service.update(email, s.getId(), new UpdateStream(null, null, 1080, 60, null, null, null, null, null, null, null));
        service.setOverlays(email, s.getId(), List.of(new StudioSpec.OverlaySetting(null, "TEXT", true, 0.05, 0.05, 0, 0.05,
                "Night drive", null, null, "#ffffff", "#99000000", 1, null, null, 0, null))); // drawn live: no copy mode
        try {
            onlyWorker(4, 2, 4, 70.0); // 3.4 usable, 2.8 in use: 0.6 free
            Preflight pf = service.preflight(email, s.getId());
            assertThat(pf.ok()).isTrue(); // a warning, not a failed check
            assertThat(pf.capacity().fits()).isFalse();
            assertThat(pf.capacity().needCpus()).isEqualTo(2.1);
            assertThat(pf.capacity().freeCpus()).isEqualTo(0.6);
            assertThat(pf.capacity().suggestion()).isEqualTo(new com.afklive.streamer.service.CapacityEstimator.Quality(720, 30));
            assertThat(pf.capacity().message()).contains("By our estimate 1080p 60 fps would run slower than real time", "720p 30 fps fits");

            // Going live by hand stops at the warning; "Go live anyway" goes live.
            assertThatThrownBy(() -> service.goLive(email, s.getId(), null, false))
                    .isInstanceOf(LiveStreamService.CapacityWarningException.class)
                    .hasMessageContaining("slower than real time");
            assertThat(jobs.findAllByStreamIdAndIsLiveTrue(s.getId())).isEmpty();
            assertThat(service.goLive(email, s.getId(), null, true).getStreamId()).isEqualTo(s.getId());
            streamService.finalizeJob(jobs.findAllByStreamIdAndIsLiveTrue(s.getId()).getFirst().getId(), "test");

            // Taking the suggestion fits.
            service.update(email, s.getId(), new UpdateStream(null, null, 720, 30, null, null, null, null, null, null, null));
            assertThat(service.preflight(email, s.getId()).capacity().fits()).isTrue();
            assertThat(service.goLive(email, s.getId(), null, false)).isNotNull();
        } finally {
            jdbc.update("DELETE FROM stream_workers");
        }
    }

    @Test
    void preflightBlocksOnlyAQualityNoServerCouldRunAndSaysNothingWithoutReports() {
        String email = newUser(PlanType.AGENCY);
        LiveStream s = ready(email);
        service.update(email, s.getId(), new UpdateStream(null, null, 2160, 30, null, null, null, null, null, null, null));
        try {
            jdbc.update("DELETE FROM stream_workers");
            Preflight unknown = service.preflight(email, s.getId());
            assertThat(unknown.ok()).isTrue();
            assertThat(unknown.capacity()).isNull(); // no worker reported: nothing to say

            onlyWorker(4, 0, 4, 1.0); // even idle, 4 CPUs can't encode 4K
            Preflight pf = service.preflight(email, s.getId());
            assertThat(pf.ok()).isFalse();
            assertThat(pf.checks()).filteredOn(c -> c.id().equals("server-capacity")).singleElement()
                    .satisfies(c -> {
                        assertThat(c.ok()).isFalse();
                        assertThat(c.fix()).isEqualTo("settings");
                        assertThat(c.message()).contains("Pick a lower quality, such as 1440p 30 fps");
                    });
            assertThatThrownBy(() -> service.goLive(email, s.getId(), null, true)).hasMessageContaining("Pick a lower quality");
        } finally {
            jdbc.update("DELETE FROM stream_workers");
        }
    }

    @Test
    void theDatabaseRefusesASecondLiveRunOfTheSameStream() {
        // The last line of defence: even a path that skips goLive's checks can't create a second run.
        String email = newUser(PlanType.AGENCY);
        LiveStream s = ready(email);
        StreamJob first = service.goLive(email, s.getId(), null);
        Long userId = users.findByEmail(email).orElseThrow().getId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO stream_jobs(user_id, is_live, pid, stream_id, restarts) VALUES (?, true, 0, ?, 0)",
                userId, s.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // Ended runs don't count: history keeps every past run of the stream.
        streamService.finalizeJob(first.getId(), "test");
        assertThat(service.goLive(email, s.getId(), null).getId()).isNotEqualTo(first.getId());
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
