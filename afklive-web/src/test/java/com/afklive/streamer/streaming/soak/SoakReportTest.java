package com.afklive.streamer.streaming.soak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-6: how measurements become pass/fail, without running anything. */
class SoakReportTest {

    @TempDir
    Path dir;

    static SoakHarness.Config cfg(int minutes, boolean copy) {
        return new SoakHarness.Config(Duration.ofMinutes(minutes), copy, 1, 1, 720, 30, Duration.ofMinutes(10),
                Duration.ofSeconds(10), Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(5), Path.of("x"));
    }

    /** Samples every 10 s for {@code minutes}, heap growing {@code heapPerHour} MB/h, everything else flat. */
    static List<ProcessSampler.Sample> series(int minutes, double heapPerHour, int fdBump) {
        List<ProcessSampler.Sample> out = new ArrayList<>();
        for (int t = 0; t <= minutes * 60; t += 10) {
            double heap = 100 + heapPerHour * t / 3600.0;
            int fds = 80 + (t > minutes * 60 * 0.8 ? fdBump : 0);
            out.add(new ProcessSampler.Sample(t, 400, heap + 30, heap, fds, 60, 4, 300, 0.2, 1.1));
        }
        return out;
    }

    static Map<String, Object> stream(int connections, int switchesOk, int switchesFailed) {
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("backwardsVideo", 0L);
        timeline.put("backwardsAudio", 0L);
        timeline.put("maxVideoGapSeconds", 0.034);
        timeline.put("maxAudioGapSeconds", 0.3);
        timeline.put("avDriftMs", 12L);
        timeline.put("mediaVsWallDriftMs", 300L);
        timeline.put("stalls", 0L);
        timeline.put("midStreamSequenceHeaders", 0L);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("connections", connections);
        d.put("reconnectingEvents", 0);
        d.put("notReachingEvents", 0);
        d.put("timelines", List.of(timeline));
        Map<String, Object> speed = new LinkedHashMap<>();
        speed.put("readings", 100L);
        speed.put("avg", 1.0);
        speed.put("min", 0.97);
        speed.put("belowShare", 0.0);
        List<Map<String, Object>> sw = new ArrayList<>();
        for (int i = 0; i < switchesOk + switchesFailed; i++) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("ok", i < switchesOk);
            x.put("seconds", 2.5);
            sw.add(x);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", "s1");
        s.put("endedEarly", false);
        s.put("encoderFailures", 0);
        s.put("speed", Map.of("studio", speed));
        s.put("studioFramesOut", 100_000L);
        s.put("studioFramesDropped", 10L);
        s.put("switches", sw);
        s.put("destinations", List.of(d));
        return s;
    }

    static Map<String, Object> clean() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fds", 81);
        m.put("threads", 62);
        m.put("ffmpegProcesses", 0);
        m.put("workDirLeftovers", 0);
        m.put("cachePins", 0);
        return m;
    }

    static SoakReport report(SoakHarness.Config cfg, List<ProcessSampler.Sample> series, Map<String, Object> stream,
                             Map<String, Object> afterStop, List<Map<String, Object>> samples) {
        return SoakReport.evaluate(cfg, cfg.duration().toSeconds(), series, 80, 60, afterStop, List.of(stream), samples,
                Map.of("s1", Map.of("studio", 0.8)), Map.of("studio", 0.3), 0, Map.of("cpus", 2));
    }

    @Test
    void theSlopeIsMegabytesPerHour() {
        assertThat(SoakReport.slopePerHour(series(120, 12, 0), ProcessSampler.Sample::heapAfterGcMb)).isCloseTo(12.0,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(SoakReport.growth(series(60, 0, 7), ProcessSampler.Sample::fds)).isEqualTo(7);
    }

    @Test
    void aHealthyLongRunPassesAndWritesBothFiles() throws Exception {
        SoakReport r = report(cfg(360, true), series(360, 2, 0), stream(1, 36, 0), clean(), List.of(Map.of("ok", true)));
        assertThat(r.failures()).isEmpty();
        assertThat(r.pass()).isTrue();
        r.write(dir);
        assertThat(Files.readString(dir.resolve("summary.json"))).contains("\"pass\" : true", "\"story\" : \"OPS-6\"");
        assertThat(Files.readString(dir.resolve("report.md"))).contains("PASS", "A/V drift");
    }

    @Test
    void aReconnectAFailedSwitchOrALeakFails() {
        assertThat(report(cfg(360, true), series(360, 2, 0), stream(2, 36, 0), clean(), List.of()).failures())
                .extracting(SoakReport.Check::name).containsExactly("Each destination stayed on one connection");
        assertThat(report(cfg(360, true), series(360, 2, 0), stream(1, 35, 1), clean(), List.of()).failures())
                .extracting(SoakReport.Check::name).containsExactly("Every studio↔copy switch succeeded");
        assertThat(report(cfg(360, false), series(360, 25, 0), stream(1, 0, 0), clean(), List.of()).failures())
                .extracting(SoakReport.Check::name).containsExactly("Heap after full GC: no growth");
        Map<String, Object> leftover = clean();
        leftover.put("cachePins", 2);
        assertThat(report(cfg(10, false), series(10, 0, 0), stream(1, 0, 0), leftover, List.of()).failures())
                .extracting(SoakReport.Check::name).containsExactly("No FFmpeg left running, no work files, no cache pins after stopping");
    }

    @Test
    void aShortRunDoesNotJudgeSlopes() {
        SoakReport r = report(cfg(10, false), series(10, 500, 0), stream(1, 0, 0), clean(), List.of());
        assertThat(r.pass()).isTrue();
        assertThat(r.checks()).filteredOn(c -> c.name().startsWith("Heap")).singleElement()
                .satisfies(c -> assertThat(c.judged()).isFalse());
    }
}
