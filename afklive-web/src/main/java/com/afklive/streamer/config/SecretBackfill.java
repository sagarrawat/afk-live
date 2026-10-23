package com.afklive.streamer.config;

import com.afklive.streamer.util.SecretBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Encrypts secrets saved before encryption existed, and re-encrypts values under an old key after a
 * rotation. Runs at startup on the web side; each update is conditional on the value it read, so
 * concurrent instances (or writes) are never overwritten.
 */
@Component
@Slf4j
public class SecretBackfill {

    /** table, id column, secret column */
    static final List<String[]> COLUMNS = List.of(
            new String[]{"stream_destinations", "id", "stream_key"},
            new String[]{"streams", "id", "ingest_key"},
            new String[]{"stream_jobs", "id", "spec"},
            new String[]{"stream_jobs", "id", "stream_key"},
            new String[]{"users", "id", "two_factor_secret"},
            new String[]{"users", "id", "two_factor_pending_secret"});

    private final JdbcTemplate jdbc;
    private final String role;

    public SecretBackfill(JdbcTemplate jdbc, @Value("${app.streaming.role:all}") String role) {
        this.jdbc = jdbc;
        this.role = role;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if ("worker".equalsIgnoreCase(role) || !SecretBox.enabled()) {
            return;
        }
        int total = 0;
        for (String[] c : COLUMNS) {
            total += backfill(c[0], c[1], c[2]);
        }
        if (total > 0) {
            log.info("Encrypted {} stored secret(s) with the current key", total);
        }
    }

    int backfill(String table, String idColumn, String column) {
        int done = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + idColumn + " AS id, " + column + " AS v FROM " + table + " WHERE " + column + " IS NOT NULL");
        for (Map<String, Object> row : rows) {
            String v = (String) row.get("v");
            if (SecretBox.isCurrent(v)) {
                continue;
            }
            try {
                String re = SecretBox.encrypt(SecretBox.decrypt(v));
                done += jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE " + idColumn + " = ? AND " + column + " = ?",
                        re, row.get("id"), v);
            } catch (Exception e) {
                log.error("Couldn't re-encrypt {}.{} for id {}: {}", table, column, row.get("id"), e.getMessage());
            }
        }
        return done;
    }
}
