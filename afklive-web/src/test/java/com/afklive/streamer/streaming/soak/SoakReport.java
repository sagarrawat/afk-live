package com.afklive.streamer.streaming.soak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import static com.afklive.streamer.streaming.soak.SoakHarness.round;

/**
 * OPS-6: turns a soak run's measurements into checks with pass/fail limits ({@link Thresholds}), a
 * machine-readable {@code summary.json} and a short {@code report.md}.
 */
public final class SoakReport {

    /** One check. {@code judged} false: shown for information, or the run was too short to judge it. */
    public record Check(String group, String name, boolean judged, boolean pass, String measured, String limit) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", group);
            m.put("name", name);
            m.put("judged", judged);
            m.put("pass", pass);
            m.put("measured", measured);
            m.put("limit", limit);
            return m;
        }
    }

    private final Map<String, Object> summary;
    private final List<Check> checks;
    private final boolean pass;

    private SoakReport(Map<String, Object> summary, List<Check> checks) {
        this.summary = summary;
        this.checks = checks;
        this.pass = checks.stream().allMatch(c -> !c.judged() || c.pass());
        summary.put("pass", pass);
    }

    public boolean pass() {
        return pass;
    }

    public List<Check> checks() {
        return checks;
    }

    public Map<String, Object> summary() {
        return summary;
    }

    public List<Check> failures() {
        return checks.stream().filter(c -> c.judged() && !c.pass()).toList();
    }

    // ---------------------------------------------------------------- evaluation

    @SuppressWarnings("unchecked")
    static SoakReport evaluate(SoakHarness.Config cfg, double ranSeconds, List<ProcessSampler.Sample> series, int fds0,
                               int threads0, Map<String, Object> afterStop, List<Map<String, Object>> streams,
                               List<Map<String, Object>> samples, Map<String, Map<String, Double>> ffmpegCores,
                               Map<String, Double> jvmCores, double unattributedCpu, Map<String, Object> machine) {
        List<Check> checks = new ArrayList<>();

        // Destinations: what a platform would see.
        int maxConnections = 0;
        int reconnecting = 0;
        int notReaching = 0;
        List<Map<String, Object>> timelines = new ArrayList<>();
        for (Map<String, Object> s : streams) {
            for (Map<String, Object> d : (List<Map<String, Object>>) s.get("destinations")) {
                maxConnections = Math.max(maxConnections, (int) d.get("connections"));
                reconnecting += (int) d.get("reconnectingEvents");
                notReaching += (int) d.get("notReachingEvents");
                timelines.addAll((List<Map<String, Object>>) d.get("timelines"));
            }
        }
        int minConnections = streams.stream().flatMap(s -> ((List<Map<String, Object>>) s.get("destinations")).stream())
                .mapToInt(d -> (int) d.get("connections")).min().orElse(0);
        checks.add(new Check("Destinations", "Each destination stayed on one connection", true,
                minConnections == Thresholds.CONNECTIONS_PER_DESTINATION && maxConnections == Thresholds.CONNECTIONS_PER_DESTINATION,
                "connections per destination: " + minConnections + "–" + maxConnections, "exactly 1"));
        checks.add(new Check("Destinations", "No reconnects reported by the runner", true, reconnecting == 0,
                String.valueOf(reconnecting), "0"));
        checks.add(new Check("Destinations", "No destination shown as not reaching", true, notReaching == 0,
                String.valueOf(notReaching), "0"));

        // Producers.
        long endedEarly = streams.stream().filter(s -> Boolean.TRUE.equals(s.get("endedEarly"))).count();
        int failures = streams.stream().mapToInt(s -> (int) s.get("encoderFailures")).sum();
        checks.add(new Check("Producers", "Every stream ran to the end", true, endedEarly == 0, endedEarly + " ended early", "0"));
        checks.add(new Check("Producers", "No producer crashed or restarted", true, failures == 0, String.valueOf(failures), "0"));
        double worstSlowShare = 0;
        double minSpeed = Double.MAX_VALUE;
        long readings = 0;
        for (Map<String, Object> s : streams) {
            for (Object v : ((Map<String, Object>) s.get("speed")).values()) {
                Map<String, Object> sp = (Map<String, Object>) v;
                readings += ((Number) sp.get("readings")).longValue();
                worstSlowShare = Math.max(worstSlowShare, ((Number) sp.get("belowShare")).doubleValue());
                if (sp.get("min") != null) {
                    minSpeed = Math.min(minSpeed, ((Number) sp.get("min")).doubleValue());
                }
            }
        }
        checks.add(new Check("Producers", "Encoders kept up with real time", readings > 0,
                worstSlowShare <= Thresholds.SLOW_SPEED_SHARE,
                String.format(Locale.ROOT, "worst %.2f%% of readings below %.1fx (min %.2fx, %d readings)", worstSlowShare * 100,
                        Thresholds.SLOW_SPEED, minSpeed == Double.MAX_VALUE ? 0 : minSpeed, readings),
                String.format(Locale.ROOT, "≤ %.0f%% below %.1fx", Thresholds.SLOW_SPEED_SHARE * 100, Thresholds.SLOW_SPEED)));
        long framesOut = streams.stream().mapToLong(s -> ((Number) s.get("studioFramesOut")).longValue()).sum();
        long dropped = streams.stream().mapToLong(s -> ((Number) s.get("studioFramesDropped")).longValue()).sum();
        double droppedShare = framesOut == 0 ? 0 : (double) dropped / framesOut;
        checks.add(new Check("Producers", "Studio clock dropped almost no frames", framesOut > 0,
                droppedShare <= Thresholds.STUDIO_DROPPED_SHARE,
                String.format(Locale.ROOT, "%d of %d (%.3f%%)", dropped, framesOut, droppedShare * 100),
                String.format(Locale.ROOT, "≤ %.1f%%", Thresholds.STUDIO_DROPPED_SHARE * 100)));

        // Timeline, every tag of every connection.
        long backwards = sumL(timelines, "backwardsVideo") + sumL(timelines, "backwardsAudio");
        double videoGap = maxD(timelines, "maxVideoGapSeconds");
        double audioGap = maxD(timelines, "maxAudioGapSeconds");
        long avDrift = (long) maxD(timelines, "avDriftMs");
        long wallDrift = (long) maxD(timelines, "mediaVsWallDriftMs");
        long stalls = sumL(timelines, "stalls");
        long headers = sumL(timelines, "midStreamSequenceHeaders");
        boolean any = !timelines.isEmpty();
        checks.add(new Check("Timeline", "Timestamps only go forward", any, any && backwards == 0, backwards + " steps back", "0"));
        checks.add(new Check("Timeline", "No hole in the video timeline", any, videoGap < FlvTimeline.VIDEO_GAP_LIMIT_SECONDS,
                "largest step " + videoGap + " s", "< " + FlvTimeline.VIDEO_GAP_LIMIT_SECONDS + " s"));
        checks.add(new Check("Timeline", "No hole in the audio timeline", any, audioGap < FlvTimeline.AUDIO_GAP_LIMIT_SECONDS,
                "largest step " + audioGap + " s", "< " + FlvTimeline.AUDIO_GAP_LIMIT_SECONDS + " s"));
        checks.add(new Check("Timeline", "A/V drift", any, avDrift <= Thresholds.AV_DRIFT_MS, avDrift + " ms",
                "≤ " + Thresholds.AV_DRIFT_MS + " ms"));
        checks.add(new Check("Timeline", "Media timeline kept pace with the clock", any, wallDrift <= Thresholds.MEDIA_VS_WALL_DRIFT_MS,
                wallDrift + " ms", "≤ " + Thresholds.MEDIA_VS_WALL_DRIFT_MS + " ms"));
        checks.add(new Check("Timeline", "No stalls (2 s without data)", any, stalls == 0, String.valueOf(stalls), "0"));
        long changedHeaders = timelines.stream().mapToLong(r -> ((Number) r.getOrDefault("changedSequenceHeaders", 0L)).longValue()).sum();
        checks.add(new Check("Timeline", "Sequence headers sent again mid-stream", false, true,
                headers + " (" + changedHeaders + " with different content)", "info"));
        long badSamples = samples.stream().filter(s -> !Boolean.TRUE.equals(s.get("ok"))).count();
        checks.add(new Check("Timeline", "Samples decode cleanly and are continuous (ffprobe/ffmpeg)", !samples.isEmpty(),
                badSamples == 0, (samples.size() - badSamples) + " of " + samples.size() + " clean", "all"));

        // Switches (copy mode).
        if (cfg.copyMode()) {
            List<Map<String, Object>> sw = new ArrayList<>();
            streams.forEach(s -> sw.addAll((List<Map<String, Object>>) s.get("switches")));
            long ok = sw.stream().filter(x -> Boolean.TRUE.equals(x.get("ok"))).count();
            double maxSeconds = sw.stream().filter(x -> Boolean.TRUE.equals(x.get("ok")))
                    .mapToDouble(x -> ((Number) x.get("seconds")).doubleValue()).max().orElse(0);
            double maxJump = sw.stream().filter(x -> x.get("positionJumpSeconds") != null)
                    .mapToDouble(x -> Math.abs(((Number) x.get("positionJumpSeconds")).doubleValue())).max().orElse(0);
            checks.add(new Check("Switches", "Every studio↔copy switch succeeded", true, !sw.isEmpty() && ok == sw.size(),
                    ok + " of " + sw.size(), "all, at least 1"));
            checks.add(new Check("Switches", "Hand-off time", ok > 0, maxSeconds <= Thresholds.SWITCH_SECONDS,
                    "longest " + maxSeconds + " s", "≤ " + Thresholds.SWITCH_SECONDS + " s"));
            checks.add(new Check("Switches", "Playlist position jump at a switch", false, true,
                    "largest " + maxJump + " s (copy starts at a keyframe, up to 2 s back)", "info"));
            double lagAtEnd = timelines.stream().mapToDouble(r -> ((Number) r.getOrDefault("mediaVsWallAtEndMs", 0L)).doubleValue())
                    .average().orElse(0);
            double perSwitch = ok == 0 ? 0 : lagAtEnd / ((double) ok / Math.max(1, streams.size()));
            checks.add(new Check("Switches", "Media timeline moved against the clock per switch", false, true,
                    String.format(Locale.ROOT, "%+.0f ms per switch (%+.0f ms at the end; + is ahead of real time)", perSwitch, lagAtEnd),
                    "info"));
        }

        // Memory and resources.
        double warm = cfg.memoryWarmup().toSeconds();
        List<ProcessSampler.Sample> steady = series.stream().filter(s -> s.atSeconds() >= warm).toList();
        double steadyMinutes = steady.size() < 2 ? 0 : (steady.getLast().atSeconds() - steady.getFirst().atSeconds()) / 60;
        boolean judgeSlopes = steadyMinutes >= Thresholds.SLOPE_MIN_MINUTES;
        String note = judgeSlopes ? "" : String.format(Locale.ROOT, " (not judged: %.0f min steady, needs %.0f)", steadyMinutes,
                Thresholds.SLOPE_MIN_MINUTES);
        double heapSlope = slopePerHour(steady.stream().filter(s -> !Double.isNaN(s.heapAfterGcMb())).toList(),
                ProcessSampler.Sample::heapAfterGcMb);
        double rssSlope = slopePerHour(steady, ProcessSampler.Sample::rssMb);
        double ffSlope = slopePerHour(steady, ProcessSampler.Sample::ffmpegRssMb);
        checks.add(new Check("Memory", "Heap after full GC: no growth", judgeSlopes, heapSlope <= Thresholds.HEAP_SLOPE_MB_PER_HOUR,
                String.format(Locale.ROOT, "%+.1f MB/h%s", heapSlope, note), "≤ " + Thresholds.HEAP_SLOPE_MB_PER_HOUR + " MB/h"));
        checks.add(new Check("Memory", "Worker JVM resident memory: no growth", judgeSlopes, rssSlope <= Thresholds.RSS_SLOPE_MB_PER_HOUR,
                String.format(Locale.ROOT, "%+.1f MB/h%s", rssSlope, note), "≤ " + Thresholds.RSS_SLOPE_MB_PER_HOUR + " MB/h"));
        checks.add(new Check("Memory", "FFmpeg resident memory: no growth", judgeSlopes, ffSlope <= Thresholds.FFMPEG_RSS_SLOPE_MB_PER_HOUR,
                String.format(Locale.ROOT, "%+.1f MB/h%s", ffSlope, note), "≤ " + Thresholds.FFMPEG_RSS_SLOPE_MB_PER_HOUR + " MB/h"));
        int fdGrowth = growth(steady, ProcessSampler.Sample::fds);
        int threadGrowth = growth(steady, ProcessSampler.Sample::threads);
        boolean enough = steady.size() >= 10;
        checks.add(new Check("Resources", "Open file descriptors: no growth while running", enough, fdGrowth <= Thresholds.FD_GROWTH,
                (fdGrowth >= 0 ? "+" : "") + fdGrowth + " (last fifth vs first fifth, peaks)", "≤ +" + Thresholds.FD_GROWTH));
        checks.add(new Check("Resources", "Threads: no growth while running", enough, threadGrowth <= Thresholds.THREAD_GROWTH,
                (threadGrowth >= 0 ? "+" : "") + threadGrowth + " (last fifth vs first fifth, peaks)", "≤ +" + Thresholds.THREAD_GROWTH));
        int fdsAfter = (int) afterStop.get("fds");
        int threadsAfter = (int) afterStop.get("threads");
        checks.add(new Check("Resources", "Descriptors back to the start after stopping", fds0 >= 0,
                fdsAfter - fds0 <= Thresholds.FD_AFTER_STOP, fds0 + " → " + fdsAfter, "≤ +" + Thresholds.FD_AFTER_STOP));
        checks.add(new Check("Resources", "Threads back to the start after stopping", true,
                threadsAfter - threads0 <= Thresholds.THREADS_AFTER_STOP, threads0 + " → " + threadsAfter,
                "≤ +" + Thresholds.THREADS_AFTER_STOP));
        int orphans = (int) afterStop.get("ffmpegProcesses");
        int leftovers = (int) afterStop.get("workDirLeftovers");
        int pins = (int) afterStop.get("cachePins");
        checks.add(new Check("Resources", "No FFmpeg left running, no work files, no cache pins after stopping", true,
                orphans == 0 && leftovers == 0 && pins == 0,
                orphans + " processes, " + leftovers + " files, " + pins + " pins", "0, 0, 0"));

        // CPU (information: depends on the machine).
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("ffmpegCoresByStreamAndMode", roundNested(ffmpegCores));
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvmCores.forEach((k, v) -> jvm.put(k, round(v, 3)));
        cpu.put("jvmCoresByMode", jvm);
        Map<String, Object> perStream = new LinkedHashMap<>();
        for (String mode : List.of("studio", "copy")) {
            double ff = ffmpegCores.values().stream().filter(m -> m.containsKey(mode)).mapToDouble(m -> m.get(mode)).average().orElse(Double.NaN);
            if (!Double.isNaN(ff)) {
                double j = jvmCores.getOrDefault(mode, 0.0) / Math.max(1, cfg.streams());
                perStream.put(mode, round(ff + j, 3));
            }
        }
        cpu.put("coresPerStreamByMode", perStream);
        cpu.put("unattributedFfmpegCpuSeconds", round(unattributedCpu, 1));
        for (Map.Entry<String, Object> e : perStream.entrySet()) {
            checks.add(new Check("CPU", "CPU per stream, " + e.getKey(), false, true,
                    String.format(Locale.ROOT, "%.2f cores (FFmpeg + JVM share)", ((Number) e.getValue()).doubleValue()), "info"));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("story", "OPS-6");
        summary.put("pass", false);
        summary.put("finishedAt", Instant.now().toString());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("minutes", round(cfg.duration().toMillis() / 60000.0, 2));
        config.put("ranMinutes", round(ranSeconds / 60, 2));
        config.put("copyMode", cfg.copyMode());
        config.put("streams", cfg.streams());
        config.put("destinationsPerStream", cfg.destinations());
        config.put("canvas", cfg.height() + "p" + cfg.fps());
        config.put("switchEveryMinutes", cfg.copyMode() ? round(cfg.switchEvery().toMillis() / 60000.0, 2) : null);
        config.put("memoryWarmupMinutes", round(cfg.memoryWarmup().toMillis() / 60000.0, 2));
        summary.put("config", config);
        summary.put("machine", machine);
        List<Map<String, Object>> cj = new ArrayList<>();
        checks.forEach(c -> cj.add(c.json()));
        summary.put("checks", cj);
        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put("before", Map.of("fds", fds0, "threads", threads0));
        proc.put("afterStop", afterStop);
        Map<String, Object> slopes = new LinkedHashMap<>();
        slopes.put("judged", judgeSlopes);
        slopes.put("steadyMinutes", round(steadyMinutes, 1));
        slopes.put("heapAfterGcMbPerHour", round(heapSlope, 2));
        slopes.put("rssMbPerHour", round(rssSlope, 2));
        slopes.put("ffmpegRssMbPerHour", round(ffSlope, 2));
        slopes.put("fdGrowth", fdGrowth);
        slopes.put("threadGrowth", threadGrowth);
        proc.put("slopes", slopes);
        proc.put("peakRssMb", round(series.stream().mapToDouble(ProcessSampler.Sample::rssMb).max().orElse(0), 1));
        proc.put("peakFfmpegRssMb", round(series.stream().mapToDouble(ProcessSampler.Sample::ffmpegRssMb).max().orElse(0), 1));
        summary.put("process", proc);
        summary.put("cpu", cpu);
        summary.put("streams", streams);
        summary.put("samples", samples);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProcessSampler.Sample s : series) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("t", round(s.atSeconds(), 1));
            r.put("rssMb", round(s.rssMb(), 1));
            r.put("heapMb", round(s.heapUsedMb(), 1));
            r.put("heapAfterGcMb", Double.isNaN(s.heapAfterGcMb()) ? null : round(s.heapAfterGcMb(), 1));
            r.put("fds", s.fds());
            r.put("threads", s.threads());
            r.put("ffmpeg", s.ffmpegProcesses());
            r.put("ffmpegRssMb", round(s.ffmpegRssMb(), 1));
            r.put("jvmCores", round(s.jvmCores(), 3));
            r.put("ffmpegCores", round(s.ffmpegCores(), 3));
            rows.add(r);
        }
        summary.put("series", rows);
        return new SoakReport(summary, checks);
    }

    /** Least-squares slope of {@code value} against time, per hour; 0 with fewer than two points. */
    static double slopePerHour(List<ProcessSampler.Sample> samples, ToDoubleFunction<ProcessSampler.Sample> value) {
        int n = samples.size();
        if (n < 2) {
            return 0;
        }
        double mx = 0;
        double my = 0;
        for (ProcessSampler.Sample s : samples) {
            mx += s.atSeconds() / 3600;
            my += value.applyAsDouble(s);
        }
        mx /= n;
        my /= n;
        double num = 0;
        double den = 0;
        for (ProcessSampler.Sample s : samples) {
            double dx = s.atSeconds() / 3600 - mx;
            num += dx * (value.applyAsDouble(s) - my);
            den += dx * dx;
        }
        return den == 0 ? 0 : num / den;
    }

    /** Peak in the last fifth minus peak in the first fifth: robust to the short bumps of a switch. */
    static int growth(List<ProcessSampler.Sample> samples, ToDoubleFunction<ProcessSampler.Sample> value) {
        if (samples.size() < 5) {
            return 0;
        }
        int fifth = Math.max(1, samples.size() / 5);
        double first = samples.subList(0, fifth).stream().mapToDouble(value).max().orElse(0);
        double last = samples.subList(samples.size() - fifth, samples.size()).stream().mapToDouble(value).max().orElse(0);
        return (int) Math.round(last - first);
    }

    private static long sumL(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToLong(r -> ((Number) r.get(key)).longValue()).sum();
    }

    private static double maxD(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(r -> ((Number) r.get(key)).doubleValue()).max().orElse(0);
    }

    private static Map<String, Object> roundNested(Map<String, Map<String, Double>> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            v.forEach((mode, c) -> m.put(mode, round(c, 3)));
            out.put(k, m);
        });
        return out;
    }

    // ---------------------------------------------------------------- output

    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(dir.resolve("summary.json").toFile(), summary);
        Files.writeString(dir.resolve("report.md"), markdown());
    }

    @SuppressWarnings("unchecked")
    public String markdown() {
        Map<String, Object> c = (Map<String, Object>) summary.get("config");
        Map<String, Object> m = (Map<String, Object>) summary.get("machine");
        StringBuilder sb = new StringBuilder();
        sb.append("## OPS-6 soak: ").append(pass ? "PASS" : "FAIL").append("\n\n");
        sb.append(String.format(Locale.ROOT, "%s min (ran %s), %s stream(s) × %s destination(s), %s, %s%s.%n%n",
                c.get("minutes"), c.get("ranMinutes"), c.get("streams"), c.get("destinationsPerStream"), c.get("canvas"),
                Boolean.TRUE.equals(c.get("copyMode")) ? "copy mode with studio↔copy switches every " + c.get("switchEveryMinutes") + " min"
                        : "studio (overlays on)", ""));
        sb.append("Machine: ").append(m.get("cpus")).append(" CPUs, ").append(m.get("os")).append(", Java ").append(m.get("java"))
                .append(", ").append(m.get("ffmpeg")).append(", load at the end ").append(m.get("loadAverage")).append(".\n\n");
        sb.append("| | Check | Measured | Limit |\n| --- | --- | --- | --- |\n");
        for (Check ch : checks) {
            String mark = !ch.judged() ? "·" : ch.pass() ? "✓" : "✗";
            sb.append("| ").append(mark).append(" | ").append(ch.group()).append(": ").append(ch.name()).append(" | ")
                    .append(ch.measured().replace("|", "/")).append(" | ").append(ch.limit()).append(" |\n");
        }
        sb.append("\n✓ pass, ✗ fail, · information or not judged on a run this short. Limits: docs/operations.md, \"Soak test\".\n");
        return sb.toString();
    }
}
