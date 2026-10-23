package com.afklive.streamer;

import com.afklive.streamer.streaming.StreamWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The worker process (profile "worker") must start: it runs every live stream. Regression: it failed to boot without a web context. */
@SpringBootTest
@ActiveProfiles("worker")
class WorkerProfileBootTest extends PostgresIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void workerProfileStarts() {
        assertThat(context.getBean(StreamWorker.class)).isNotNull();
        assertThat(context.getEnvironment().getProperty("app.streaming.role")).isEqualTo("worker");
        assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
