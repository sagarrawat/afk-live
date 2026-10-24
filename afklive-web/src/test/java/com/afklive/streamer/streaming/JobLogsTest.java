package com.afklive.streamer.streaming;

import com.afklive.streamer.repository.StreamJobLogStore;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** AD-6: the worker's log keeper saves off the stream's threads, keeps earlier lines, and never piles up. */
class JobLogsTest {

    static final String KEY = "abcd-efgh-ijkl-mnop-qrst";

    /** The database table, in memory; {@code down} makes every call fail. */
    static final class FakeStore extends StreamJobLogStore {
        final Map<Long, String> rows = new HashMap<>();
        int saves;
        boolean down;

        FakeStore() {
            super(null);
        }

        @Override
        public void save(long jobId, String lines, ZonedDateTime at) {
            if (down) {
                throw new IllegalStateException("database unreachable");
            }
            saves++;
            rows.put(jobId, lines);
        }

        @Override
        public Optional<Entry> find(long jobId) {
            if (down) {
                throw new IllegalStateException("database unreachable");
            }
            return Optional.ofNullable(rows.get(jobId)).map(l -> new Entry(l, ZonedDateTime.now()));
        }
    }

    static StreamSpec spec() {
        return new StreamSpec("a@example.com", "v.mp4", null, "1.0", -1, null, false, "original", 360, false, null, null, List.of(new StreamSpec.Destination("YouTube", "rtmp://a.rtmp.youtube.com/live2/" + KEY)));
    }

    @Test
    void savesRedactedLinesAndOnlyWhenThereAreNewOnes() {
        FakeStore store = new FakeStore();
        JobLogs logs = new JobLogs(store);
        logs.line(7, spec(), "dest 1", "[out#0/flv] Error opening output rtmp://a.rtmp.youtube.com/live2/" + KEY + ": Broken pipe");
        logs.line(7, spec(), "dest 1", "[rtmp] publish " + KEY + " refused");
        assertThat(store.saves).isZero(); // nothing on the stream's thread

        logs.saveAll();
        assertThat(store.rows.get(7L)).contains("dest 1: [rtmp] publish [REDACTED] refused").doesNotContain(KEY);
        logs.saveAll();
        assertThat(store.saves).isEqualTo(1);
    }

    @Test
    void aRunTakenOverKeepsTheLinesTheOldWorkerSaved() {
        FakeStore store = new FakeStore();
        store.rows.put(7L, "11:59:00 dest 1: from the old worker");
        JobLogs logs = new JobLogs(store);
        logs.line(7, spec(), "encoder", "from the new worker");
        logs.saveAll();
        String[] lines = store.rows.get(7L).split("\n");
        assertThat(lines[0]).isEqualTo("11:59:00 dest 1: from the old worker");
        assertThat(lines[1]).endsWith("encoder: from the new worker");
    }

    @Test
    void aClosedRunIsSavedThenForgotten() {
        FakeStore store = new FakeStore();
        JobLogs logs = new JobLogs(store);
        logs.line(7, spec(), "encoder", "x");
        logs.close(7, "ended: Stopped by user");
        logs.saveAll();
        assertThat(store.rows.get(7L)).contains("worker: ended: Stopped by user");
        assertThat(logs.open()).isZero();
        logs.close(8, "never logged anything"); // no-op
        assertThat(logs.open()).isZero();
    }

    @Test
    void aDatabaseOutageNeitherThrowsNorLeaks() {
        FakeStore store = new FakeStore();
        store.down = true;
        JobLogs logs = new JobLogs(store);
        logs.line(7, spec(), "encoder", "x");
        logs.saveAll(); // logged at debug, kept for later
        assertThat(logs.open()).isEqualTo(1);
        logs.close(7, "stopped on w1");
        for (int i = 0; i < JobLogs.MAX_TRIES_AFTER_CLOSE; i++) {
            logs.saveAll();
        }
        assertThat(logs.open()).isZero();
    }
}
