package com.afklive.streamer.streaming.soak;

import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.MediaProbe;
import com.afklive.streamer.service.PlayoutCopies;
import com.afklive.streamer.streaming.CopyRuns;
import com.afklive.streamer.streaming.IngestReader;
import com.afklive.streamer.streaming.MediaCache;
import com.afklive.streamer.streaming.StreamPreparer;
import com.afklive.streamer.streaming.StreamRunner;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StudioBuilder;
import com.afklive.streamer.streaming.StudioRuns;
import com.afklive.streamer.streaming.StudioSpec;
import com.afklive.streamer.streaming.TsStitcherTest;
import com.afklive.streamer.streaming.studio.Canvas;
import com.afklive.streamer.streaming.studio.Studio;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OPS-6 soak harness: runs K streams for a long time through the worker's own code, with only the
 * database and object storage replaced by in-memory stand-ins, and measures whether they stay healthy.
 * <p>
 * The real parts: {@link StudioRuns} / {@link StudioBuilder} / {@link Studio} (frame clock, decoders,
 * overlays, one long-lived x264 encoder), {@link CopyRuns} / {@code CopyPlayer} (FO-2 stream copy of profile
 * copies), {@link StreamRunner} with the production relay command and production timings, {@code TsStitcher}
 * and {@link StreamRunner#handOff} for studio↔copy switches, {@link MediaCache} and {@link StreamPreparer}.
 * Switches follow {@code StreamWorker.switchToStudio} / {@code maybeMoveToCopy} step by step (save the
 * position, build the other producer there, hand off, close the old one).
 * <p>
 * Each destination is a {@link SoakSink} on 127.0.0.1: no external network, nothing large on disk.
 */
public final class SoakHarness {

    static final String WORKER = "soak-worker";
    /** As StreamWorker.HAND_OFF_TIMEOUT. */
    static final Duration HAND_OFF_TIMEOUT = Duration.ofSeconds(30);

    /** What to run. See {@link #fromSystemProperties()} for the names and defaults. */
    public record Config(Duration duration, boolean copyMode, int streams, int destinations, int height, int fps,
                         Duration switchEvery, Duration sampleEvery, Duration captureEvery, Duration memoryWarmup,
                         Duration gcEvery, Path outDir) {

        /**
         * soak.minutes (360), soak.copyMode (false), soak.streams (2), soak.destinations (2), soak.height (720),
         * soak.fps (30), soak.switchMinutes (10, or a sixth of a run shorter than an hour), soak.out.
         */
        public static Config fromSystemProperties() {
            double minutes = Double.parseDouble(System.getProperty("soak.minutes", "360"));
            Duration d = Duration.ofMillis(Math.round(minutes * 60_000));
            boolean copy = Boolean.parseBoolean(System.getProperty("soak.copyMode", "false"));
            int streams = Integer.parseInt(System.getProperty("soak.streams", "2"));
            int dests = Integer.parseInt(System.getProperty("soak.destinations", "2"));
            int height = Integer.parseInt(System.getProperty("soak.height", "720"));
            int fps = Integer.parseInt(System.getProperty("soak.fps", "30"));
            String sw = System.getProperty("soak.switchMinutes");
            Duration switchEvery = sw != null && !sw.isBlank() ? Duration.ofMillis(Math.round(Double.parseDouble(sw) * 60_000))
                    : minutes >= 60 ? Duration.ofMinutes(10) : d.dividedBy(6);
            Duration captureEvery = minutes >= 60 ? Duration.ofMinutes(30) : d.dividedBy(3);
            Duration warm = Duration.ofMillis(Math.min(Duration.ofMinutes(15).toMillis(), Math.max(Duration.ofMinutes(2).toMillis(), d.toMillis() / 10)));
            Duration gcEvery = minutes >= 60 ? Duration.ofMinutes(5) : Duration.ofMinutes(1);
            String out = System.getProperty("soak.out", "");
            Path outDir = out.isBlank() ? Path.of("target", "soak", Instant.now().toString().replace(":", "-")) : Path.of(out);
            return new Config(d, copy, streams, dests, height, fps, switchEvery, Duration.ofSeconds(10), captureEvery, warm,
                    gcEvery, outDir.toAbsolutePath());
        }
    }

    /** The playlist every stream plays: lengths that don't line up with the 2 s keyframe interval on purpose. */
    record Clip(String id, String look, int w, int h, int seconds, int toneHz) {
    }

    static final List<Clip> CLIPS = List.of(
            new Clip("1", "testsrc2", 1920, 1080, 47, 440),
            new Clip("2", "testsrc", 1280, 720, 31, 660),
            new Clip("3", "smptebars", 720, 1280, 73, 880));

    private final Config cfg;
    private final Path work;
    private final Path samplesDir;
    private final Map<String, Path> library = new ConcurrentHashMap<>();
    private final Map<String, String> profileKeys = new ConcurrentHashMap<>();
    private final Map<Long, String> savedPositions = new ConcurrentHashMap<>();
    private final List<Run> runs = new ArrayList<>();
    private final List<Map<String, Object>> sampleResults = new CopyOnWriteArrayList<>();
    private final ExecutorService checker = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon(true).name("soak-check").unstarted(r));
    private final AtomicInteger captureSeq = new AtomicInteger();
    private final StringBuilder log = new StringBuilder();
    private MediaCache cache;
    private StudioRuns studioRuns;
    private CopyRuns copyRuns;
    private ProcessSampler sampler;

    public SoakHarness(Config cfg) {
        this.cfg = cfg;
        this.work = cfg.outDir().resolve("work");
        this.samplesDir = cfg.outDir().resolve("samples");
    }

    // ---------------------------------------------------------------- one stream

    /** One running stream: its producer (studio or copy), runner, sinks and what its listener heard. */
    final class Run implements StreamRunner.Listener {
        final String name;
        final long jobId;
        final long streamId;
        final StreamSpec spec;
        final List<SoakSink> sinks = new ArrayList<>();
        volatile StreamRunner runner;
        volatile StudioRuns.Handle studio;
        volatile CopyRuns.Handle copy;
        volatile String mode;
        volatile boolean stopping;
        volatile String finishedReason;
        volatile boolean finishedFailed;
        final AtomicInteger encoderStarts = new AtomicInteger();
        final AtomicInteger encoderFailures = new AtomicInteger();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final Map<Integer, AtomicInteger> reconnecting = new ConcurrentHashMap<>();
        final Map<Integer, AtomicInteger> notReaching = new ConcurrentHashMap<>();
        final Map<String, Speed> speed = new ConcurrentHashMap<>();
        final AtomicLong studioFramesOut = new AtomicLong();
        final AtomicLong studioFramesDropped = new AtomicLong();
        final List<Map<String, Object>> switches = new CopyOnWriteArrayList<>();
        final List<String> lastLogLines = new CopyOnWriteArrayList<>();

        Run(int k, StreamSpec spec) {
            this.name = "s" + k;
            this.jobId = 9000 + k;
            this.streamId = 7000 + k;
            this.spec = spec;
        }

        @Override
        public void onEncoderStarted(int restarts) {
            encoderStarts.incrementAndGet();
        }

        @Override
        public void onProgress(StreamRunner.Progress p) {
            if (p.speed() > 0) {
                speed.computeIfAbsent(mode == null ? "starting" : mode, m -> new Speed()).add(p.speed());
            }
        }

        @Override
        public void onDestination(int position, String status, int restarts, String error) {
            if ("RECONNECTING".equals(status) && !stopping) {
                reconnecting.computeIfAbsent(position, p -> new AtomicInteger()).incrementAndGet();
                note("dest " + (position + 1) + " reconnecting: " + error);
            }
        }

        @Override
        public void onReach(int position, String reach, String reason) {
            if (StreamRunner.NOT_REACHING.equals(reach) && !stopping) {
                notReaching.computeIfAbsent(position, p -> new AtomicInteger()).incrementAndGet();
            }
        }

        @Override
        public void onEncoderFailure(int restarts, String error) {
            if (!stopping) {
                encoderFailures.incrementAndGet();
                note("producer failed (" + restarts + "): " + error);
            }
        }

        @Override
        public void onLogLine(String source, String line) {
            if (lastLogLines.size() >= 30) {
                lastLogLines.removeFirst();
            }
            lastLogLines.add(source + ": " + line);
        }

        @Override
        public void onFinished(String reason, boolean failed) {
            finishedReason = reason;
            finishedFailed = failed;
            if (!stopping) {
                note("ended unexpectedly: " + reason);
            }
        }

        void note(String s) {
            if (errors.size() < 50) {
                errors.add(String.format("%.0fs %s", (System.nanoTime() - started) / 1e9, s));
            }
            say(name + ": " + s);
        }

        void addStudioFrames(StudioRuns.Handle h) {
            Studio.Status st = h.studio().status();
            studioFramesOut.addAndGet(st.framesOut());
            studioFramesDropped.addAndGet(st.framesDropped());
        }
    }

    /** Encoder speed readings (FFmpeg's -progress "speed"): 1.0 is real time. */
    static final class Speed {
        long count;
        long below;
        double sum;
        double min = Double.MAX_VALUE;

        synchronized void add(double s) {
            count++;
            sum += s;
            min = Math.min(min, s);
            if (s < Thresholds.SLOW_SPEED) {
                below++;
            }
        }

        synchronized Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("readings", count);
            m.put("avg", count == 0 ? null : round(sum / count, 3));
            m.put("min", count == 0 ? null : round(min, 3));
            m.put("belowShare", count == 0 ? 0 : round((double) below / count, 4));
            return m;
        }
    }

    private long started;

    // ---------------------------------------------------------------- run

    /** Runs the whole soak and writes summary.json and report.md; returns the report. */
    public SoakReport run() throws Exception {
        Files.createDirectories(cfg.outDir());
        say("OPS-6 soak: " + cfg);
        prepareMedia();
        wire();
        sampler = new ProcessSampler(this::streamOfCommand);
        List<ProcessSampler.Sample> series = new ArrayList<>();
        sampler.sample(Map.of(), true);
        int fds0 = ProcessSampler.fds();
        List<String> names0 = threadNames();
        int threads0 = names0.size();

        started = System.nanoTime();
        for (int k = 1; k <= cfg.streams(); k++) {
            Run r = new Run(k, specFor(k));
            runs.add(r);
            start(r);
            Thread.sleep(2000); // stagger, as real streams start at different moments
        }

        long end = started + cfg.duration().toNanos();
        long nextSample = System.nanoTime();
        long nextReport = System.nanoTime();
        long nextGc = System.nanoTime() + cfg.gcEvery().toNanos();
        long nextSwitch = started + cfg.switchEvery().toNanos();
        long nextCapture = started + Duration.ofSeconds(60).toNanos();
        int switchNo = 0;
        while (System.nanoTime() < end) {
            long now = System.nanoTime();
            if (now >= nextReport) {
                heartbeat();
                nextReport = now + Duration.ofSeconds(10).toNanos();
            }
            if (now >= nextSample) {
                boolean gc = now >= nextGc;
                if (gc) {
                    nextGc = now + cfg.gcEvery().toNanos();
                }
                ProcessSampler.Sample s = sampler.sample(modes(), gc);
                series.add(s);
                nextSample = now + cfg.sampleEvery().toNanos();
                if (series.size() % 30 == 1) {
                    say(String.format("t=%.0fs rss=%.0fMB heap=%.0fMB fds=%d threads=%d ffmpeg=%d (%.0fMB) cpu jvm=%.2f ffmpeg=%.2f modes=%s",
                            s.atSeconds(), s.rssMb(), s.heapUsedMb(), s.fds(), s.threads(), s.ffmpegProcesses(), s.ffmpegRssMb(),
                            s.jvmCores(), s.ffmpegCores(), modes()));
                }
            }
            if (cfg.copyMode() && now >= nextSwitch && end - now > Duration.ofSeconds(45).toNanos()) {
                switchNo++;
                for (Run r : runs) {
                    switchOnce(r, switchNo);
                }
                nextSwitch = System.nanoTime() + cfg.switchEvery().toNanos();
            }
            if (now >= nextCapture && end - now > Duration.ofSeconds(40).toNanos()) {
                for (Run r : runs) {
                    captureAll(r, "periodic, " + r.mode);
                }
                nextCapture = now + cfg.captureEvery().toNanos();
            }
            for (Run r : runs) {
                if (r.finishedReason != null && !r.stopping) {
                    say(r.name + " ended early: " + r.finishedReason);
                }
            }
            if (runs.stream().allMatch(r -> r.finishedReason != null)) {
                break;
            }
            Thread.sleep(250);
        }
        double ranSeconds = (System.nanoTime() - started) / 1e9;
        ProcessSampler.Sample last = sampler.sample(modes(), true);
        series.add(last);

        // Stop everything the way the worker does, then check that nothing was left behind.
        for (Run r : runs) {
            r.stopping = true;
            r.sinks.forEach(s -> s.connections().forEach(FlvTimeline::finishCapture));
            if (r.runner != null) {
                r.runner.stop("Soak over");
            }
        }
        for (Run r : runs) {
            if (r.runner != null) {
                r.runner.awaitFinished(Duration.ofSeconds(30));
            }
            closeProducers(r);
        }
        checker.shutdown();
        checker.awaitTermination(5, TimeUnit.MINUTES);
        for (Run r : runs) {
            r.sinks.forEach(SoakSink::close); // the stand-in platforms are the harness's, not the worker's
        }
        long settle = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < settle && ProcessSampler.ffmpegProcesses() > 0) {
            Thread.sleep(250);
        }
        Thread.sleep(3000);
        System.gc();
        Map<String, Object> afterStop = new LinkedHashMap<>();
        afterStop.put("fds", ProcessSampler.fds());
        // JVM pools that start on first use and idle out later aren't leaks; anything else that stayed is.
        List<String> stayed = newThreads(names0, threadNames());
        List<String> unexpected = stayed.stream().filter(n -> JVM_POOLS.stream().noneMatch(n::contains)).toList();
        afterStop.put("threads", threads0 + unexpected.size());
        afterStop.put("threadsRaw", ProcessSampler.threads());
        afterStop.put("newThreads", stayed);
        afterStop.put("ffmpegProcesses", ProcessSampler.ffmpegProcesses());
        afterStop.put("workDirLeftovers", leftovers());
        afterStop.put("cachePins", cachePins());
        List<Map<String, Object>> streams = new ArrayList<>();
        for (Run r : runs) {
            streams.add(streamJson(r));
        }

        SoakReport report = SoakReport.evaluate(cfg, ranSeconds, series, fds0, threads0, afterStop, streams,
                List.copyOf(sampleResults), sampler.ffmpegCoresByStreamAndMode(), sampler.jvmCoresByMode(),
                sampler.unattributedCpuSeconds(), machine());
        report.write(cfg.outDir());
        Files.writeString(cfg.outDir().resolve("soak.log"), log.toString());
        if (report.pass()) {
            deleteQuietly(work);
        }
        say("Soak " + (report.pass() ? "PASSED" : "FAILED") + ": " + cfg.outDir().resolve("report.md"));
        return report;
    }

    // ---------------------------------------------------------------- setup

    private void prepareMedia() throws Exception {
        Path media = cfg.outDir().resolve("media");
        Files.createDirectories(media);
        Canvas canvas = Canvas.of(cfg.height(), false, cfg.fps());
        for (Clip c : CLIPS) {
            Path src = media.resolve("clip-" + c.id() + ".mp4");
            if (!Files.exists(src)) {
                run(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", c.look() + "=size=" + c.w() + "x" + c.h() + ":rate=30",
                        "-f", "lavfi", "-i", "sine=f=" + c.toneHz() + ":r=48000", "-t", String.valueOf(c.seconds()),
                        "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ac", "2",
                        "-shortest", src.toString()));
            }
            String key = "soak/clip-" + c.id() + ".mp4";
            library.put(key, src);
            if (cfg.copyMode()) {
                // The FO-2 profile copy, made with the same command the web side uses.
                Path prof = media.resolve("profile-" + c.id() + "-" + canvas.width() + "x" + canvas.height() + "-" + cfg.fps() + ".ts");
                if (!Files.exists(prof)) {
                    say("Preparing profile copy " + prof.getFileName());
                    run(PlayoutCopies.profileCommand(src.toString(), prof, canvas.width(), canvas.height(), cfg.fps(), "FIT", 0,
                            true, EncoderSettings.DEFAULT_PRESET));
                }
                String pkey = "soak/profile-" + c.id() + ".ts";
                library.put(pkey, prof);
                profileKeys.put(key, pkey);
            }
        }
    }

    private void wire() throws IOException {
        Files.createDirectories(work);
        FileStorageService storage = mock(FileStorageService.class);
        doAnswer(inv -> {
            Path from = library.get(inv.<String>getArgument(0));
            if (from == null) {
                throw new IOException("No such soak file " + inv.getArgument(0));
            }
            Files.copy(from, inv.<Path>getArgument(1), StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(storage).downloadFileToPath(anyString(), any(Path.class));
        cache = new MediaCache(storage, work.toString(), 50);
        StreamPreparer preparer = new StreamPreparer(storage, null, null, null);
        ReflectionTestUtils.setField(preparer, "workDirRoot", work.toString());

        PlayoutCopies copies = mock(PlayoutCopies.class);
        // The studio plays the originals (no FO-1 playout copy); copy mode gets the profile copies.
        when(copies.keyFor(anyString(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(copies.profileKeyFor(anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyDouble(), anyBoolean()))
                .thenAnswer(inv -> profileKeys.get(inv.<String>getArgument(0)));

        // The database: only the saved playlist position matters, and it round-trips like the real column.
        LiveStreamRepository streams = mock(LiveStreamRepository.class);
        when(streams.saveResumePosition(anyLong(), anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            savedPositions.put(inv.getArgument(0), inv.getArgument(1));
            return 1;
        });
        when(streams.findById(anyLong())).thenAnswer(inv -> {
            LiveStream s = new LiveStream();
            s.setResumePosition(savedPositions.get(inv.<Long>getArgument(0)));
            return Optional.of(s);
        });
        StreamJobRepository jobs = mock(StreamJobRepository.class);

        StudioBuilder builder = new StudioBuilder(cache, mock(IngestReader.class), mock(MediaProbe.class), copies,
                EncoderSettings.DEFAULT);
        studioRuns = new StudioRuns(builder, streams, jobs, preparer);
        copyRuns = new CopyRuns(copies, cache, streams, jobs, preparer, true);
    }

    private StreamSpec specFor(int k) {
        List<StudioSpec.Media> videos = new ArrayList<>();
        for (Clip c : CLIPS) {
            videos.add(new StudioSpec.Media(c.id(), "soak/clip-" + c.id() + ".mp4", "Clip " + c.id(), c.seconds(), true));
        }
        // Copy mode needs a stream with nothing drawn live; the studio-only soak carries the overlays most streams have.
        List<StudioSpec.OverlaySetting> overlays = cfg.copyMode() ? List.of() : List.of(
                new StudioSpec.OverlaySetting("np", "TEXT", true, 0.03, 0.05, 0.5, 0.045, "Now playing: {title}", null, null,
                        "#ffffff", "#99000000", 1, null, null, 0, null),
                new StudioSpec.OverlaySetting("clock", "CLOCK", true, 0.8, 0.05, 0.15, 0.04, null, null, null,
                        "#ffffff", "#99000000", 1, "Asia/Kolkata", null, 0, null));
        StudioSpec studio = new StudioSpec(cfg.height(), false, cfg.fps(), "PLAYLIST", videos, "IN_ORDER", 0, List.of(), "IN_ORDER",
                "KEEP", 1.0, 1.0, overlays, List.of(), StudioSpec.LIVE, "Asia/Kolkata", null, null, false, "FIT", 0);
        List<StreamSpec.Destination> dests = new ArrayList<>();
        for (int d = 1; d <= cfg.destinations(); d++) {
            dests.add(new StreamSpec.Destination("sink " + d, "unused", false));
        }
        return StreamSpec.studio("soak", 7000L + k, studio, dests);
    }

    private void start(Run r) throws Exception {
        List<List<String>> relays = new ArrayList<>();
        for (int d = 1; d <= cfg.destinations(); d++) {
            SoakSink sink = new SoakSink(r.name + "-d" + d, Duration.ofSeconds(30).toNanos());
            r.sinks.add(sink);
            relays.add(FFmpegCommandBuilder.buildRelayCommand(sink.url()));
        }
        StreamRunner.Settings settings = StreamRunner.Settings.defaults();
        if (cfg.copyMode()) {
            CopyRuns.Handle c = copyRuns.prepare(r.jobId, r.streamId, r.spec.studio(), StudioSpec.LIVE, copyRuns.resumeFor(r.spec));
            if (c == null) {
                throw new IllegalStateException("The soak stream didn't qualify for copy mode: " + CopyRuns.ineligible(r.spec.studio(), StudioSpec.LIVE));
            }
            r.copy = c;
            r.mode = "copy";
            r.runner = new StreamRunner(r.jobId, c.player(), relays, r, settings).seamless();
        } else {
            StudioRuns.Handle h = studioRuns.start(r.jobId, r.spec, StudioSpec.LIVE, null, null);
            r.studio = h;
            r.mode = "studio";
            h.studio().start();
            r.runner = new StreamRunner(r.jobId, h.studio(), relays, r, settings);
        }
        r.runner.start();
        say(r.name + " started in " + r.mode + " mode, sinks " + r.sinks.stream().map(SoakSink::url).toList());
    }

    /** What the worker's loop does every few seconds: report and save the position. */
    private void heartbeat() {
        for (Run r : runs) {
            try {
                CopyRuns.Handle c = r.copy;
                StudioRuns.Handle h = r.studio;
                if (c != null) {
                    copyRuns.report(r.jobId, WORKER, c);
                } else if (h != null) {
                    studioRuns.report(r.jobId, WORKER, h);
                }
            } catch (RuntimeException e) {
                r.note("report failed: " + e);
            }
        }
    }

    // ---------------------------------------------------------------- switches (FO-2)

    private void switchOnce(Run r, int n) {
        if (r.finishedReason != null) {
            return;
        }
        captureAll(r, "switch " + n + " from " + r.mode);
        try {
            boolean toStudio = "copy".equals(r.mode);
            Map<String, Object> sw = toStudio ? toStudio(r) : toCopy(r);
            sw.put("n", n);
            r.switches.add(sw);
            say(r.name + " switch " + n + ": " + sw);
        } catch (Exception e) {
            Map<String, Object> sw = new LinkedHashMap<>();
            sw.put("n", n);
            sw.put("ok", false);
            sw.put("error", String.valueOf(e));
            r.switches.add(sw);
            r.note("switch " + n + " failed: " + e);
        }
    }

    /** As StreamWorker.switchToStudio: save the position, build the studio there, hand off, close the copy. */
    private Map<String, Object> toStudio(Run r) throws Exception {
        CopyRuns.Handle c = r.copy;
        StudioSpec.Position before = c.player().position();
        long t0 = System.nanoTime();
        copyRuns.savePosition(r.jobId, WORKER, c);
        StreamSpec spec = r.spec.withStudio(c.spec());
        StudioRuns.Handle h = studioRuns.start(r.jobId, spec, StudioSpec.LIVE, null, null);
        h.studio().start();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        boolean started = r.runner.handOff(h.studio(), HAND_OFF_TIMEOUT, done::complete);
        if (!started) {
            studioRuns.close(h);
            return switchResult("studio", false, t0, before, null, "hand-off refused");
        }
        boolean ok = done.get(HAND_OFF_TIMEOUT.toSeconds() + 15, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - t0) / 1e9;
        if (ok) {
            r.studio = h;
            r.copy = null;
            r.mode = "studio";
            copyRuns.close(c);
            Thread.sleep(1000);
            return switchResult("studio", true, t0, before, studioRuns.position(h), null, seconds);
        }
        studioRuns.close(h);
        return switchResult("studio", false, t0, before, null, "studio didn't take over in time");
    }

    /** As StreamWorker.maybeMoveToCopy: position from the studio, copy player there, hand off, close the studio. */
    private Map<String, Object> toCopy(Run r) throws Exception {
        StudioRuns.Handle h = r.studio;
        StudioSpec.Position at = studioRuns.position(h);
        long t0 = System.nanoTime();
        CopyRuns.Handle c = at == null ? null : copyRuns.prepare(r.jobId, r.streamId, studioRuns.spec(h), StudioSpec.LIVE, at);
        if (c == null) {
            return switchResult("copy", false, t0, at, null, "copy mode not available");
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        boolean started = r.runner.handOff(c.player(), HAND_OFF_TIMEOUT, done::complete);
        if (!started) {
            copyRuns.close(c);
            return switchResult("copy", false, t0, at, null, "hand-off refused");
        }
        boolean ok = done.get(HAND_OFF_TIMEOUT.toSeconds() + 15, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - t0) / 1e9;
        if (ok) {
            r.copy = c;
            r.studio = null;
            r.mode = "copy";
            r.addStudioFrames(h);
            studioRuns.close(h);
            Thread.sleep(1000);
            return switchResult("copy", true, t0, at, c.player().position(), null, seconds);
        }
        copyRuns.close(c);
        return switchResult("copy", false, t0, at, null, "copy didn't take over in time");
    }

    private Map<String, Object> switchResult(String to, boolean ok, long t0, StudioSpec.Position before,
                                             StudioSpec.Position after, String error) {
        return switchResult(to, ok, t0, before, after, error, (System.nanoTime() - t0) / 1e9);
    }

    private Map<String, Object> switchResult(String to, boolean ok, long t0, StudioSpec.Position before,
                                             StudioSpec.Position after, String error, double seconds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("to", to);
        m.put("ok", ok);
        m.put("seconds", round(seconds, 2));
        m.put("atSeconds", round((t0 - started) / 1e9, 1));
        if (error != null) {
            m.put("error", error);
        }
        if (before != null && after != null && before.itemId() != null && before.itemId().equals(after.itemId())) {
            // Where the playlist is after the switch against where it would be had nothing changed (1 s after the hand-off).
            double expected = before.offsetSeconds() + (System.nanoTime() - t0) / 1e9;
            m.put("positionJumpSeconds", round(after.offsetSeconds() - expected, 2));
        }
        return m;
    }

    // ---------------------------------------------------------------- samples

    private void captureAll(Run r, String reason) {
        for (SoakSink s : r.sinks) {
            Path file = samplesDir.resolve(String.format("%03d-%s.flv", captureSeq.incrementAndGet(), s.name()));
            s.capture(file, 20, reason, c -> checker.submit(() -> check(s.name(), c)));
        }
    }

    /** A sample around a moment, probed the way TsStitcherTest checks continuity, and fully decoded. */
    private void check(String sink, FlvTimeline.Capture c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sink", sink);
        m.put("reason", c.reason);
        m.put("file", c.file.getFileName().toString());
        try {
            List<TsStitcherTest.Packet> packets = TsStitcherTest.probe(c.file);
            SampleCheck sc = SampleCheck.of(packets);
            m.putAll(sc.json());
            // FLV timestamps are whole milliseconds; with the default 1/30 s output time base two frames can round to
            // the same tick and the null muxer complains, which says nothing about the stream. Keep milliseconds.
            Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", c.file.toString(), "-enc_time_base:v", "1:1000",
                    "-f", "null", "-")
                    .redirectErrorStream(true).start();
            String errors = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            long errorLines = errors.lines().filter(l -> !l.isBlank()).count();
            m.put("decodeErrorLines", errorLines);
            boolean ok = sc.ok() && errorLines < Thresholds.SAMPLE_DECODE_ERROR_LINES;
            m.put("ok", ok);
            if (ok) {
                Files.deleteIfExists(c.file); // keep only the ones that show a problem
            } else if (!errors.isBlank()) {
                m.put("decodeErrors", errors.lines().limit(5).toList());
            }
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", String.valueOf(e));
        }
        sampleResults.add(m);
    }

    /** Continuity of one probed sample, with the same limits as {@code TsStitcherTest.assertContinuous}. */
    record SampleCheck(int video, int audio, int backwards, double maxVideoGap, double maxAudioGap) {
        static SampleCheck of(List<TsStitcherTest.Packet> packets) {
            int v = 0;
            int a = 0;
            int back = 0;
            double gv = 0;
            double ga = 0;
            double pv = Double.NEGATIVE_INFINITY;
            double pa = Double.NEGATIVE_INFINITY;
            for (TsStitcherTest.Packet p : packets) {
                boolean video = p.type().equals("video");
                double t = video ? p.dts() : p.pts();
                if (Double.isNaN(t)) {
                    continue;
                }
                double prev = video ? pv : pa;
                if (prev != Double.NEGATIVE_INFINITY) {
                    if (t <= prev) {
                        back++;
                    }
                    if (video) {
                        gv = Math.max(gv, t - prev);
                    } else {
                        ga = Math.max(ga, t - prev);
                    }
                }
                if (video) {
                    v++;
                    pv = t;
                } else {
                    a++;
                    pa = t;
                }
            }
            return new SampleCheck(v, a, back, gv, ga);
        }

        boolean ok() {
            return video > 0 && audio > 0 && backwards == 0 && maxVideoGap < FlvTimeline.VIDEO_GAP_LIMIT_SECONDS
                    && maxAudioGap < FlvTimeline.AUDIO_GAP_LIMIT_SECONDS;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("videoPackets", video);
            m.put("audioPackets", audio);
            m.put("backwards", backwards);
            m.put("maxVideoGapSeconds", round(maxVideoGap, 3));
            m.put("maxAudioGapSeconds", round(maxAudioGap, 3));
            return m;
        }
    }

    // ---------------------------------------------------------------- results

    private Map<String, String> modes() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Run r : runs) {
            if (r.finishedReason == null && r.mode != null) {
                m.put(r.name, r.mode);
            }
        }
        return m;
    }

    /** Which stream an FFmpeg process belongs to: by its work folder (encoder, decoders, copy list) or sink port (relays). */
    private String streamOfCommand(String cmd) {
        for (Run r : runs) {
            if (cmd.contains("job-" + r.jobId + "-") || cmd.contains("job" + r.jobId + "-")) {
                return r.name;
            }
            for (SoakSink s : r.sinks) {
                if (cmd.contains(s.url())) {
                    return r.name;
                }
            }
        }
        return null;
    }

    private void closeProducers(Run r) {
        CopyRuns.Handle c = r.copy;
        StudioRuns.Handle h = r.studio;
        if (c != null) {
            copyRuns.close(c);
        }
        if (h != null) {
            r.addStudioFrames(h);
            studioRuns.close(h);
        }
    }

    private Map<String, Object> streamJson(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name);
        m.put("modeAtEnd", r.mode);
        m.put("finishedReason", r.finishedReason);
        m.put("endedEarly", r.errors.stream().anyMatch(e -> e.contains("ended unexpectedly")));
        m.put("encoderStarts", r.encoderStarts.get());
        m.put("encoderFailures", r.encoderFailures.get());
        Map<String, Object> speeds = new LinkedHashMap<>();
        r.speed.forEach((mode, s) -> speeds.put(mode, s.json()));
        m.put("speed", speeds);
        m.put("studioFramesOut", r.studioFramesOut.get());
        m.put("studioFramesDropped", r.studioFramesDropped.get());
        m.put("switches", List.copyOf(r.switches));
        List<Map<String, Object>> dests = new ArrayList<>();
        for (int i = 0; i < r.sinks.size(); i++) {
            SoakSink s = r.sinks.get(i);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", s.name());
            List<FlvTimeline> conns = s.connections();
            d.put("connections", conns.size());
            d.put("reconnectingEvents", r.reconnecting.getOrDefault(i, new AtomicInteger()).get());
            d.put("notReachingEvents", r.notReaching.getOrDefault(i, new AtomicInteger()).get());
            List<Map<String, Object>> cs = new ArrayList<>();
            for (FlvTimeline t : conns) {
                cs.add(timelineJson(t.stats()));
            }
            d.put("timelines", cs);
            dests.add(d);
        }
        m.put("destinations", dests);
        m.put("errors", List.copyOf(r.errors));
        m.put("lastLogLines", List.copyOf(r.lastLogLines));
        return m;
    }

    static Map<String, Object> timelineJson(FlvTimeline.Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytes", s.bytes());
        m.put("avgKbps", s.wallSeconds() > 0 ? Math.round(s.bytes() * 8 / 1000.0 / s.wallSeconds()) : 0);
        m.put("videoTags", s.videoTags());
        m.put("audioTags", s.audioTags());
        m.put("keyframes", s.keyframes());
        m.put("mediaSeconds", round(s.mediaSeconds(), 1));
        m.put("wallSeconds", round(s.wallSeconds(), 1));
        m.put("backwardsVideo", s.backwardsVideo());
        m.put("backwardsAudio", s.backwardsAudio());
        m.put("maxVideoGapSeconds", s.maxVideoGapSeconds());
        m.put("maxAudioGapSeconds", s.maxAudioGapSeconds());
        m.put("videoGapsOverLimit", s.videoGapsOverLimit());
        m.put("audioGapsOverLimit", s.audioGapsOverLimit());
        m.put("avDriftMs", s.avDriftMs());
        m.put("mediaVsWallDriftMs", s.lagDriftMs());
        m.put("mediaVsWallAtEndMs", s.lagEndMs());
        m.put("stalls", s.stalls());
        m.put("maxStallSeconds", round(s.maxStallSeconds(), 2));
        m.put("midStreamSequenceHeaders", s.midStreamHeaders());
        m.put("changedSequenceHeaders", s.changedHeaders());
        m.put("sequenceHeaderEvents", s.headerEvents());
        List<List<Object>> w = new ArrayList<>();
        for (FlvTimeline.Window x : s.windows()) {
            w.add(List.of(round(x.atSeconds(), 0), x.avMs(), x.lagMs()));
        }
        m.put("windows[atSeconds,avMs,lagMs]", w);
        return m;
    }

    /** Thread pools that start on first use and stay (or idle out after a minute): not a leak. */
    static final List<String> JVM_POOLS = List.of("process reaper", "ForkJoinPool", "CompilerThread", "media-prefetch",
            "Attach Listener", "Common-Cleaner", "Notification Thread", "surefire", "CompletableFutureDelayScheduler",
            "Java2D Disposer");

    static List<String> threadNames() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName).sorted().toList();
    }

    /** Names in {@code after} beyond those in {@code before} (as a multiset). */
    static List<String> newThreads(List<String> before, List<String> after) {
        List<String> left = new ArrayList<>(before);
        List<String> out = new ArrayList<>();
        for (String n : after) {
            if (!left.remove(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private int leftovers() {
        if (!Files.isDirectory(work)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(work)) {
            Path cacheDir = work.resolve("cache");
            return (int) s.filter(Files::isRegularFile).filter(p -> !p.startsWith(cacheDir)).count();
        } catch (IOException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private int cachePins() {
        Object pins = ReflectionTestUtils.getField(cache, "pins");
        if (pins instanceof Map<?, ?> m) {
            synchronized (cache) {
                return m.size();
            }
        }
        return -1;
    }

    private static Map<String, Object> machine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpus", Runtime.getRuntime().availableProcessors());
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("java", System.getProperty("java.version"));
        m.put("maxHeapMb", Runtime.getRuntime().maxMemory() / 1048576);
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            m.put("ffmpeg", new String(p.getInputStream().readAllBytes()).lines().findFirst().orElse("?"));
            p.waitFor();
        } catch (Exception e) {
            m.put("ffmpeg", "?");
        }
        try {
            m.put("loadAverage", Files.readString(Path.of("/proc/loadavg")).trim());
        } catch (IOException e) {
            m.put("loadAverage", ManagementLoad.load());
        }
        return m;
    }

    private static final class ManagementLoad {
        static String load() {
            return String.valueOf(java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        }
    }

    private static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private synchronized void say(String s) {
        String line = Instant.now() + " " + s;
        System.out.println("[soak] " + line);
        log.append(line).append('\n');
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
