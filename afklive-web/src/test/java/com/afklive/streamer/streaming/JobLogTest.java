package com.afklive.streamer.streaming;

import com.afklive.streamer.util.Redact;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogTest {

    static final String KEY = "abcd-efgh-ijkl-mnop-qrst";
    static final ZonedDateTime NOON = ZonedDateTime.of(2026, 10, 23, 12, 0, 5, 0, ZoneId.of("UTC"));

    private static JobLog log() {
        return new JobLog(Redact.secretsOf("rtmp://a.rtmp.youtube.com/live2/" + KEY), () -> NOON);
    }

    @Test
    void keepsTheLastLinesOnlyWithTimeAndSource() {
        JobLog log = log();
        for (int i = 0; i < JobLog.MAX_LINES + 50; i++) {
            log.add("encoder", "line " + i);
        }
        assertThat(log.size()).isEqualTo(JobLog.MAX_LINES);
        String[] lines = log.text().split("\n");
        assertThat(lines[0]).isEqualTo("12:00:05 encoder: line 50");
        assertThat(lines[lines.length - 1]).isEqualTo("12:00:05 encoder: line 249");
        log.add("dest 1", "   ");
        assertThat(log.size()).isEqualTo(JobLog.MAX_LINES); // blank lines aren't kept
    }

    @Test
    void neverKeepsAKey() {
        JobLog log = log();
        log.add("dest 1", "[out#0/flv] Error opening output rtmp://a.rtmp.youtube.com/live2/" + KEY + ": Broken pipe");
        log.add("dest 1", "[rtmp] publish " + KEY + " refused");
        assertThat(log.text()).doesNotContain(KEY).contains("dest 1: [rtmp] publish [REDACTED] refused");
    }

    @Test
    void cutsVeryLongLines() {
        JobLog log = log();
        log.add("encoder", "x".repeat(5000));
        assertThat(log.text().length()).isLessThan(JobLog.MAX_LINE_CHARS + 40);
    }

    @Test
    void knowsWhatIsUnsavedAndKeepsEarlierLinesAfterATakeover() {
        JobLog log = log();
        assertThat(log.hasUnsaved()).isFalse();
        log.add("encoder", "first");
        long v = log.version();
        log.add("encoder", "second");
        log.saved(v);
        assertThat(log.hasUnsaved()).isTrue(); // "second" came after that save
        log.saved(log.version());
        assertThat(log.hasUnsaved()).isFalse();

        JobLog next = log();
        next.add("worker", "started on w2");
        next.seed(log.text());
        assertThat(List.of(next.text().split("\n"))).containsExactly(
                "12:00:05 encoder: first", "12:00:05 encoder: second", "12:00:05 worker: started on w2");
    }
}
