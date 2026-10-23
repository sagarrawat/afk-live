package com.afklive.streamer.service;

import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.util.NetworkSafety;
import com.afklive.streamer.util.Redact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * MS-6: "Test" for a destination. Sends a 10-second test pattern to the platform; if the platform
 * accepts the connection and the stream key for the whole time, the destination works. YouTube then
 * shows the test in Studio's live dashboard ("receiving"), which is the confirmation without the API.
 */
@Service
@Slf4j
public class DestinationTestService {

    static final int TEST_SECONDS = 10;

    public record Result(boolean ok, String message) {
    }

    private final StreamJobRepository jobs;
    private final Function<String, List<String>> command;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public DestinationTestService(StreamJobRepository jobs) {
        this(jobs, DestinationTestService::ffmpegTest);
    }

    DestinationTestService(StreamJobRepository jobs, Function<String, List<String>> command) {
        this.jobs = jobs;
        this.command = command;
    }

    static List<String> ffmpegTest(String destination) {
        String url = FFmpegCommandBuilder.destinationUrl(destination);
        return List.of(FFmpegCommandBuilder.ffmpegBinary(), "-hide_banner", "-loglevel", "error", "-re",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=f=440:r=44100",
                "-t", String.valueOf(TEST_SECONDS),
                "-c:v", "libx264", "-preset", "veryfast", "-b:v", "3000k", "-maxrate", "3000k", "-bufsize", "6000k",
                "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
                "-f", FFmpegCommandBuilder.formatFor(url), url);
    }

    public Result test(String email, StreamDestination d) {
        String key = d.getStreamKey();
        NetworkSafety.requireSafeStreamDestination(key);
        for (StreamJob j : jobs.findAllByUserEmailAndIsLiveTrue(email)) {
            try {
                if (j.getSpec() != null && StreamSpec.fromJson(j.getSpec()).destinations().stream().anyMatch(x -> key.equals(x.url()))) {
                    return new Result(false, "This destination is live right now. Test it when the stream has stopped.");
                }
            } catch (Exception ignored) {
                // legacy run without a readable spec
            }
        }
        if (!running.add(email)) {
            return new Result(false, "A test is already running. Wait for it to finish.");
        }
        try {
            Process p = new ProcessBuilder(command.apply(key)).redirectErrorStream(true).start();
            p.getOutputStream().close();
            boolean done = p.waitFor(TEST_SECONDS + 20L, TimeUnit.SECONDS);
            String out = new String(p.getInputStream().readNBytes(4000), StandardCharsets.UTF_8);
            if (!done) {
                p.destroyForcibly();
                return new Result(false, "The platform didn't respond. Check the server address.");
            }
            if (p.exitValue() == 0) {
                return new Result(true, "Connected and sent a " + TEST_SECONDS
                        + "-second test. In YouTube Studio's live dashboard the stream should show as receiving.");
            }
            log.info("Destination test failed: {}", Redact.streamKeys(out));
            return new Result(false, explain(out));
        } catch (IOException e) {
            return new Result(false, "The test couldn't run on our side. Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "The test was interrupted.");
        } finally {
            running.remove(email);
        }
    }

    /** FFmpeg's error, in words a creator can act on. */
    static String explain(String ffmpegOutput) {
        String o = ffmpegOutput == null ? "" : ffmpegOutput.toLowerCase();
        if (o.contains("connection refused") || o.contains("cannot open connection") || o.contains("failed to resolve")
                || o.contains("name or service not known") || o.contains("timed out")) {
            return "Couldn't reach the server. Check the server address.";
        }
        if (o.contains("broken pipe") || o.contains("end of file") || o.contains("input/output error")
                || o.contains("connection reset") || o.contains("unauthorized") || o.contains("403")) {
            return "The platform refused the stream key. Copy the key again from the platform and save it.";
        }
        return "The test didn't get through. Check the server address and stream key.";
    }
}
