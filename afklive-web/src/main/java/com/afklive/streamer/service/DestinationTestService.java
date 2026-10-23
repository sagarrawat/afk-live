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
        for (StreamJob j : jobs.findAllByUserEmailAndIsLiveTrue(user.getEmail())) {
            try {
                if (j.getSpec() != null && StreamSpec.fromJson(j.getSpec()).destinations().stream().anyMatch(x -> key.equals(x.url()))) {
                    throw new com.afklive.streamer.exception.ConflictException("This destination is live right now. Test it when the stream has stopped.");
                }
            } catch (com.afklive.streamer.exception.ConflictException e) {
                throw e;
            } catch (Exception ignored) {
                // a legacy run without a readable spec
            }
        }
        if (tests.existsByUserIdAndStatusIn(user.getId(), List.of(DestinationTest.Status.PENDING, DestinationTest.Status.RUNNING))) {
            throw new com.afklive.streamer.exception.ConflictException("A test is already running. Wait for it to finish.");
        }
        if (tests.countByUserIdAndCreatedAtAfter(user.getId(), ZonedDateTime.now(ZoneId.of("UTC")).minusHours(1)) >= PER_HOUR) {
            throw new IllegalStateException("You've run " + PER_HOUR + " tests in the last hour. Try again later.");
        }
        DestinationTest t = new DestinationTest();
        t.setUserId(user.getId());
        t.setDestinationId(d.getId());
        t.setUrl(key);
        t.setCreatedAt(ZonedDateTime.now(ZoneId.of("UTC")));
        return tests.save(t);
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
            o = execute(t.getUrl());
        } catch (RuntimeException e) {
            o = new Outcome(false, "That address can't be used for streaming.");
        }
        tests.finish(testId, o.ok() ? DestinationTest.Status.OK : DestinationTest.Status.FAILED, o.message(),
                ZonedDateTime.now(ZoneId.of("UTC")));
    }

    record Outcome(boolean ok, String message) {
    }

    Outcome execute(String destination) {
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
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Outcome(false, "The platform didn't respond. Check the server address.");
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
            return new Outcome(false, explain(text));
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
                "-rw_timeout", "10000000", // give up on a server that stops answering (10 s)
                "-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100",
                "-t", String.valueOf(TEST_SECONDS),
                "-c:v", "libx264", "-preset", "veryfast", "-b:v", "3000k", "-maxrate", "3000k", "-bufsize", "6000k",
                "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
                "-f", FFmpegCommandBuilder.formatFor(url), url);
    }

    /** FFmpeg's error, in words a creator can act on. */
    static String explain(String ffmpegOutput) {
        String o = ffmpegOutput == null ? "" : ffmpegOutput.toLowerCase();
        if (o.contains("connection refused") || o.contains("cannot open connection") || o.contains("failed to resolve")
                || o.contains("name or service not known") || o.contains("timed out") || o.contains("network is unreachable")) {
            return "Couldn't reach the server. Check the server address.";
        }
        if (o.contains("broken pipe") || o.contains("end of file") || o.contains("input/output error")
                || o.contains("connection reset") || o.contains("unauthorized") || o.contains("403")) {
            return "The platform refused the stream key. Copy the key again from the platform and save it.";
        }
        return "The test didn't get through. Check the server address and stream key.";
    }
}
