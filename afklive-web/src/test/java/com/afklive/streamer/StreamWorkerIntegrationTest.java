package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamJobDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.YouTubeService;
import com.afklive.streamer.streaming.StreamPreparer;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StreamStates;
import com.afklive.streamer.streaming.StreamWorker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End to end through the real worker: database coordination, file preparation, FFmpeg encoder and relay.
 * The destination is a local file instead of a platform.
 */
@SpringBootTest(properties = "app.streaming.work-dir=${java.io.tmpdir}/afklive-test-worker")
class StreamWorkerIntegrationTest extends PostgresIntegrationTest {

    @TempDir
    static Path dir;
    static Path source;

    @Autowired StreamWorker worker;
    @Autowired StreamService streamService;
    @Autowired StreamJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired FileStorageService storage;
    @Autowired StreamJobDestinationRepository destinations;
    @Autowired StreamPreparer preparer;
    @Autowired YouTubeService youTubeService;
    @Autowired com.afklive.streamer.streaming.StudioRuns studioRuns;
    @Autowired com.afklive.streamer.repository.StreamCommandRepository commandRepository;
    @Autowired com.afklive.streamer.service.LiveStreamService liveStreams;
    @Autowired com.afklive.streamer.repository.LiveStreamRepository streamRepository;
    @Autowired com.afklive.streamer.repository.MediaAssetRepository mediaRepository;
    @Autowired com.afklive.streamer.service.PlayoutCopies playoutCopies;
    @Autowired com.afklive.streamer.streaming.MediaCache mediaCache;

    /** A separate worker process, as far as coordination goes (own id, own first pass). */
    private StreamWorker newWorker(String id) {
        return new StreamWorker(jobs, destinations, streamService, preparer, storage, youTubeService, studioRuns,
                commandRepository, id, 5, 500, false, 1.01); // never "busy": test machines are, and these tests are about coordination
    }

