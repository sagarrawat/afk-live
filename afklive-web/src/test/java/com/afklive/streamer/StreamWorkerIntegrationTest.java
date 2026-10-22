package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.StreamService;
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
@SpringBootTest
class StreamWorkerIntegrationTest extends PostgresIntegrationTest {

    @TempDir
    static Path dir;
    static Path source;

    @Autowired StreamWorker worker;
    @Autowired StreamService streamService;
    @Autowired StreamJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired FileStorageService storage;

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
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            worker.tick();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    @Test
    void anOrphanedStreamIsTakenOverAndRunsUntilStopped() throws Exception {
        User owner = account("worker-e2e@example.com");
        String videoKey;
        try (InputStream in = Files.newInputStream(source)) {
            videoKey = storage.uploadFile(in, "src.mp4", Files.size(source));
        }
        Path out = dir.resolve("e2e.flv");
        StreamSpec spec = new StreamSpec(owner.getEmail(), videoKey, null, "1.0", -1, null, false, "original", 360,
                false, null, null, List.of(new StreamSpec.Destination("File", "file://" + out)));
        // Owned by a worker that stopped reporting two minutes ago.
        StreamJob job = job(owner, spec.toJson(), "dead-worker", ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(2));
        long id = job.getId();

        tickUntil(() -> {
            StreamJob j = jobs.findById(id).orElseThrow();
            return StreamStates.LIVE.equals(j.getStatus()) && j.getBitrateKbps() != null && j.getBitrateKbps() > 0;
        }, Duration.ofSeconds(40));

        StreamJob live = jobs.findById(id).orElseThrow();
        assertThat(live.getWorkerId()).isEqualTo(worker.workerId());
        assertThat(live.getRestarts()).isEqualTo(1); // counted as a takeover
        assertThat(live.getFps()).isPositive();
        assertThat(Files.size(out)).isPositive();
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
}
