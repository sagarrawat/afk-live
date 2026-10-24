package com.afklive.streamer.service;

import com.afklive.streamer.model.DestinationTest;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.DestinationTestRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.streaming.StreamRunner;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.util.NetworkSafety;
import com.afklive.streamer.util.Platforms;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * MS-6: "Test" for a destination. The web app records a request; a stream worker (which has the
 * egress firewall, so a test can't be pointed inside our network) sends a 10-second test pattern;
 * the web app reads the result. If the platform accepts the connection and the key for the whole
 * time, the destination works, and YouTube Studio shows the test as "receiving".
 * <p>
 * GL-1: Go live runs the same test first for each destination that hasn't passed one in 24 hours with
 * its current key. A test never runs against a key a live run is using: two connections on one key
 * make the platform drop one of them, which could cut a stream that is on air.
 */
@Service
@Slf4j
public class DestinationTestService {

    static final int TEST_SECONDS = 10;
    /** How many tests a user may request per hour. */
    static final int PER_HOUR = 6;
    /** A test nobody picked up, or whose worker died, fails after this long. */
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(2);
    /** GL-1: a passed test counts for this long, as long as the key hasn't changed since. */
    static final Duration FRESH_FOR = Duration.ofHours(24);
    /** GL-1: a test that failed this recently is shown again instead of re-run (pressing Go live twice). */
    static final Duration FAILED_REUSE = Duration.ofSeconds(60);
    /** GL-1: tests Go live may start per user per hour (each destination counts). */
    static final int GO_LIVE_TESTS_PER_HOUR = 30;
    static final String WENT_LIVE = "Not tested: a live stream started on this destination.";

    /** GL-1 states of one destination before going live. */
    public static final String PASSED = "PASSED";
    public static final String TESTING = "TESTING";
    public static final String FAILED = "FAILED";
    /** Not tested because a live run of this same stream uses the key (e.g. it is still stopping). */
    public static final String SKIPPED = "SKIPPED";

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
        com.afklive.streamer.util.StreamAddresses.requireWellFormed(key);
        NetworkSafety.requireSafeStreamDestination(key);
        if (liveRunUsing(user.getId(), key).isPresent()) {
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

    public DestinationTest result(User user, Long testId) {
        expireStale();
        return tests.findByIdAndUserId(testId, user.getId())
                .orElseThrow(() -> new com.afklive.streamer.exception.NotFoundException("Test not found."));
    }

    private void expireStale() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        tests.expire("No stream server could run the test right now. Try again in a minute.",
                now.minus(EXPIRE_AFTER), now.minus(EXPIRE_AFTER), now);
    }

    /** The user's live run that sends to this key, if any. */
    public Optional<StreamJob> liveRunUsing(Long userId, String key) {
        if (userId == null || key == null) {
            return Optional.empty();
        }
        String k = key.trim();
        for (StreamJob j : jobs.findAllLiveByUserId(userId)) {
            try {
                if (j.getSpec() != null && StreamSpec.fromJson(j.getSpec()).destinations().stream()
                        .anyMatch(x -> !x.off() && x.url() != null && k.equals(x.url().trim()))) {
                    return Optional.of(j);
                }
            } catch (Exception ignored) {
                // a legacy run without a readable spec
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ GL-1: before going live

    /**
     * One destination's state before going live: {@link #PASSED}, {@link #TESTING}, {@link #FAILED} or
     * {@link #SKIPPED}. {@code platform}: "YouTube", ... or null; {@code message} in plain words.
     */
    public record Check(Long destinationId, String name, String platform, String state, String message) {
    }

    /**
     * GL-1: for each destination, whether it passed a test in the last 24 hours with its current key.
     * With {@code start}, a destination without such a pass gets a test (one already running is reused),
     * so a stream whose destinations are all fresh goes live without waiting for anything. A key that a
     * live run uses is never tested: skipped when that run is this stream's (it is stopping), failed with
     * the reason when it is another stream's (a platform takes one stream per key).
     */
    public List<Check> goLiveChecks(User user, Long streamId, List<StreamDestination> destinations, boolean start) {
        expireStale();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        List<Check> out = new ArrayList<>();
        for (StreamDestination d : destinations) {
            String key = d.getStreamKey();
            String name = d.getName() == null ? "Destination" : d.getName();
            String platform = Platforms.label(key);
            String where = platform != null ? platform : name;
            Optional<StreamJob> live = liveRunUsing(user.getId(), key);
            if (live.isPresent()) {
                boolean same = streamId != null && streamId.equals(live.get().getStreamId());
                out.add(same
                        ? new Check(d.getId(), name, platform, SKIPPED, "Not tested: this stream is still sending to " + where + ".")
                        : new Check(d.getId(), name, platform, FAILED, "Another of your streams is live on this " + where
                        + " key right now, and " + where + " takes one stream per key. Stop that stream or use another key."));
                continue;
            }
            DestinationTest latest = key == null ? null : tests.findTop10ByDestinationIdOrderByIdDesc(d.getId()).stream()
                    .filter(t -> key.equals(t.getUrl())).findFirst().orElse(null);
            DestinationTest.Status status = latest == null ? null : latest.getStatus();
            ZonedDateTime finished = latest == null ? null : latest.getFinishedAt();
            if (status == DestinationTest.Status.PENDING || status == DestinationTest.Status.RUNNING) {
                out.add(new Check(d.getId(), name, platform, TESTING, testing(where)));
                continue;
            }
            if (status == DestinationTest.Status.OK && finished != null && finished.isAfter(now.minus(FRESH_FOR))) {
                out.add(new Check(d.getId(), name, platform, PASSED, "Worked in a test in the last 24 hours."));
                continue;
            }
            if (status == DestinationTest.Status.FAILED && (!start || finished != null && finished.isAfter(now.minus(FAILED_REUSE)))) {
                out.add(new Check(d.getId(), name, platform, FAILED, latest.getMessage()));
                continue;
            }
            if (!start) {
                out.add(new Check(d.getId(), name, platform, TESTING, "Not tested yet."));
                continue;
            }
            try {
                com.afklive.streamer.util.StreamAddresses.requireWellFormed(key);
                NetworkSafety.requireSafeStreamDestination(key);
            } catch (RuntimeException e) {
                out.add(new Check(d.getId(), name, platform, FAILED, e.getMessage() == null
                        ? "That address can't be used for streaming." : e.getMessage()));
                continue;
            }
            if (tests.countByUserIdAndCreatedAtAfter(user.getId(), now.minusHours(1)) >= GO_LIVE_TESTS_PER_HOUR) {
                out.add(new Check(d.getId(), name, platform, FAILED, "Too many tests in the last hour, so we couldn't check "
                        + where + ". Try again later, or go live anyway."));
                continue;
            }
            create(user, d);
            out.add(new Check(d.getId(), name, platform, TESTING, testing(where)));
        }
        return out;
    }

    private static String testing(String where) {
        return "Sending a " + TEST_SECONDS + "-second test to " + where + "…";
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
            // GL-1: never on a key that is on air. A run that went live before this test started is seen here;
            // one that goes live during it is seen by the check every second, and the test stops at once.
            BooleanSupplier wentLive = () -> liveRunUsing(userId, url).isPresent();
            o = wentLive.getAsBoolean() ? new Outcome(false, WENT_LIVE) : execute(url, wentLive);
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

    /** {@code abort}: looked at every second; true stops the test at once (its key went live). */
    Outcome execute(String destination, BooleanSupplier abort) {
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
                    stop = false; // can't tell (database hiccup): carry on
                }
                if (stop) {
                    p.destroyForcibly();
                    return new Outcome(false, WENT_LIVE);
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
            return new Outcome(false, explain(text, destination));
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
    public static String explain(String ffmpegOutput) {
        return explain(ffmpegOutput, null);
    }

    /**
     * FFmpeg's error, in words a creator can act on. Shared by the destination test and the live status
     * (RE-8), so a key that fails the test and a key that fails live read the same. {@code destination}:
     * the address it went to, when known: it names the platform, and an address that can never work (a
     * server address pasted as the key) is explained as such whatever FFmpeg said.
     */
    public static String explain(String ffmpegOutput, String destination) {
        if (destination != null) {
            try {
                com.afklive.streamer.util.StreamAddresses.requireWellFormed(destination);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        String o = ffmpegOutput == null ? "" : ffmpegOutput.toLowerCase(Locale.ROOT);
        String platform = destination == null ? null : Platforms.label(destination);
        String it = platform == null ? "the platform" : platform;
        String It = platform == null ? "The platform" : platform;
        if (o.contains(StreamRunner.STALLED.toLowerCase(Locale.ROOT))) {
            return It + " stopped taking the stream. We keep reconnecting.";
        }
        if (o.contains("couldn't keep up")) {
            return "The connection to " + it + " was too slow to keep up, so we reconnected.";
        }
        if (o.contains("connection refused") || o.contains("cannot open connection") || o.contains("failed to resolve")
                || o.contains("name or service not known") || o.contains("timed out") || o.contains("network is unreachable")) {
            return "Couldn't reach the server. Check the server address.";
        }
        if (o.contains("broken pipe") || o.contains("end of file") || o.contains("input/output error")
                || o.contains("connection reset") || o.contains("unauthorized") || o.contains("403")) {
            return It + " refused the stream key. Copy the key again from " + it + " and save it.";
        }
        if (o.contains("no such file or directory") || o.contains("permission denied")) {
            return "The address can't be opened. Check the server address and stream key.";
        }
        return "The stream didn't get through. Check the server address and stream key.";
    }
}
