package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.WorkerStatusRepository;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.streaming.StreamStates;
import com.afklive.streamer.streaming.WorkerPools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FO-8: the claim-time pool filter and preference, straight against PostgreSQL (the same conditional updates
 * the worker uses): phone runs never go to a pool that can't read the ingest, 24/7 playlists wait for their
 * preferred pool only while it can take them, and two pools claiming at once never both get a run.
 */
@SpringBootTest
class WorkerPoolsIntegrationTest extends PostgresIntegrationTest {

    @Autowired StreamJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired WorkerStatusRepository workerStatus;
    @Autowired StreamService streamService;

    private final List<Long> created = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    /** Pool names unique to each test, so workers other tests leave reporting never count. */
    private final String tag = UUID.randomUUID().toString().substring(0, 6);

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
    }

    @AfterEach
    void tidy() {
        for (Long id : created) {
            streamService.finalizeJob(id, "test");
        }
        reported.forEach(workerStatus::remove);
    }

    private User owner() {
        String email = "pools-" + tag + "@example.com";
        return users.findByEmail(email).orElseGet(() -> {
            User u = new User(email);
            u.setPlanType(PlanType.ESSENTIALS);
            return users.save(u);
        });
    }

    /** An unclaimed live run of this placement that went live {@code ago}. */
    private long run(String placement, Duration ago) {
        ZonedDateTime start = now().minus(ago);
        StreamJob job = new StreamJob(owner(), "k", "video", null, null, true, 0, "Pools " + placement, null, null, start, "File");
        job.setLastBillingTime(start);
        job.setStatus(StreamStates.STARTING);
        job.setDesiredState(StreamStates.RUN);
        job.setSpec("{}");
        job.setPlacement(placement);
        long id = jobs.save(job).getId();
        created.add(id);
        return id;
    }

    private String pool(String name) {
        return name + "-" + tag;
    }

    /** A worker of {@code pool} reporting now (or {@code age} ago) with {@code streams} of {@code capacity} in use. */
    private void worker(String id, String pool, int streams, int capacity, Double cpuPercent, Duration age) {
        String workerId = id + "-" + tag;
        ZonedDateTime at = now().minus(age);
        workerStatus.report(workerId, at.minusHours(1), at, streams, capacity, cpuPercent, 1L, 2L, 4, pool);
        reported.add(workerId);
    }

    private WorkerPools.Claim rules(String myPool, String ingestPools, String preference) {
        return WorkerPools.of(myPool, ingestPools, preference).claim(now(), 0.85);
    }

    private boolean claims(String worker, long id, WorkerPools.Claim c) {
        ZonedDateTime now = now();
        boolean listed = jobs.findClaimableIds(worker, now.minusMinutes(1), c).contains(id);
        int claimed = jobs.claimFor(id, worker, now, now.minusMinutes(1), c);
        assertThat(claimed == 1).as("the lookup and the fenced claim agree").isEqualTo(listed);
        return claimed == 1;
    }

    @Test
    void withTheDefaultsEveryWorkerClaimsEveryKindOfRunAsBefore() {
        WorkerPools.Claim defaults = WorkerPools.defaults().claim(now(), 0.85);
        for (String placement : new String[]{null, WorkerPools.PHONE, WorkerPools.PLAYOUT, WorkerPools.PLAYLIST, WorkerPools.OTHER}) {
            long id = run(placement, Duration.ZERO);
            assertThat(claims("main-" + tag, id, defaults)).as(String.valueOf(placement)).isTrue();
        }
    }

    @Test
    void phoneRunsAndRunsOfUnknownKindAreNeverClaimedOrTakenOverByARemotePool() {
        String remotePool = pool("remote");
        WorkerPools.Claim remote = rules(remotePool, "main", "");
        long phone = run(WorkerPools.PHONE, Duration.ZERO);
        long unknown = run(null, Duration.ZERO);
        long playlist = run(WorkerPools.PLAYOUT, Duration.ZERO);

        assertThat(claims("remote-" + tag, phone, remote)).isFalse();
        assertThat(claims("remote-" + tag, unknown, remote)).isFalse();
        assertThat(claims("remote-" + tag, playlist, remote)).isTrue();

        // Even after waiting a long time, and even as an orphan: the hard rule has no grace.
        long oldPhone = run(WorkerPools.PHONE, Duration.ofHours(1));
        assertThat(claims("remote-" + tag, oldPhone, remote)).isFalse();
        ZonedDateTime cutoff = now().minusSeconds(45);
        StreamJob orphan = jobs.findById(oldPhone).orElseThrow();
        orphan.setWorkerId("dead-" + tag);
        orphan.setHeartbeatAt(now().minusMinutes(5));
        jobs.save(orphan);
        assertThat(jobs.findOrphanIdsFor(cutoff, "remote-" + tag, false)).doesNotContain(oldPhone);
        assertThat(jobs.takeOverFor(oldPhone, "remote-" + tag, now(), cutoff, false)).isZero();
        // A main-pool worker rescues it.
        assertThat(jobs.findOrphanIdsFor(cutoff, "main-" + tag, true)).contains(oldPhone);
        assertThat(jobs.takeOverFor(oldPhone, "main-" + tag, now(), cutoff, true)).isEqualTo(1);
        assertThat(jobs.findById(oldPhone).orElseThrow().getWorkerId()).isEqualTo("main-" + tag);
    }

    @Test
    void aPlayoutRunWaitsForItsPreferredPoolWhileThatPoolHasRoom() {
        String playout = pool("playout");
        worker("po-1", playout, 1, 4, 20.0, Duration.ZERO);
        WorkerPools.Claim main = rules("main", "main", "PLAYOUT=" + playout);
        WorkerPools.Claim po = rules(playout, "main", "PLAYOUT=" + playout);

        long id = run(WorkerPools.PLAYOUT, Duration.ZERO);
        assertThat(claims("main-" + tag, id, main)).as("left for the playout pool").isFalse();
        assertThat(claims("po-1-" + tag, id, po)).isTrue();

        // Only the preferred kinds wait: a studio playlist and a phone run are claimed by main at once.
        long studio = run(WorkerPools.PLAYLIST, Duration.ZERO);
        assertThat(claims("main-" + tag, studio, main)).isTrue();
        long phone = run(WorkerPools.PHONE, Duration.ZERO);
        assertThat(claims("main-" + tag, phone, main)).isTrue();
    }

    @Test
    void aPreferredRunIsClaimedAtOnceWhenItsPoolIsFullBusyDrainingOrGone() {
        String playout = pool("playout");
        WorkerPools.Claim main = rules("main", "main", "PLAYOUT=" + playout + ",PLAYLIST=" + playout);

        // Absent: no worker of that pool has ever reported.
        assertThat(claims("main-" + tag, run(WorkerPools.PLAYOUT, Duration.ZERO), main)).as("absent").isTrue();

        worker("po-full", playout, 4, 4, 20.0, Duration.ZERO);
        assertThat(claims("main-" + tag, run(WorkerPools.PLAYOUT, Duration.ZERO), main)).as("full").isTrue();

        worker("po-busy", playout, 0, 4, 95.0, Duration.ZERO);
        assertThat(claims("main-" + tag, run(WorkerPools.PLAYLIST, Duration.ZERO), main)).as("CPU busy").isTrue();

        worker("po-stale", playout, 0, 4, 10.0, Duration.ofMinutes(2));
        assertThat(claims("main-" + tag, run(WorkerPools.PLAYOUT, Duration.ZERO), main)).as("stopped reporting").isTrue();

        worker("po-drain", playout, 0, 4, 10.0, Duration.ZERO);
        workerStatus.setDraining("po-drain-" + tag, now(), now());
        assertThat(claims("main-" + tag, run(WorkerPools.PLAYOUT, Duration.ZERO), main)).as("draining").isTrue();

        // A free worker appears: new playout runs wait for it again...
        worker("po-free", playout, 0, 4, 10.0, Duration.ZERO);
        long waiting = run(WorkerPools.PLAYOUT, Duration.ZERO);
        assertThat(claims("main-" + tag, waiting, main)).isFalse();
        // ...but not forever: one that has waited past the grace starts anywhere.
        long waitedLong = run(WorkerPools.PLAYOUT, WorkerPools.PREFERENCE_GRACE.plusSeconds(5));
        assertThat(claims("main-" + tag, waitedLong, main)).isTrue();
    }

    @Test
    void handoverOffersFollowTheSameRules() {
        String playout = pool("playout");
        worker("po-new", playout, 0, 4, 10.0, Duration.ZERO);
        WorkerPools.Claim main = rules("main", "main", "PLAYOUT=" + playout);
        WorkerPools.Claim po = rules(playout, "main", "PLAYOUT=" + playout);
        String old = "po-old-" + tag;

        long playlist = run(WorkerPools.PLAYOUT, Duration.ofHours(2));
        long phone = run(WorkerPools.PHONE, Duration.ofHours(2));
        for (long id : new long[]{playlist, phone}) {
            StreamJob j = jobs.findById(id).orElseThrow();
            j.setWorkerId(old);
            j.setHeartbeatAt(now());
            jobs.save(j);
            assertThat(jobs.offerHandover(id, old, now(), null)).isEqualTo(1);
        }

        // The phone run: never to the remote pool; main takes it.
        assertThat(jobs.findHandoverOfferIdsFor("po-new-" + tag, po)).doesNotContain(phone);
        assertThat(jobs.claimHandoverFor(phone, "po-new-" + tag, now(), po)).isZero();
        // The playout run: main leaves it to the playout pool's new version while it has room.
        assertThat(jobs.findHandoverOfferIdsFor("main-" + tag, main)).contains(phone).doesNotContain(playlist);
        assertThat(jobs.claimHandoverFor(playlist, "main-" + tag, now(), main)).isZero();
        assertThat(jobs.claimHandoverFor(playlist, "po-new-" + tag, now(), po)).isEqualTo(1);
        assertThat(jobs.claimHandoverFor(phone, "main-" + tag, now(), main)).isEqualTo(1);
    }

    @Test
    void twoPoolsClaimingAtOnceNeverBothGetARunAndTheRemoteOneNeverGetsAPhoneRun() throws Exception {
        String remotePool = pool("remote");
        Map<String, WorkerPools.Claim> claimers = Map.of(
                "main-a-" + tag, rules("main", "main", ""),
                "main-b-" + tag, rules("main", "main", ""),
                "remote-a-" + tag, rules(remotePool, "main", ""),
                "remote-b-" + tag, rules(remotePool, "main", ""));
        Set<Long> mine = new HashSet<>();
        Set<Long> phoneOrUnknown = new HashSet<>();
        String[] kinds = {WorkerPools.PHONE, null, WorkerPools.PLAYOUT, WorkerPools.PLAYLIST, WorkerPools.OTHER};
        for (int i = 0; i < 30; i++) {
            String kind = kinds[i % kinds.length];
            long id = run(kind, Duration.ZERO);
            mine.add(id);
            if (kind == null || WorkerPools.PHONE.equals(kind)) {
                phoneOrUnknown.add(id);
            }
        }
        Map<Long, String> winners = new ConcurrentHashMap<>();
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(claimers.size());
        try {
            List<Future<?>> done = new ArrayList<>();
            claimers.forEach((worker, rules) -> done.add(pool.submit(() -> {
                go.await();
                ZonedDateTime now = now();
                List<Long> ids = new ArrayList<>(jobs.findClaimableIds(worker, now.minusMinutes(1), rules));
                ids.retainAll(mine);
                java.util.Collections.shuffle(ids);
                for (Long id : ids) {
                    if (jobs.claimFor(id, worker, now, now.minusMinutes(1), rules) == 1) {
                        successes.incrementAndGet();
                        String previous = winners.putIfAbsent(id, worker);
                        assertThat(previous).as("run %s claimed twice", id).isNull();
                    }
                }
                return null;
            })));
            go.countDown();
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(successes.get()).isEqualTo(mine.size()).isEqualTo(winners.size());
        for (Long id : mine) {
            String owner = jobs.findById(id).orElseThrow().getWorkerId();
            assertThat(owner).isEqualTo(winners.get(id));
            if (phoneOrUnknown.contains(id)) {
                assertThat(owner).as("phone run %s", id).startsWith("main-");
            }
        }
    }
}
