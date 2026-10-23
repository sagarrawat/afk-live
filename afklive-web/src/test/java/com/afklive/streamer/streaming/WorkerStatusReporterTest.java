package com.afklive.streamer.streaming;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AD-2: how a worker measures its machine. */
class WorkerStatusReporterTest {

    @Test
    void memoryUsedLeavesOutReclaimableCache() {
        long[] m = WorkerStatusReporter.parseMeminfo(List.of(
                "MemTotal:       16384000 kB", "MemFree:          512000 kB", "MemAvailable:    8192000 kB", "Cached: 7000000 kB"));
        assertThat(m).containsExactly(16384000L * 1024, 8192000L * 1024);
    }

    @Test
    void unreadableMeminfoMeansUnknown() {
        assertThat(WorkerStatusReporter.parseMeminfo(List.of("MemTotal: 100 kB"))).isNull();
        assertThat(WorkerStatusReporter.parseMeminfo(List.of())).isNull();
    }

    @Test
    void readsThisMachine() {
        WorkerStatusReporter.Machine m = WorkerStatusReporter.machine();
        assertThat(m.memoryTotalBytes()).isPositive();
        assertThat(m.memoryUsedBytes()).isBetween(0L, m.memoryTotalBytes());
        if (m.cpuPercent() != null) {
            assertThat(m.cpuPercent()).isBetween(0.0, 100.0);
        }
    }
}
