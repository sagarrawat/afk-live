package com.afklive.streamer.streaming.soak;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * OPS-6: the worker process's resources over time, read the way an operator would on the server: the JVM's
 * resident memory, heap (and heap after a full GC, the number a leak moves), open file descriptors, threads,
 * and every FFmpeg child with its memory and CPU. Linux only for RSS, descriptors and per-process CPU
 * ({@code /proc}); elsewhere those read as -1.
 */
public final class ProcessSampler {

    /** One reading. CPU figures are cores used over the interval since the previous reading. */
    public record Sample(double atSeconds, double rssMb, double heapUsedMb, double heapAfterGcMb, int fds, int threads,
                         int ffmpegProcesses, double ffmpegRssMb, double jvmCores, double ffmpegCores) {
    }

    private static final boolean PROC = Files.isDirectory(Path.of("/proc/self/fd"));
    private static final double TICKS = clockTicks();

    private final long startNanos = System.nanoTime();
    private final Function<String, String> streamOf;
    private final Map<Long, Double> lastCpu = new HashMap<>();
    private final Map<Long, String> owner = new HashMap<>();
    /** FFmpeg CPU seconds per stream and mode ("studio" / "copy"). */
    private final Map<String, Map<String, Double>> cpu = new LinkedHashMap<>();
    /** Wall seconds per stream and mode, to turn CPU seconds into cores. */
    private final Map<String, Map<String, Double>> wall = new LinkedHashMap<>();
    private final Map<String, Double> jvmCpuByMode = new LinkedHashMap<>();
    private final Map<String, Double> jvmWallByMode = new LinkedHashMap<>();
    private long lastNanos = System.nanoTime();
    private double lastJvmCpu = jvmCpuSeconds();
    private double lastChildrenCpu = reapedChildrenCpuSeconds();
    private double unattributedCpu;

    /** @param streamOf which stream an FFmpeg command line belongs to (null: none of them) */
    public ProcessSampler(Function<String, String> streamOf) {
        this.streamOf = streamOf;
    }

    /**
     * Takes a reading. {@code modes} is each running stream's mode right now; {@code fullGc} runs a full
     * collection first so the heap figure is what is really retained.
     */
    public synchronized Sample sample(Map<String, String> modes, boolean fullGc) {
        long now = System.nanoTime();
        double dt = Math.max(1e-3, (now - lastNanos) / 1e9);
        lastNanos = now;
        double heapAfterGc = Double.NaN;
        if (fullGc) {
            System.gc();
            heapAfterGc = heapUsedMb();
        }
        double jvm = jvmCpuSeconds();
        double jvmCores = (jvm - lastJvmCpu) / dt;
        lastJvmCpu = jvm;
        String jvmMode = modes.isEmpty() ? "idle" : modes.values().iterator().next();
        jvmCpuByMode.merge(jvmMode, jvmCores * dt, Double::sum);
        jvmWallByMode.merge(jvmMode, dt, Double::sum);

        int procs = 0;
        double rss = 0;
        double attributed = 0;
        double liveCpu = 0;
        Map<Long, Double> seen = new HashMap<>();
        List<ProcessHandle> children = ProcessHandle.current().descendants().toList();
        for (ProcessHandle ph : children) {
            String cmd = commandLine(ph.pid());
            if (cmd == null || !cmd.contains("ffmpeg")) {
                continue;
            }
            procs++;
            rss += rssMb(ph.pid());
            double c = cpuSeconds(ph.pid());
            if (c < 0) {
                continue;
            }
            seen.put(ph.pid(), c);
            liveCpu += c;
            String stream = owner.computeIfAbsent(ph.pid(), pid -> streamOf.apply(cmd));
            double delta = c - lastCpu.getOrDefault(ph.pid(), 0.0);
            if (stream != null && delta > 0) {
                String mode = modes.getOrDefault(stream, "stopping");
                cpu.computeIfAbsent(stream, k -> new LinkedHashMap<>()).merge(mode, delta, Double::sum);
                attributed += delta;
            }
        }
        // Children that exited since the last reading were reaped by the JVM: their CPU is in cutime/cstime.
        double reaped = reapedChildrenCpuSeconds();
        double previousLive = lastCpu.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalDelta = Math.max(0, (reaped - lastChildrenCpu) + liveCpu - previousLive);
        lastChildrenCpu = reaped;
        unattributedCpu += Math.max(0, totalDelta - attributed);
        lastCpu.clear();
        lastCpu.putAll(seen);
        owner.keySet().retainAll(seen.keySet());
        modes.forEach((stream, mode) -> wall.computeIfAbsent(stream, k -> new LinkedHashMap<>()).merge(mode, dt, Double::sum));

        return new Sample((now - startNanos) / 1e9, selfRssMb(), heapUsedMb(), heapAfterGc, fds(),
                ManagementFactory.getThreadMXBean().getThreadCount(), procs, rss, jvmCores, totalDelta / dt);
    }

