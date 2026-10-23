package com.afklive.streamer;

import com.afklive.streamer.streaming.StreamWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The worker process (profile "worker") must start: it runs every live stream. Regression: it failed to boot without a web context. */
@SpringBootTest(properties = "app.streaming.worker.control-token=test-control-token")
@ActiveProfiles("worker")
class WorkerProfileBootTest extends PostgresIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired WebApplicationContext web;
    @Autowired StreamWorker worker;

    @Test
    void workerProfileStarts() {
        assertThat(context.getBean(StreamWorker.class)).isNotNull();
        assertThat(context.getEnvironment().getProperty("app.streaming.role")).isEqualTo("worker");
        assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    /** OPS-3: the deploy script asks the old worker to drain; only from this machine and with the token. */
    @Test
    void theDeployScriptCanDrainTheWorkerWithTheTokenOnly() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        try {
            mvc.perform(post("/internal/worker/drain")).andExpect(status().isForbidden());
            mvc.perform(post("/internal/worker/drain").header("X-Worker-Token", "wrong")).andExpect(status().isForbidden());
            mvc.perform(post("/internal/worker/drain").header("X-Worker-Token", "test-control-token")
                            .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                    .andExpect(status().isForbidden());
            assertThat(worker.isDraining()).isFalse();

            mvc.perform(post("/internal/worker/drain").header("X-Worker-Token", "test-control-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.draining").value(true))
                    .andExpect(jsonPath("$.workerId").value(worker.workerId()))
                    .andExpect(jsonPath("$.owned").value(0));
            assertThat(worker.isDraining()).isTrue();
            mvc.perform(get("/internal/worker/status").header("X-Worker-Token", "test-control-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.draining").value(true));

            mvc.perform(post("/internal/worker/undrain").header("X-Worker-Token", "test-control-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.draining").value(false));
        } finally {
            worker.stopDraining();
        }
    }
}
