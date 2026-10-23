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
import com.afklive.streamer.streaming.Handover;
import com.afklive.streamer.streaming.IngestRouting;
import com.afklive.streamer.streaming.StreamPreparer;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StreamStates;
import com.afklive.streamer.streaming.StreamWorker;
import com.afklive.streamer.streaming.StudioSpec;
import com.afklive.streamer.streaming.YouTubeIngest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-3: a stream moves from a draining worker (the old version) to another worker (the new one) with
 * real FFmpeg on both sides. Platforms are local TCP sinks that record every connection. "YouTube" is a
 * pair of sinks standing in for its primary and backup ingest (production routes real YouTube URLs;
 * that choice is unit tested in YouTubeIngestTest); the other destination has no backup, like Twitch.
 */
@SpringBootTest(properties = "app.streaming.work-dir=${java.io.tmpdir}/afklive-test-worker")
class WorkerHandoverIntegrationTest extends PostgresIntegrationTest {

    @TempDir
    static Path dir;
    static Path source;
    /** A minute long, so a playlist position can be told apart from a fresh start. */
    static Path longSource;

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
    @Autowired ApplicationContext context;

    private final List<StreamWorker> workers = new ArrayList<>();
    private final List<Sink> sinks = new ArrayList<>();

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
        longSource = dir.resolve("long.mp4");
        Process q = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "60", "-c:v", "libx264", "-preset", "ultrafast", "-g", "60",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", longSource.toString()).redirectErrorStream(true).start();
        q.getInputStream().readAllBytes();
        assertThat(q.waitFor(120, TimeUnit.SECONDS)).isTrue();
    }

    @BeforeEach
    void noLeftoverStreams() {
        // Workers here claim any live stream in the database: start from none.
        for (StreamJob j : jobs.findAllByIsLiveTrue()) {
            streamService.finalizeJob(j.getId(), "test");
        }
    }

    @AfterEach
    void tidy() {
        for (StreamWorker w : workers) {
            w.shutdown();
        }
        for (StreamJob j : jobs.findAllByIsLiveTrue()) {
            streamService.finalizeJob(j.getId(), "test");
        }
        sinks.forEach(Sink::close);
    }

    // ------------------------------------------------------------------ helpers

    /** A worker process as far as coordination goes, routing "YouTube" to the given sinks. */
    private StreamWorker worker(String id, Sink primary, Sink backup, Duration timeout) {
        StreamWorker w = new StreamWorker(jobs, destinations, streamService, preparer, storage, youTubeService, studioRuns,
                commandRepository, id, 5, 500, false);
        w.setIngestRouting(new IngestRouting() {
            @Override
            public boolean hasBackup(String url) {
                return url.equals(primary.url()) || url.equals(backup.url());
            }

            @Override
            public String route(String url, YouTubeIngest.Ingest ingest) {
                return hasBackup(url) ? (ingest == YouTubeIngest.Ingest.BACKUP ? backup.url() : primary.url()) : url;
            }
        });
        w.setHandoverSettings(new Handover.Settings(timeout, Duration.ofSeconds(3), Duration.ofSeconds(6)));
        workers.add(w);
        return w;
    }

    private Sink sink() throws IOException {
        Sink s = new Sink();
        sinks.add(s);
        return s;
    }

    private User account(String email) {
        return users.findByEmail(email).orElseGet(() -> {
            User u = new User(email);
            u.setPlanType(PlanType.ESSENTIALS);
            return users.save(u);
        });
    }

    private String uploadSource() throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            return storage.uploadFile(in, "src.mp4", Files.size(source));
        }
    }

    private static List<StreamSpec.Destination> targets(Sink youTube, Sink other) {
        List<StreamSpec.Destination> out = new ArrayList<>();
        out.add(new StreamSpec.Destination("YouTube", youTube.url()));
        if (other != null) {
            out.add(new StreamSpec.Destination("Other", other.url()));
        }
        return out;
    }

    /** A single-file stream (cheap to encode) that loops forever. */
    private StreamSpec fileSpec(User owner, List<StreamSpec.Destination> to) throws Exception {
        return new StreamSpec(owner.getEmail(), uploadSource(), null, "1.0", -1, null, false, "original", 360,
                false, null, null, to);
    }

    private StreamJob job(User owner, String spec, int destinationCount) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamJob job = new StreamJob(owner, "k", "video", null, null, true, 0, null, null, null, now, "File", false);
        job.setLastBillingTime(now);
        job.setStatus(StreamStates.STARTING);
        job.setDesiredState(StreamStates.RUN);
        job.setSpec(spec);
        for (int i = 0; i < destinationCount; i++) {
            job.getDestinations().add(new StreamJobDestination(job, i, "Dest " + i));
        }
        return jobs.save(job);
    }

    private StreamJob row(long id) {
        return jobs.findById(id).orElseThrow();
    }

    private boolean onAir(long id) {
        StreamJob j = row(id);
        return StreamStates.LIVE.equals(j.getStatus()) && j.getBitrateKbps() != null && j.getBitrateKbps() > 0;
    }

    /** Runs the given workers' loops (and the new worker's half-second handover poll) until the condition holds. */
    private static void runUntil(BooleanSupplier condition, Duration timeout, StreamWorker... ws) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (StreamWorker w : ws) {
                w.tick();
                w.pollHandovers();
            }
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    private static void runFor(Duration d, StreamWorker... ws) throws InterruptedException {
        long deadline = System.nanoTime() + d.toNanos();
        while (System.nanoTime() < deadline) {
            for (StreamWorker w : ws) {
                w.tick();
                w.pollHandovers();
            }
            Thread.sleep(250);
        }
    }

    private static void assertNoHandover(StreamJob j) {
        assertThat(j.getHandoverFrom()).isNull();
        assertThat(j.getHandoverTo()).isNull();
        assertThat(j.getHandoverBeatAt()).isNull();
        assertThat(j.getHandoverReadyAt()).isNull();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aDrainingWorkerHandsAPlaylistStreamOverWithoutABreak() throws Exception {
        Sink ytPrimary = sink();
        Sink ytBackup = sink();
        Sink other = sink();
        User owner = account("handover-ok@example.com");
        com.afklive.streamer.model.LiveStream stream = liveStreams.create(owner.getEmail(),
                new com.afklive.streamer.dto.StreamDtos.CreateStream("Handover", null, null, 720, 30));
        String longKey;
        try (InputStream in = Files.newInputStream(longSource)) {
            longKey = storage.uploadFile(in, "long.mp4", Files.size(longSource));
        }
        List<Long> ids = List.of(mediaRepository.save(new com.afklive.streamer.model.MediaAsset(owner, "long.mp4", longKey,
                Files.size(longSource), com.afklive.streamer.model.MediaAsset.ContentType.VIDEO)).getId());
        liveStreams.setItems(owner.getEmail(), stream.getId(), com.afklive.streamer.model.StreamItem.Kind.VIDEO, ids);
        stream = streamRepository.findById(stream.getId()).orElseThrow();
        long streamId = stream.getId();
        StudioSpec base = liveStreams.studioSpec(stream, null);
        // 360p keeps two encodes cheap on a test machine.
        StudioSpec studio = new StudioSpec(360, base.vertical(), base.fps(), base.source(), base.videos(), base.order(), base.loops(),
                base.music(), base.musicOrder(), base.audioMode(), base.videoVolume(), base.musicVolume(), base.overlays(),
                base.scenes(), base.initialScene(), base.timeZone(), null, base.phone(), false);
        StreamJob job = job(owner, StreamSpec.studio(owner.getEmail(), stream.getId(), studio, targets(ytPrimary, other)).toJson(), 2);
        job.setStreamId(stream.getId());
        jobs.save(job);
        long id = job.getId();

        StreamWorker oldWorker = worker("w-old", ytPrimary, ytBackup, Duration.ofSeconds(40));
        StreamWorker newWorker = worker("w-new", ytPrimary, ytBackup, Duration.ofSeconds(40));
        runUntil(() -> onAir(id) && ytPrimary.receiving() && other.receiving(), Duration.ofSeconds(60), oldWorker);
        // Play long enough to be well into the video.
        runFor(Duration.ofSeconds(8), oldWorker);

        // Nothing is offered until the old worker drains; the new one just waits.
        runFor(Duration.ofSeconds(1), oldWorker, newWorker);
        assertThat(newWorker.runningCount()).isZero();
        long drainedAt = System.nanoTime();
        oldWorker.drain();
        runUntil(() -> "w-new".equals(row(id).getWorkerId()) && oldWorker.runningCount() == 0, Duration.ofSeconds(60),
                oldWorker, newWorker);

        StreamJob moved = row(id);
        assertThat(moved.isLive()).isTrue();
        assertThat(moved.getRestarts()).isZero(); // a planned move, not a crash takeover
        assertThat(moved.getYoutubeIngest()).isEqualTo("BACKUP");
        assertNoHandover(moved);
        assertThat(newWorker.ownedCount()).isEqualTo(1);

        // Other destination: the old connection closed before the new one opened (one publisher at a time),
        // and the gap was short.
        runUntil(() -> other.connections.size() == 2 && other.receiving(), Duration.ofSeconds(15), newWorker, oldWorker);
        Sink.Conn first = other.connections.get(0);
        Sink.Conn second = other.connections.get(1);
        assertThat(first.closedNanos).isPositive();
        assertThat(second.openedNanos).isGreaterThanOrEqualTo(first.closedNanos);
        assertThat(Duration.ofNanos(second.openedNanos - first.closedNanos)).isLessThan(Duration.ofSeconds(4));
        assertThat(other.maxConcurrent()).isEqualTo(1);

        // "YouTube": the backup ingest was receiving the new copy for about the ready time (3 s here) before
        // the primary dropped. (A relay writes its first bytes once it has analysed the input, ~3 s after start.)
        assertThat(ytPrimary.connections).hasSize(1);
        assertThat(ytBackup.connections).hasSize(1);
        Sink.Conn primaryConn = ytPrimary.connections.get(0);
        Sink.Conn backupConn = ytBackup.connections.get(0);
        assertThat(primaryConn.closedNanos).isPositive();
        assertThat(backupConn.firstByteNanos).isPositive();
        assertThat(Duration.ofNanos(primaryConn.closedNanos - backupConn.firstByteNanos)).isGreaterThan(Duration.ofSeconds(2));
        assertThat(backupConn.closedNanos).isZero();

        // The new copy continued where the old one was instead of starting the video over.
        runUntil(() -> streamRepository.findById(streamId).orElseThrow().getResumePosition() != null, Duration.ofSeconds(20), newWorker);
        StudioSpec.Position resumed = StudioSpec.parse(streamRepository.findById(streamId).orElseThrow().getResumePosition(),
                StudioSpec.Position.class);
        double sinceDrain = (System.nanoTime() - drainedAt) / 1e9;
        assertThat(resumed.loopsCompleted()).isZero();
        // A fresh start would be at most "time since the drain" into the video; the old copy was over 8 s in.
        assertThat(resumed.offsetSeconds()).isGreaterThan(sinceDrain + 4);

        // It stays put: the new worker keeps reporting, the old one never takes it back.
        runFor(Duration.ofSeconds(3), oldWorker, newWorker);
        assertThat(row(id).getWorkerId()).isEqualTo("w-new");
        assertThat(oldWorker.runningCount()).isZero();
        assertThat(newWorker.runningCount()).isEqualTo(1);
        assertThat(ytBackup.receiving()).isTrue();
        assertThat(ytPrimary.openCount()).isZero();
        runUntil(() -> row(id).getDestinations().stream().allMatch(d -> "LIVE".equals(d.getStatus())), Duration.ofSeconds(10), newWorker);

        streamService.stopStream(id, owner.getEmail());
        runUntil(() -> !row(id).isLive(), Duration.ofSeconds(30), newWorker, oldWorker);
        assertThat(row(id).getStatus()).isEqualTo(StreamStates.ENDED);
    }

    @Test
    void aHandoverThatNeverGetsOnAirIsAbandonedAndTheOldWorkerCarriesOn() throws Exception {
        Sink ytPrimary = sink();
        Sink ytBackup = sink();
        ytBackup.close(); // the new copy can't reach the backup ingest
        User owner = account("handover-abort@example.com");
        long id = job(owner, fileSpec(owner, targets(ytPrimary, null)).toJson(), 1).getId();
        StreamWorker oldWorker = worker("w-old-a", ytPrimary, ytBackup, Duration.ofSeconds(8));
        StreamWorker newWorker = worker("w-new-a", ytPrimary, ytBackup, Duration.ofSeconds(8));
        runUntil(() -> onAir(id) && ytPrimary.receiving(), Duration.ofSeconds(40), oldWorker);

        oldWorker.drain();
        runUntil(() -> newWorker.incomingCount() == 1, Duration.ofSeconds(10), oldWorker, newWorker);
        assertThat(row(id).getHandoverTo()).isEqualTo("w-new-a");
        runUntil(() -> newWorker.runningCount() == 0 && row(id).getHandoverTo() == null, Duration.ofSeconds(20), oldWorker, newWorker);

        // The old worker still owns and runs it, and its connection never dropped.
        runFor(Duration.ofSeconds(2), oldWorker, newWorker);
        StreamJob j = row(id);
        assertThat(j.getWorkerId()).isEqualTo("w-old-a");
        assertThat(j.isLive()).isTrue();
        assertThat(j.getYoutubeIngest()).isNull();
        assertThat(oldWorker.runningCount()).isEqualTo(1);
        assertThat(newWorker.runningCount()).isZero(); // and doesn't retry straight away
        assertThat(ytPrimary.connections).hasSize(1);
        assertThat(ytPrimary.receiving()).isTrue();

        // The deploy is called off: the offer is withdrawn.
        oldWorker.stopDraining();
        runFor(Duration.ofSeconds(1), oldWorker, newWorker);
        assertNoHandover(row(id));
    }

    @Test
    void aClaimerThatDiesIsDroppedAndTheStreamNeverStops() throws Exception {
        Sink ytPrimary = sink();
        Sink ytBackup = sink();
        User owner = account("handover-ghost@example.com");
        long id = job(owner, fileSpec(owner, targets(ytPrimary, null)).toJson(), 1).getId();
        StreamWorker oldWorker = worker("w-old-g", ytPrimary, ytBackup, Duration.ofSeconds(30));
        runUntil(() -> onAir(id) && ytPrimary.receiving(), Duration.ofSeconds(40), oldWorker);
        oldWorker.drain();
        runUntil(() -> row(id).getHandoverFrom() != null, Duration.ofSeconds(5), oldWorker);

        // A worker claims it and dies straight away (never beats again).
        assertThat(jobs.claimHandover(id, "ghost", ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS))).isEqualTo(1);
        runUntil(() -> row(id).getHandoverTo() == null, Duration.ofSeconds(15), oldWorker);
        assertThat(row(id).getHandoverFrom()).isEqualTo("w-old-g"); // still offered
        assertThat(row(id).getWorkerId()).isEqualTo("w-old-g");
        assertThat(ytPrimary.connections).hasSize(1);
        assertThat(ytPrimary.receiving()).isTrue();

        // A live worker then takes it the planned way.
        StreamWorker newWorker = worker("w-new-g", ytPrimary, ytBackup, Duration.ofSeconds(30));
        runUntil(() -> "w-new-g".equals(row(id).getWorkerId()) && oldWorker.runningCount() == 0, Duration.ofSeconds(40),
                oldWorker, newWorker);
        assertThat(row(id).getRestarts()).isZero();
        assertThat(ytBackup.receiving()).isTrue();
    }

    @Test
    void anOwnerThatDiesMidHandoverIsReplacedByTheCopyAlreadyOnAir() throws Exception {
        Sink ytPrimary = sink();
        Sink ytBackup = sink();
        Sink other = sink();
        User owner = account("handover-owner-dies@example.com");
        long id = job(owner, fileSpec(owner, targets(ytPrimary, other)).toJson(), 2).getId();
        StreamWorker oldWorker = worker("w-old-d", ytPrimary, ytBackup, Duration.ofSeconds(40));
        StreamWorker newWorker = worker("w-new-d", ytPrimary, ytBackup, Duration.ofSeconds(40));
        runUntil(() -> onAir(id) && ytPrimary.receiving() && other.receiving(), Duration.ofSeconds(40), oldWorker);
        oldWorker.drain();
        oldWorker.tick(); // offers it
        // From here the old worker is frozen (its process hangs or the machine is cut off): it never ticks.
        runUntil(() -> row(id).getHandoverReadyAt() != null, Duration.ofSeconds(30), newWorker);
        assertThat(row(id).getWorkerId()).isEqualTo("w-old-d");
        assertThat(other.connections).hasSize(1); // the new copy hasn't touched the single-publisher destination

        // Its heartbeat goes stale (45 s in production; aged here).
        StreamJob j = row(id);
        j.setHeartbeatAt(ZonedDateTime.now(ZoneId.of("UTC")).minusMinutes(2));
        jobs.save(j);
        runUntil(() -> "w-new-d".equals(row(id).getWorkerId()), Duration.ofSeconds(10), newWorker);
        assertThat(row(id).getRestarts()).isEqualTo(1); // counted as a takeover
        assertNoHandover(row(id));
        // The copy was already on the backup ingest: same connection, no restart.
        assertThat(ytBackup.connections).hasSize(1);
        assertThat(ytBackup.receiving()).isTrue();

        // The old process wakes up: its fenced heartbeat fails and it stops its copy. Only one owner.
        oldWorker.tick();
        assertThat(oldWorker.runningCount()).isZero();
        runUntil(() -> ytPrimary.openCount() == 0 && other.receiving(), Duration.ofSeconds(15), newWorker, oldWorker);
        assertThat(row(id).getWorkerId()).isEqualTo("w-new-d");
        assertThat(newWorker.runningCount()).isEqualTo(1);
    }

    @Test
    void aDrainingWorkerClaimsNothingAndOnShutdownLetsItsStreamsGoAtOnce() throws Exception {
        Sink ytPrimary = sink();
        Sink ytBackup = sink();
        User owner = account("handover-release@example.com");
        long id = job(owner, fileSpec(owner, targets(ytPrimary, null)).toJson(), 1).getId();
        StreamWorker oldWorker = worker("w-old-r", ytPrimary, ytBackup, Duration.ofSeconds(30));
        runUntil(() -> onAir(id) && ytPrimary.receiving(), Duration.ofSeconds(40), oldWorker);

        oldWorker.drain();
        long waiting = job(owner, fileSpec(owner, targets(sink(), null)).toJson(), 1).getId();
        runFor(Duration.ofSeconds(1), oldWorker);
        assertThat(row(waiting).getWorkerId()).isNull(); // a draining worker takes on nothing new

        // The handover didn't happen in time (no other worker): the deploy stops the old one anyway.
        oldWorker.shutdown();
        StreamJob released = row(id);
        assertThat(released.isLive()).isTrue();
        assertThat(released.getWorkerId()).isNull();
        assertNoHandover(released);
        assertThat(ytPrimary.openCount()).isZero(); // disconnected before letting go

        // The new worker picks both up on its next pass, without waiting for the takeover timeout.
        StreamWorker newWorker = worker("w-new-r", ytPrimary, ytBackup, Duration.ofSeconds(30));
        newWorker.tick();
        assertThat(row(id).getWorkerId()).isEqualTo("w-new-r");
        assertThat(row(waiting).getWorkerId()).isEqualTo("w-new-r");
        assertThat(row(id).getRestarts()).isZero();
        runUntil(() -> onAir(id) && ytPrimary.receiving(), Duration.ofSeconds(40), newWorker);
    }

    @Test
    void aRestartedWorkerDropsItsOldOfferAndResumesItsStream() throws Exception {
        Sink ytPrimary = sink();
        Sink ytBackup = sink();
        User owner = account("handover-restart@example.com");
        StreamJob job = job(owner, fileSpec(owner, targets(ytPrimary, null)).toJson(), 1);
        long id = job.getId();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        job = row(id);
        job.setWorkerId("w-restart");
        job.setHeartbeatAt(now);
        jobs.save(job);
        // It was draining when it went down (the deploy fell back to a restart).
        assertThat(jobs.offerHandover(id, "w-restart", now, null)).isEqualTo(1);

        StreamWorker restarted = worker("w-restart", ytPrimary, ytBackup, Duration.ofSeconds(30));
        StreamWorker other = worker("w-other", ytPrimary, ytBackup, Duration.ofSeconds(30));
        restarted.tick();
        assertThat(restarted.runningCount()).isEqualTo(1);
        assertNoHandover(row(id));
        other.tick();
        assertThat(other.runningCount()).isZero(); // nothing left to claim
        runUntil(() -> onAir(id), Duration.ofSeconds(40), restarted, other);
        assertThat(row(id).getWorkerId()).isEqualTo("w-restart");
    }

    @Test
    void theDrainEndpointOnlyExistsInTheWorkerProcess() {
        assertThat(context.getBeanNamesForType(com.afklive.streamer.endpoint.WorkerControlController.class)).isEmpty();
    }

    // ------------------------------------------------------------------ a local "platform"

    /** Accepts connections on localhost and records when each opened, first received data, and closed. */
    static final class Sink implements AutoCloseable {
        static final class Conn {
            final long openedNanos = System.nanoTime();
            volatile long firstByteNanos;
            volatile long lastByteNanos;
            volatile long closedNanos;
            final AtomicLong bytes = new AtomicLong();
        }

        final ServerSocket server;
        final List<Conn> connections = new CopyOnWriteArrayList<>();
        private final String url;

        Sink() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            url = "tcp://127.0.0.1:" + server.getLocalPort();
            Thread.ofPlatform().daemon(true).name("sink-" + server.getLocalPort()).start(this::accept);
        }

        String url() {
            return url;
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Conn c = new Conn();
                    connections.add(c);
                    Thread.ofPlatform().daemon(true).start(() -> read(s, c));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void read(Socket s, Conn c) {
            byte[] buf = new byte[64 * 1024];
            try (s; InputStream in = s.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    long t = System.nanoTime();
                    if (c.firstByteNanos == 0) {
                        c.firstByteNanos = t;
                    }
                    c.lastByteNanos = t;
                    c.bytes.addAndGet(n);
                }
            } catch (IOException ignored) {
                // closed
            } finally {
                c.closedNanos = System.nanoTime();
            }
        }

        int openCount() {
            return (int) connections.stream().filter(c -> c.closedNanos == 0).count();
        }

        /** Some open connection received data in the last two seconds. */
        boolean receiving() {
            long now = System.nanoTime();
            return connections.stream().anyMatch(c -> c.closedNanos == 0 && c.lastByteNanos != 0
                    && now - c.lastByteNanos < Duration.ofSeconds(2).toNanos());
        }

        /** The most connections that were open at the same moment. */
        int maxConcurrent() {
            int max = 0;
            for (Conn c : connections) {
                long t = c.openedNanos;
                int open = (int) connections.stream()
                        .filter(o -> o.openedNanos <= t && (o.closedNanos == 0 || o.closedNanos > t)).count();
                max = Math.max(max, open);
            }
            return max;
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