    private String uploadSource() throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            return storage.uploadFile(in, "src.mp4", Files.size(source));
        }
    }

    private StreamSpec fileSpec(User owner, String videoKey, Path out) {
        return new StreamSpec(owner.getEmail(), videoKey, null, "1.0", -1, null, false, "original", 360,
                false, null, null, List.of(new StreamSpec.Destination("File", "file://" + out)));
    }

    private static void tickUntil(StreamWorker w, BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            w.tick();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    private boolean onAir(long id) {
        StreamJob j = jobs.findById(id).orElseThrow();
        return StreamStates.LIVE.equals(j.getStatus()) && j.getBitrateKbps() != null && j.getBitrateKbps() > 0;
    }

    @BeforeAll
    static void makeSource() throws Exception {
        Process v = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0, "ffmpeg not installed");
        source = dir.resolve("src.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", source.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    }

    private User account(String email) {
        return users.findByEmail(email).orElseGet(() -> {
            User u = new User(email);
            u.setPlanType(PlanType.ESSENTIALS);
            return users.save(u);
        });
    }

    private StreamJob job(User owner, String spec, String workerId, ZonedDateTime heartbeat) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = new StreamJob(owner, "k", "video", null, null, true, 0, null, null, null, now, "File", false);
        job.setLastBillingTime(now);
        job.setStatus(StreamStates.STARTING);
        job.setDesiredState(StreamStates.RUN);
        job.setSpec(spec);
        job.setWorkerId(workerId);
        job.setHeartbeatAt(heartbeat);
        job.getDestinations().add(new StreamJobDestination(job, 0, "File"));
        return jobs.save(job);
    }

    private void tickUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        tickUntil(worker, condition, timeout);
    }

    @Test
    void aBusyWorkerLeavesNewStreamsToOthersButStartsThemIfNobodyDoes() throws Exception {
        User owner = account("worker-cpu@example.com");
        StreamWorker busy = new StreamWorker(jobs, destinations, streamService, preparer, storage, youTubeService, studioRuns,
                commandRepository, "w-cpu", 5, 500, false, 0.85);
        busy.cpuLoadFrom(() -> 0.95);
        long first = job(owner, fileSpec(owner, uploadSource(), dir.resolve("cpu-1.flv")).toJson(), null, null).getId();
        long second = -1;
        try {
            // Idle (nothing running yet): takes the first stream whatever the CPU reads.
            tickUntil(busy, () -> onAir(first), Duration.ofSeconds(40));

            second = job(owner, fileSpec(owner, uploadSource(), dir.resolve("cpu-2.flv")).toJson(), null, null).getId();
            for (int i = 0; i < 3; i++) {
                busy.tick();
            }
            assertThat(jobs.findById(second).orElseThrow().getWorkerId()).as("left for a less busy worker").isNull();

            // Nobody else took it within the grace period: start it here rather than never.
            StreamJob waiting = jobs.findById(second).orElseThrow();
            waiting.setStartTime(waiting.getStartTime().minus(StreamWorker.BUSY_CLAIM_GRACE).minusSeconds(5));
            jobs.save(waiting);
            busy.tick();
            assertThat(jobs.findById(second).orElseThrow().getWorkerId()).isEqualTo("w-cpu");
        } finally {
            busy.shutdown();
            streamService.finalizeJob(first, "test");
            if (second > 0) {
                streamService.finalizeJob(second, "test");
            }
        }
    }

    @Test
    void anOrphanedStreamIsTakenOverAndRunsUntilStopped() throws Exception {
        User owner = account("worker-e2e@example.com");
        String videoKey = uploadSource();
        Path out = dir.resolve("e2e.flv");
        StreamSpec spec = fileSpec(owner, videoKey, out);
        // Owned by a worker that stopped reporting two minutes ago.
        StreamJob job = job(owner, spec.toJson(), "dead-worker", ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(2));
        long id = job.getId();

        tickUntil(() -> onAir(id), Duration.ofSeconds(40));

        StreamJob live = jobs.findById(id).orElseThrow();
        assertThat(live.getWorkerId()).isEqualTo(worker.workerId());
        assertThat(live.getRestarts()).isEqualTo(1); // counted as a takeover
        assertThat(live.getFps()).isPositive();
        // The relay writes once it has probed the stream (a few seconds after the encoder starts).
        tickUntil(() -> Files.exists(out) && out.toFile().length() > 0, Duration.ofSeconds(20));
        tickUntil(() -> "LIVE".equals(jobs.findById(id).orElseThrow().getDestinations().get(0).getStatus()), Duration.ofSeconds(15));

        // The web app asks to stop; the worker (alive, so not ended directly) carries it out.
        streamService.stopStream(id, owner.getEmail());
        assertThat(jobs.findById(id).orElseThrow().isLive()).isTrue();
        tickUntil(() -> !jobs.findById(id).orElseThrow().isLive(), Duration.ofSeconds(30));

        StreamJob ended = jobs.findById(id).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(StreamStates.ENDED);
        assertThat(ended.getEndReason()).isEqualTo("Stopped by user");
        assertThat(worker.runningCount()).isZero();
    }

    @Test
    void aJobWithUnreadableSettingsIsClaimedAndFailedNotLeftHanging() throws Exception {
        User owner = account("worker-bad@example.com");
        StreamJob job = job(owner, "{not json", null, null);
        long id = job.getId();

        tickUntil(() -> !jobs.findById(id).orElseThrow().isLive(), Duration.ofSeconds(10));

        StreamJob after = jobs.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(StreamStates.FAILED);
        assertThat(after.getEndReason()).contains("unreadable");
    }

    @Test
    void aStreamWithAHealthyWorkerIsNotTakenOver() throws Exception {
        User owner = account("worker-busy@example.com");
        StreamJob job = job(owner, "{}", "other-worker", ZonedDateTime.now(ZoneId.of("UTC")));
        worker.tick();
        assertThat(jobs.findById(job.getId()).orElseThrow().getWorkerId()).isEqualTo("other-worker");
        // tidy up
        streamService.finalizeJob(job.getId(), "test");
    }

    @Test
    void aRestartedWorkerResumesItsOwnStreamOnceAndOnlyOnStartup() throws Exception {
        User owner = account("worker-resume@example.com");
        Path out = dir.resolve("resume.flv");
        // Assigned to "w-resume" which just restarted (its heartbeat is recent, so nobody else takes it).
        StreamJob job = job(owner, fileSpec(owner, uploadSource(), out).toJson(), "w-resume", ZonedDateTime.now(ZoneId.of("UTC")));
        long id = job.getId();
        StreamWorker restarted = newWorker("w-resume");
        try {
            tickUntil(restarted, () -> onAir(id), Duration.ofSeconds(40));
            assertThat(restarted.runningCount()).isEqualTo(1);
            // Further passes keep the one copy running (no second start).
            for (int i = 0; i < 3; i++) {
                restarted.tick();
            }
            assertThat(restarted.runningCount()).isEqualTo(1);
            assertThat(jobs.findById(id).orElseThrow().getRestarts()).isZero();

            streamService.stopStream(id, owner.getEmail());
            tickUntil(restarted, () -> !jobs.findById(id).orElseThrow().isLive(), Duration.ofSeconds(30));
            assertThat(restarted.runningCount()).isZero();
        } finally {
            restarted.shutdown();
            streamService.finalizeJob(id, "test");
        }
    }

    @Test
    void twoProcessesWithTheSameIdNeverBothKeepAStream() throws Exception {
        User owner = account("worker-dup@example.com");
        StreamJob job = job(owner, fileSpec(owner, uploadSource(), dir.resolve("dup.flv")).toJson(), null, null);
        long id = job.getId();
        StreamWorker first = newWorker("w-dup");
        StreamWorker second = newWorker("w-dup");
        try {
            tickUntil(first, () -> onAir(id), Duration.ofSeconds(40));
            // The second process starts up, sees a job under its id and resumes it; the first one's
            // next heartbeat no longer matches, so it lets go.
            second.tick();
            first.tick();
            assertThat(first.runningCount()).isZero();
            assertThat(second.runningCount()).isEqualTo(1);
            tickUntil(second, () -> onAir(id), Duration.ofSeconds(40));
            first.tick();
            assertThat(first.runningCount()).isZero();
        } finally {
            first.shutdown();
            second.shutdown();
            streamService.finalizeJob(id, "test");
        }
    }

    @Test
    void aStopWhoseWorkerDiedIsCompletedByTheWebApp() {
        User owner = account("worker-abandoned@example.com");
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        StreamJob job = job(owner, "{}", "busy-worker", now);
        long id = job.getId();

        // Worker alive: the stop is left for it to carry out.
        streamService.stopStream(id, owner.getEmail());
        assertThat(jobs.findById(id).orElseThrow().isLive()).isTrue();

        // It dies before doing so.
        StreamJob j = jobs.findById(id).orElseThrow();
        j.setHeartbeatAt(now.minusMinutes(2));
        jobs.save(j);
        streamService.stopDueStreams();

        StreamJob ended = jobs.findById(id).orElseThrow();
        assertThat(ended.isLive()).isFalse();
        assertThat(ended.getEndReason()).isEqualTo("Stopped by user");
    }

    @Test
    void aSavedPlaylistStreamRunsInTheStudioAndTakesLiveCommands() throws Exception {
        User owner = account("worker-studio@example.com");
        com.afklive.streamer.model.LiveStream stream = liveStreams.create(owner.getEmail(),
                new com.afklive.streamer.dto.StreamDtos.CreateStream("Studio test", null, null, 720, 30));
        List<Long> ids = new java.util.ArrayList<>();
        for (String title : List.of("first.mp4", "second.mp4")) {
            com.afklive.streamer.model.MediaAsset m = new com.afklive.streamer.model.MediaAsset(owner, title, uploadSource(),
                    Files.size(source), com.afklive.streamer.model.MediaAsset.ContentType.VIDEO);
            ids.add(mediaRepository.save(m).getId()); // duration and audio unknown: the worker probes them
        }
        liveStreams.setItems(owner.getEmail(), stream.getId(), com.afklive.streamer.model.StreamItem.Kind.VIDEO, ids);
        liveStreams.setOverlays(owner.getEmail(), stream.getId(), List.of(new com.afklive.streamer.streaming.StudioSpec.OverlaySetting(
                "np", "TEXT", true, 0.05, 0.05, 0, 0.05, "Now playing: {title}", null, null, "#ffffff", "#99000000", 1,
                null, null, 0, null)));
        stream = streamRepository.findById(stream.getId()).orElseThrow();

        Path out = dir.resolve("studio.flv");
        com.afklive.streamer.streaming.StudioSpec studio = liveStreams.studioSpec(stream, null);
        StreamSpec spec = StreamSpec.studio(owner.getEmail(), stream.getId(), studio,
                List.of(new StreamSpec.Destination("File", "file://" + out)));
        StreamJob job = job(owner, spec.toJson(), null, null);
        job.setStreamId(stream.getId());
        jobs.save(job);
        long id = job.getId();

        StreamWorker w = newWorker("w-studio");
        try {
            // A command sent while the run is still starting is applied once the studio is ready.
            liveStreams.switchScene(owner.getEmail(), stream.getId(), "STARTING");
            tickUntil(w, () -> {
                w.pollCommands();
                return onAir(id) && "STARTING".equals(jobs.findById(id).orElseThrow().getScene());
            }, Duration.ofSeconds(60));
            liveStreams.switchScene(owner.getEmail(), stream.getId(), "LIVE");
            tickUntil(w, () -> {
                w.pollCommands();
                StreamJob j = jobs.findById(id).orElseThrow();
                return "LIVE".equals(j.getScene()) && j.getNowPlaying() != null;
            }, Duration.ofSeconds(20));
            StreamJob live = jobs.findById(id).orElseThrow();
            assertThat(live.getNowPlaying()).contains("first.mp4");
            assertThat(streamRepository.findById(stream.getId()).orElseThrow().getResumePosition()).isNotNull();

            // The remote switches to BRB: applied between frames, the encoder keeps running.
            liveStreams.switchScene(owner.getEmail(), stream.getId(), "BRB");
            w.pollCommands();
            tickUntil(w, () -> "BRB".equals(jobs.findById(id).orElseThrow().getScene()), Duration.ofSeconds(10));
            liveStreams.switchScene(owner.getEmail(), stream.getId(), "LIVE");
            liveStreams.skip(owner.getEmail(), stream.getId());
            w.pollCommands();
            tickUntil(w, () -> {
                String np = jobs.findById(id).orElseThrow().getNowPlaying();
                return np != null && np.contains("second.mp4") && "LIVE".equals(jobs.findById(id).orElseThrow().getScene());
            }, Duration.ofSeconds(15));

            streamService.stopStream(id, owner.getEmail());
            tickUntil(w, () -> !jobs.findById(id).orElseThrow().isLive(), Duration.ofSeconds(30));
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(StreamStates.ENDED);
            assertThat(jobs.findById(id).orElseThrow().getRestarts()).isZero();
            assertThat(Files.size(out)).isPositive();
        } finally {
            w.shutdown();
            streamService.finalizeJob(id, "test");
        }
    }

    @Test
    void aPlainPlaylistMovesToCopyModeAndBackToTheStudioWithoutReconnecting() throws Exception {
        User owner = account("worker-copy@example.com");
        com.afklive.streamer.model.LiveStream stream = liveStreams.create(owner.getEmail(),
                new com.afklive.streamer.dto.StreamDtos.CreateStream("Copy test", null, null, 720, 30));
        List<Long> ids = new java.util.ArrayList<>();
        for (String title : List.of("first.mp4", "second.mp4")) {
            com.afklive.streamer.model.MediaAsset m = new com.afklive.streamer.model.MediaAsset(owner, title, uploadSource(),
                    Files.size(source), com.afklive.streamer.model.MediaAsset.ContentType.VIDEO);
            m.setDurationSeconds(3.0);
            m.setWidth(320);
            m.setHeight(180);
            m.setFps(30.0);
            m.setHasAudio(true);
            ids.add(mediaRepository.save(m).getId());
        }
        liveStreams.setItems(owner.getEmail(), stream.getId(), com.afklive.streamer.model.StreamItem.Kind.VIDEO, ids);
        stream = streamRepository.findById(stream.getId()).orElseThrow();
        Path out = dir.resolve("copy-mode.flv");
        StreamSpec spec = StreamSpec.studio(owner.getEmail(), stream.getId(), liveStreams.studioSpec(stream, null),
                List.of(new StreamSpec.Destination("File", "file://" + out)));
        StreamJob job = job(owner, spec.toJson(), null, null);
        job.setStreamId(stream.getId());
        jobs.save(job);
        long id = job.getId();

        StreamWorker w = newWorker("w-copy");
        w.setCopyRuns(new com.afklive.streamer.streaming.CopyRuns(playoutCopies, mediaCache, streamRepository, jobs, preparer, true));
        w.copyCheckEvery(Duration.ofSeconds(1));
        try {
            // Its files aren't prepared yet: it starts in the studio, and asks for them.
            tickUntil(w, () -> onAir(id), Duration.ofSeconds(60));
            assertThat(jobs.findById(id).orElseThrow().getNowPlaying()).doesNotContain("\"mode\":\"copy\"");
            while (playoutCopies.prepareNext()) {
                // the web side makes them
            }
            // Within a check or two it moves to copy mode, on the same connection.
            tickUntil(w, () -> String.valueOf(jobs.findById(id).orElseThrow().getNowPlaying()).contains("\"mode\":\"copy\""),
                    Duration.ofSeconds(60));
            tickUntil(w, () -> onAir(id), Duration.ofSeconds(20));

            // A live change (skip) needs the studio: it switches back, still on the same connection.
            liveStreams.skip(owner.getEmail(), stream.getId());
            tickUntil(w, () -> {
                w.pollCommands();
                String np = String.valueOf(jobs.findById(id).orElseThrow().getNowPlaying());
                return !np.contains("\"mode\":\"copy\"") && !np.equals("null");
            }, Duration.ofSeconds(60));
            tickUntil(w, () -> onAir(id), Duration.ofSeconds(20));

            assertThat(jobs.findById(id).orElseThrow().getDestinations().get(0).getRestarts())
                    .as("the destination never reconnected").isZero();
            assertThat(jobs.findById(id).orElseThrow().getRestarts()).isZero();
            streamService.stopStream(id, owner.getEmail());
            tickUntil(w, () -> !jobs.findById(id).orElseThrow().isLive(), Duration.ofSeconds(30));
            // One file, one unbroken timeline across studio → copy → studio.
            List<com.afklive.streamer.streaming.TsStitcherTest.Packet> packets = com.afklive.streamer.streaming.TsStitcherTest.probe(out);
            double prev = -1;
            double maxGap = 0;
            for (com.afklive.streamer.streaming.TsStitcherTest.Packet p : packets.stream().filter(p -> p.type().equals("video")).toList()) {
                assertThat(p.dts()).isGreaterThan(prev);
                if (prev >= 0) {
                    maxGap = Math.max(maxGap, p.dts() - prev);
                }
                prev = p.dts();
            }
            assertThat(maxGap).isLessThan(0.25);
        } finally {
            w.shutdown();
            streamService.finalizeJob(id, "test");
        }
    }

    @Test
    void aPhoneStreamShowsBrbWhenTheSignalDropsAndComesBack() throws Exception {
        User owner = account("worker-phone@example.com");
        com.afklive.streamer.model.LiveStream stream = liveStreams.create(owner.getEmail(),
                new com.afklive.streamer.dto.StreamDtos.CreateStream("Phone test", "PHONE", null, 720, 30));
        stream = streamRepository.findById(stream.getId()).orElseThrow();
        int port;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        com.afklive.streamer.streaming.StudioSpec base = liveStreams.studioSpec(stream, null);
        // Instead of an ingest server, the "phone" connects straight to the worker's decoder.
        com.afklive.streamer.streaming.StudioSpec studio = new com.afklive.streamer.streaming.StudioSpec(base.height(), base.vertical(),
                base.fps(), base.source(), base.videos(), base.order(), base.loops(), base.music(), base.musicOrder(),
                base.audioMode(), base.videoVolume(), base.musicVolume(), base.overlays(), base.scenes(), base.initialScene(),
                base.timeZone(), null, new com.afklive.streamer.streaming.StudioSpec.Phone(null, "tcp://127.0.0.1:" + port + "?listen=1", 0), false);
        Path out = dir.resolve("phone.flv");
        StreamJob job = job(owner, StreamSpec.studio(owner.getEmail(), stream.getId(), studio,
                List.of(new StreamSpec.Destination("File", "file://" + out))).toJson(), null, null);
        job.setStreamId(stream.getId());
        jobs.save(job);
        long id = job.getId();
        StreamWorker w = newWorker("w-phone");
        Process phone = null;
        try {
            tickUntil(w, () -> onAir(id), Duration.ofSeconds(30));
            assertThat(jobs.findById(id).orElseThrow().getInputLive()).isNotEqualTo(Boolean.TRUE);
            phone = sendPhone(port);
            tickUntil(w, () -> Boolean.TRUE.equals(jobs.findById(id).orElseThrow().getInputLive()), Duration.ofSeconds(20));
            assertThat(jobs.findById(id).orElseThrow().getScene()).isEqualTo("LIVE");

            phone.destroyForcibly();
            tickUntil(w, () -> Boolean.FALSE.equals(jobs.findById(id).orElseThrow().getInputLive()), Duration.ofSeconds(10));
            assertThat(jobs.findById(id).orElseThrow().getScene()).isEqualTo("BRB");
            assertThat(jobs.findById(id).orElseThrow().isLive()).isTrue(); // the stream itself never stopped

            // The phone reconnects: live again, still the same run.
            phone = sendPhone(port);
            tickUntil(w, () -> Boolean.TRUE.equals(jobs.findById(id).orElseThrow().getInputLive()), Duration.ofSeconds(20));
            assertThat(jobs.findById(id).orElseThrow().getScene()).isEqualTo("LIVE");

            streamService.stopStream(id, owner.getEmail());
            tickUntil(w, () -> !jobs.findById(id).orElseThrow().isLive(), Duration.ofSeconds(30));
            assertThat(jobs.findById(id).orElseThrow().getRestarts()).isZero();
        } finally {
            if (phone != null) {
                phone.destroyForcibly();
            }
            w.shutdown();
            streamService.finalizeJob(id, "test");
        }
    }

    private static Process sendPhone(int port) throws Exception {
        Process p = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30", "-f", "lavfi", "-i", "sine=f=660:r=44100",
                    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "30", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-f", "mpegts", "tcp://127.0.0.1:" + port)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(700, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return p;
            }
            Thread.sleep(300);
        }
        return p;
    }
}
