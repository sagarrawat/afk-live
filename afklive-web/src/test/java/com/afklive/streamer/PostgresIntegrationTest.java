package com.afklive.streamer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Base class for tests that need the real database schema. Starts one embedded PostgreSQL
 * for the whole test JVM (no Docker required) and points Spring at it.
 */
public abstract class PostgresIntegrationTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                POSTGRES.close();
            } catch (IOException ignored) {
                // JVM is exiting
            }
        }));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // Tests drive the stream worker explicitly (StreamWorker.tick) instead of a background loop.
        registry.add("app.streaming.worker.auto-start", () -> "false");
        // Test machines are busy; the CPU check has its own test.
        registry.add("app.streaming.worker.max-cpu-to-claim", () -> "1.01");
        registry.add("app.encryption-key", () -> "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdCE=");
        registry.add("app.ingest.public-host", () -> "ingest.test");
        registry.add("app.ingest.read-pass", () -> "test-read-pass");
        registry.add("app.ingest.auth-secret", () -> "test-auth-secret");
        // Tests process library files explicitly (MediaProcessor.process / sweep).
        registry.add("app.media.auto-process", () -> "false");
        registry.add("app.admin.worker-samples-auto", () -> "false"); // AdminConsoleTest drives WorkerSampler itself
        registry.add("app.check.weekly-auto", () -> "false"); // GoLiveCheckServiceTest drives the weekly check itself
        registry.add("app.first-live.auto", () -> "false"); // FirstLiveTest drives the sweep itself
    }
}
