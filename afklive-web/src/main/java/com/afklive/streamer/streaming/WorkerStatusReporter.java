package com.afklive.streamer.streaming;

import com.afklive.streamer.repository.WorkerStatusRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AD-2 / RE-7: every few seconds a worker writes its own row in {@code stream_workers}: when it last
 * reported, how many streams it runs and can run, and the machine's CPU and memory (FFmpeg runs as
 * child processes, so the machine's numbers are what matter, not the JVM's). The admin console shows
 * load per worker, idle ones included, and the status page counts workers that reported recently.
 * Separate from the per-stream heartbeat, so a failing report never affects a stream.
 */
@Component
@ConditionalOnExpression("'${app.streaming.role:all}' != 'web'")
@Slf4j
public class WorkerStatusReporter {

    static final long REPORT_SECONDS = 10;

    private final StreamWorker worker;
    private final WorkerStatusRepository repository;
    private final boolean autoStart;
    private final ZonedDateTime startedAt = now();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "worker-status");
        t.setDaemon(true);
        return t;
    });

    public WorkerStatusReporter(StreamWorker worker, WorkerStatusRepository repository,
                                @Value("${app.streaming.worker.auto-start:true}") boolean autoStart) {
        this.worker = worker;
        this.repository = repository;
        this.autoStart = autoStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!autoStart) {
            return;
        }
        timer.scheduleWithFixedDelay(() -> {
            try {
                report();
            } catch (Exception e) {
                log.debug("Worker status report failed: {}", e.getMessage());
            }
        }, 0, REPORT_SECONDS, TimeUnit.SECONDS);
    }

    /** One report; public so tests can call it. */
    public void report() {
        Machine m = machine();
        repository.report(worker.workerId(), startedAt, now(), worker.runningCount(), worker.capacity(),
                m.cpuPercent(), m.memoryUsedBytes(), m.memoryTotalBytes(), Runtime.getRuntime().availableProcessors());
    }

    /** A clean shutdown takes the worker off the list straight away (a crash ages out instead). */
    @PreDestroy
    public void stop() {
        timer.shutdownNow();
        if (!autoStart) {
            return;
        }
        try {
            repository.remove(worker.workerId());
        } catch (Exception e) {
            log.debug("Couldn't remove worker status on shutdown: {}", e.getMessage());
        }
    }

    record Machine(Double cpuPercent, Long memoryUsedBytes, Long memoryTotalBytes) {
    }

    /** CPU and memory of the machine (or container, where the JDK reads cgroup limits); null when unknown. */
    static Machine machine() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getCpuLoad();
            Double cpu = load < 0 || Double.isNaN(load) ? null : Math.round(load * 1000) / 10.0;
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            // On a whole machine, "free" leaves out the page cache the kernel gives back on demand;
            // MemAvailable counts it, so memory doesn't look full just because files were read.
            long[] meminfo = procMeminfo();
            if (meminfo != null && meminfo[0] == total) {
                free = meminfo[1];
            }
            return new Machine(cpu, total > 0 ? Math.max(0, total - free) : null, total > 0 ? total : null);
        }
        return new Machine(null, null, null);
    }

    /** {MemTotal, MemAvailable} in bytes from /proc/meminfo, or null where there's no such file. */
    static long[] procMeminfo() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("/proc/meminfo");
            if (!java.nio.file.Files.isReadable(p)) {
                return null;
            }
            return parseMeminfo(java.nio.file.Files.readAllLines(p));
        } catch (Exception e) {
            return null;
        }
    }

    static long[] parseMeminfo(java.util.List<String> lines) {
        long total = -1;
        long available = -1;
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            if (parts[0].equals("MemTotal:")) {
                total = Long.parseLong(parts[1]) * 1024;
            } else if (parts[0].equals("MemAvailable:")) {
                available = Long.parseLong(parts[1]) * 1024;
            }
        }
        return total > 0 && available >= 0 ? new long[]{total, available} : null;
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
    }
}
