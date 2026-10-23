package com.afklive.streamer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AD-1 / AD-3: the last FFmpeg lines of each run ({@code stream_job_logs}), written by the worker running
 * it (already without stream keys) and read by the admin console. One row per run, replaced whole.
 */
@Repository
public class StreamJobLogStore {

    public record Entry(String lines, ZonedDateTime updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public StreamJobLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long jobId, String lines, ZonedDateTime at) {
        jdbc.update("INSERT INTO stream_job_logs (job_id, lines, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (job_id) DO UPDATE SET lines = EXCLUDED.lines, updated_at = EXCLUDED.updated_at",
                jobId, lines, Timestamp.from(at.toInstant()));
    }

    public Optional<Entry> find(long jobId) {
        List<Entry> rows = jdbc.query("SELECT lines, updated_at FROM stream_job_logs WHERE job_id = ?",
                (rs, n) -> new Entry(rs.getString(1), rs.getTimestamp(2).toInstant().atZone(ZoneId.of("UTC"))), jobId);
        return rows.stream().findFirst();
    }

    public int deleteOlderThan(ZonedDateTime before) {
        return jdbc.update("DELETE FROM stream_job_logs WHERE updated_at < ?", Timestamp.from(before.toInstant()));
    }
}
