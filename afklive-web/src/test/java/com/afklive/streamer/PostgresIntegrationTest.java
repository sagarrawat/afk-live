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
        registry.add("app.encryption-key", () -> "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdCE=");
        registry.add("app.ingest.public-host", () -> "ingest.test");
        registry.add("app.ingest.read-pass", () -> "test-read-pass");
        registry.add("app.ingest.auth-secret", () -> "test-auth-secret");
    }
}
