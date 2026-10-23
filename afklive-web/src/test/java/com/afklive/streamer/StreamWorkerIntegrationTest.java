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

    /** A separate worker process, as far as coordination goes (own id, own first pass). */
    private StreamWorker newWorker(String id) {
        return new StreamWorker(jobs, destinations, streamService, preparer, storage, youTubeService, id, 5, 500, false);
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
}
