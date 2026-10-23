package com.afklive.streamer.service;

import com.afklive.streamer.model.DestinationTest;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.DestinationTestRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.util.NetworkSafety;
import com.afklive.streamer.util.Redact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * MS-6: "Test" for a destination. The web app records a request; a stream worker (which has the
 * egress firewall, so a test can't be pointed inside our network) sends a 10-second test pattern;
 * the web app reads the result. If the platform accepts the connection and the key for the whole
 * time, the destination works, and YouTube Studio shows the test as "receiving".
 */
@Service
@Slf4j
public class DestinationTestService {

    static final int TEST_SECONDS = 10;
    /** How many tests a user may request per hour. */
    static final int PER_HOUR = 6;
    /** A test nobody picked up, or whose worker died, fails after this long. */
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(2);

    private final DestinationTestRepository tests;
    private final StreamJobRepository jobs;
    private final Function<String, List<String>> command;
    /** How long a test may take before it's abandoned. */
    long timeoutSeconds = TEST_SECONDS + 20L;

    @Autowired
    public DestinationTestService(DestinationTestRepository tests, StreamJobRepository jobs) {
        this(tests, jobs, DestinationTestService::ffmpegTest);
    }

    DestinationTestService(DestinationTestRepository tests, StreamJobRepository jobs, Function<String, List<String>> command) {
        this.tests = tests;
        this.jobs = jobs;
        this.command = command;
    }

    // ------------------------------------------------------------------ web side

    /** Records a test for a worker to run; the caller polls {@link #result}. */
    public DestinationTest request(User user, StreamDestination d) {
        String key = d.getStreamKey();
        NetworkSafety.requireSafeStreamDestination(key);
        if (isLive(user.getId(), key)) {
            throw new com.afklive.streamer.exception.ConflictException("This destination is live right now. Test it when the stream has stopped.");
        }
        if (tests.existsByUserIdAndStatusIn(user.getId(), List.of(DestinationTest.Status.PENDING, DestinationTest.Status.RUNNING))) {
            throw new com.afklive.streamer.exception.ConflictException("A test is already running. Wait for it to finish.");
        }
        if (tests.countByUserIdAndCreatedAtAfter(user.getId(), ZonedDateTime.now(ZoneId.of("UTC")).minusHours(1)) >= PER_HOUR) {
            throw new IllegalStateException("You've run " + PER_HOUR + " tests in the last hour. Try again later.");
        }
        return create(user, d);
    }

    private DestinationTest create(User user, StreamDestination d) {
        DestinationTest t = new DestinationTest();
        t.setUserId(user.getId());
        t.setDestinationId(d.getId());
        t.setUrl(d.getStreamKey());
        t.setCreatedAt(ZonedDateTime.now(ZoneId.of("UTC")));
        return tests.save(t);
    }

    /**
     * Whether one of the user's live runs sends to this key. Two connections on one key interfere (the
     * platform drops one of them), so a test never runs against a key that is live.
     */
    public boolean isLive(Long userId, String key) {
        if (userId == null || key == null) {
            return false;
        }
        for (StreamJob j : jobs.findAllLiveByUserId(userId)) {
            try {
                if (j.getSpec() != null && StreamSpec.fromJson(j.getSpec()).destinations().stream()
                        .anyMatch(x -> x.url() != null && key.trim().equals(x.url().trim()))) {
                    return true;
                }
            } catch (Exception ignored) {
                // a legacy run without a readable spec
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ GL-1: checking destinations before going live

    /** A passed test counts for this long, as long as the key hasn't changed since. */
    static final Duration FRESH_FOR = Duration.ofHours(24);
    /** A failed test is shown again rather than re-run when Go Live is pressed again this soon. */
    static final Duration FAILED_REUSE = Duration.ofSeconds(60);
    /** Go Live checks a user may start per hour (each destination counts). */
    static final int GO_LIVE_TESTS_PER_HOUR = 30;
    static final String LIVE_NOW = "Not tested: the stream went live on this destination.";

    public static final String PASSED = "PASSED";
    public static final String TESTING = "TESTING";
    public static final String FAILED = "FAILED";
    public static final String UNTESTED = "UNTESTED";

    /** One destination's state before going live. {@code testId}: the test that decided it, if any. */
    public record Check(Long destinationId, String name, String platform, String state, String message, Long testId) {
    }

    /**
     * GL-1: for each destination, whether it passed a test in the last 24 hours with its current key.
     * With {@code start}, destinations without such a pass get a test (one already running is reused),
     * so a stream whose destinations are all fresh goes live without waiting for anything.
     */
    public List<Check> goLiveChecks(User user, List<StreamDestination> destinations, boolean start) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        tests.expire("No stream server could run the test right now. Try again in a minute.",
                now.minus(EXPIRE_AFTER), now.minus(EXPIRE_AFTER), now);
        List<Check> out = new java.util.ArrayList<>();
        for (StreamDestination d : destinations) {
            String key = d.getStreamKey();
            String name = d.getName() == null ? "Destination" : d.getName();
            String platform = PlatformErrors.platform(key);
            DestinationTest latest = tests.findTop5ByDestinationIdOrderByIdDesc(d.getId()).stream()
                    .filter(t -> key != null && key.equals(t.getUrl())).findFirst().orElse(null);
            if (latest != null && (latest.getStatus() == DestinationTest.Status.PENDING || latest.getStatus() == DestinationTest.Status.RUNNING)) {
                out.add(new Check(d.getId(), name, platform, TESTING, "Sending a " + TEST_SECONDS + "-second test…", latest.getId()));
                continue;
            }
            ZonedDateTime finished = latest == null ? null : latest.getFinishedAt();
            if (latest != null && latest.getStatus() == DestinationTest.Status.OK && finished != null && finished.isAfter(now.minus(FRESH_FOR))) {
                out.add(new Check(d.getId(), name, platform, PASSED, "Worked in a test in the last 24 hours.", latest.getId()));
                continue;
            }
            if (latest != null && latest.getStatus() == DestinationTest.Status.FAILED
                    && (!start || finished != null && finished.isAfter(now.minus(FAILED_REUSE)))) {
                out.add(new Check(d.getId(), name, platform, FAILED, latest.getMessage(), latest.getId()));
                continue;
            }
            if (!start) {
                out.add(new Check(d.getId(), name, platform, UNTESTED, "Not checked yet.", null));
                continue;
            }
            try {
                NetworkSafety.requireSafeStreamDestination(key);
            } catch (RuntimeException e) {
                out.add(new Check(d.getId(), name, platform, FAILED, "That address can't be used for streaming.", null));
                continue;
            }
            if (isLive(user.getId(), key)) {
                out.add(new Check(d.getId(), name, platform, FAILED,
                        "This destination is live on another stream right now, and a platform takes one stream per key.", null));
                continue;
            }
            if (tests.countByUserIdAndCreatedAtAfter(user.getId(), now.minusHours(1)) >= GO_LIVE_TESTS_PER_HOUR) {
                out.add(new Check(d.getId(), name, platform, FAILED, "Too many checks in the last hour. Try again later.", null));
                continue;
            }
            DestinationTest t = create(user, d);
            out.add(new Check(d.getId(), name, platform, TESTING, "Sending a " + TEST_SECONDS + "-second test…", t.getId()));
        }
        return out;
    }

    /**
     * Worker side, before a run connects: makes sure no test of the user is connected to one of the run's
     * keys. Pending ones are cancelled; running ones notice the live run and stop within a second or two
     * (see {@link #run}), and this waits for that, up to {@code max}. Returns false if one is still running.
     */
    public boolean clearTestsFor(Long userId, java.util.Collection<String> keys, Duration max) {
        if (userId == null || keys.isEmpty()) {
            return true;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>();
        keys.forEach(k -> wanted.add(k == null ? null : k.trim()));
        long deadline = System.nanoTime() + max.toNanos();
        while (true) {
            boolean running = false;
            for (DestinationTest t : tests.findAllByUserIdAndStatusIn(userId, List.of(DestinationTest.Status.PENDING, DestinationTest.Status.RUNNING))) {
                if (t.getUrl() == null || !wanted.contains(t.getUrl().trim())) {
                    continue;
                }
                if (t.getStatus() == DestinationTest.Status.PENDING
                        && tests.cancelPending(t.getId(), LIVE_NOW, ZonedDateTime.now(ZoneId.of("UTC"))) == 1) {
                    continue;
                }
                running = true;
            }
            if (!running) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    public DestinationTest result(User user, Long testId) {
        tests.expire("No stream server could run the test right now. Try again in a minute.",
                ZonedDateTime.now(ZoneId.of("UTC")).minus(EXPIRE_AFTER), ZonedDateTime.now(ZoneId.of("UTC")).minus(EXPIRE_AFTER),
                ZonedDateTime.now(ZoneId.of("UTC")));
        return tests.findByIdAndUserId(testId, user.getId())
                .orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Test not found."));
    }

    // ------------------------------------------------------------------ worker side

    /** Runs one claimed test to completion and records the result. Blocking: call on its own thread. */
    public void run(Long testId) {
        DestinationTest t = tests.findById(testId).orElse(null);
        if (t == null) {
            return;
        }
        Outcome o;
        try {
            NetworkSafety.requireSafeStreamDestination(t.getUrl());
            Long userId = t.getUserId();
            String url = t.getUrl();
            // Checked after the claim: a run that went live before it is seen here; one that goes live
            // during the test is seen by the check every second, and its worker waits for this test to end.
            o = isLive(userId, url) ? new Outcome(false, LIVE_NOW) : execute(url, () -> isLive(userId, url));
        } catch (RuntimeException e) {
            o = new Outcome(false, "That address can't be used for streaming.");
        }
        tests.finish(testId, o.ok() ? DestinationTest.Status.OK : DestinationTest.Status.FAILED, o.message(),
                ZonedDateTime.now(ZoneId.of("UTC")));
    }

    record Outcome(boolean ok, String message) {
    }

    Outcome execute(String destination) {
        return execute(destination, () -> false);
    }

    /** {@code abort}: checked every second; true stops the test at once (its key went live). */
    Outcome execute(String destination, java.util.function.BooleanSupplier abort) {
        Process p;
        try {
            p = new ProcessBuilder(command.apply(destination)).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new Outcome(false, "The test couldn't run on our side. Please try again.");
        }
        // Read output on its own thread: a server that never answers must not block us.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().daemon(true).start(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        if (out.size() < 8000) {
                            out.write(buf, 0, n);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        try {
            p.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!p.waitFor(1, TimeUnit.SECONDS)) {
                boolean stop;
                try {
                    stop = abort.getAsBoolean();
                } catch (RuntimeException e) {
                    stop = false;
                }
                if (stop) {
                    p.destroyForcibly();
                    return new Outcome(false, LIVE_NOW);
                }
                if (System.nanoTime() > deadline) {
                    p.destroyForcibly();
                    return new Outcome(false, "The platform didn't respond. Check the server address.");
                }
            }
            reader.join(2000);
            String text;
            synchronized (out) {
                text = out.toString(StandardCharsets.UTF_8);
            }
            if (p.exitValue() == 0) {
                return new Outcome(true, "Connected and sent a " + TEST_SECONDS
                        + "-second test. In YouTube Studio's live dashboard the stream should show as receiving.");
            }
            log.info("Destination test failed: {}", Redact.streamKeys(text));
            return new Outcome(false, PlatformErrors.explain(text, destination));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new Outcome(false, "The test was interrupted.");
        } catch (IOException e) {
            p.destroyForcibly();
            return new Outcome(false, "The test couldn't run on our side. Please try again.");
        }
    }

    static List<String> ffmpegTest(String destination) {
        String url = FFmpegCommandBuilder.destinationUrl(destination);
        return List.of(FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-loglevel", "error",
                "-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100",
                "-t", String.valueOf(TEST_SECONDS),
                "-c:v", "libx264", "-preset", "veryfast", "-b:v", "3000k", "-maxrate", "3000k", "-bufsize", "6000k",
                "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
                // An output option (placed before an input it broke every test: "Option rw_timeout not found"):
                // give up on a server that stops answering (10 s).
                "-rw_timeout", "10000000",
                "-f", FFmpegCommandBuilder.formatFor(url), url);
    }

    /** FFmpeg's error, in words a creator can act on. */
    static String explain(String ffmpegOutput) {
        return PlatformErrors.explain(ffmpegOutput);
    }
}