    /** FFmpeg cores per stream and mode, over the time the stream spent in that mode. */
    public synchronized Map<String, Map<String, Double>> ffmpegCoresByStreamAndMode() {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        wall.forEach((stream, modes) -> modes.forEach((mode, seconds) -> {
            double c = cpu.getOrDefault(stream, Map.of()).getOrDefault(mode, 0.0);
            out.computeIfAbsent(stream, k -> new LinkedHashMap<>()).put(mode, seconds > 0 ? c / seconds : 0);
        }));
        return out;
    }

    /** JVM cores while the streams were in each mode (all streams switch together). */
    public synchronized Map<String, Double> jvmCoresByMode() {
        Map<String, Double> out = new LinkedHashMap<>();
        jvmWallByMode.forEach((mode, seconds) -> out.put(mode, seconds > 0 ? jvmCpuByMode.getOrDefault(mode, 0.0) / seconds : 0));
        return out;
    }

    /** FFmpeg CPU seconds that couldn't be tied to a stream (short-lived processes between readings). */
    public synchronized double unattributedCpuSeconds() {
        return unattributedCpu;
    }

    // ---------------------------------------------------------------- readings

    public static int fds() {
        if (!PROC) {
            return -1;
        }
        try (Stream<Path> s = Files.list(Path.of("/proc/self/fd"))) {
            return (int) s.count();
        } catch (IOException e) {
            return -1;
        }
    }

    public static int threads() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    public static int ffmpegProcesses() {
        return (int) ProcessHandle.current().descendants().filter(p -> {
            String c = commandLine(p.pid());
            return c != null && c.contains("ffmpeg");
        }).count();
    }

    static double heapUsedMb() {
        long used = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                used += pool.getUsage().getUsed();
            }
        }
        return used / 1048576.0;
    }

    static double selfRssMb() {
        return statusKb("/proc/self/status", "VmRSS:") / 1024.0;
    }

    static double rssMb(long pid) {
        return Math.max(0, statusKb("/proc/" + pid + "/status", "VmRSS:") / 1024.0);
    }

    private static double statusKb(String file, String field) {
        if (!PROC) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(Path.of(file))) {
                if (line.startsWith(field)) {
                    return Double.parseDouble(line.substring(field.length()).trim().split("\\s+")[0]);
                }
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return -1;
    }

    static String commandLine(long pid) {
        if (PROC) {
            try {
                byte[] raw = Files.readAllBytes(Path.of("/proc/" + pid + "/cmdline"));
                return new String(raw, StandardCharsets.UTF_8).replace('\0', ' ');
            } catch (IOException e) {
                return null;
            }
        }
        return ProcessHandle.of(pid).flatMap(p -> p.info().commandLine()).orElse(null);
    }

    /** utime + stime of a process, in seconds; -1 when unreadable. */
    static double cpuSeconds(long pid) {
        long[] f = statFields("/proc/" + pid + "/stat");
        return f == null ? -1 : (f[0] + f[1]) / TICKS;
    }

    private static double reapedChildrenCpuSeconds() {
        long[] f = statFields("/proc/self/stat");
        return f == null ? 0 : (f[2] + f[3]) / TICKS;
    }

    /** utime, stime, cutime, cstime from a /proc stat file (fields 14-17), or null. */
    private static long[] statFields(String file) {
        if (!PROC) {
            return null;
        }
        try {
            String s = Files.readString(Path.of(file));
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            // After "pid (comm) ", field 3 (state) is f[0]; utime is field 14 → f[11].
            return new long[]{Long.parseLong(f[11]), Long.parseLong(f[12]), Long.parseLong(f[13]), Long.parseLong(f[14])};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static double jvmCpuSeconds() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime() / 1e9;
        }
        return 0;
    }

    private static double clockTicks() {
        try {
            Process p = new ProcessBuilder("getconf", "CLK_TCK").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Double.parseDouble(out);
        } catch (Exception e) {
            return 100;
        }
    }
}
