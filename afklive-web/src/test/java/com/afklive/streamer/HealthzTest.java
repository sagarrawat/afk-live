package com.afklive.streamer;

import com.afklive.streamer.service.HealthzProbe;
import com.afklive.streamer.repository.WorkerStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public health check for outside uptime monitors: open, cheap, never cached or indexed, never saying why. */
@SpringBootTest
class HealthzTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired WorkerStatusRepository workers;

    MockMvc mvc;

    interface Connect {
        Object get() throws Exception;
    }

    /** A DataSource whose getConnection does {@code connect}; Object methods behave normally. */
    private static DataSource dataSource(Connect connect) {
        Object[] self = new Object[1];
        self[0] = Proxy.newProxyInstance(HealthzTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connect.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test DataSource";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (DataSource) self[0];
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void answersOkToAnyoneWithoutCachingIndexingOrASession() throws Exception {
        MvcResult r = mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(r.getRequest().getSession(false)).isNull();
        mvc.perform(head("/healthz")).andExpect(status().isOk());
    }

    @Test
    void isNeverRateLimited() throws Exception {
        for (int i = 0; i < 400; i++) {
            mvc.perform(get("/healthz").with(req -> {
                req.setRemoteAddr("203.0.113.9");
                return req;
            })).andExpect(status().isOk());
        }
    }

    @Test
    void workersCheckPassesWhenAStreamServerReportedRecently() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        workers.report("healthz-" + UUID.randomUUID().toString().substring(0, 8), now, now, 0, 4, null, null, null);
        mvc.perform(get("/healthz").param("workers", "1")).andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    @Test
    void workersCheckFailsWhenNoStreamServerReportedInTwoMinutes() {
        // Seen from a day ahead, every report in the database is older than two minutes.
        Clock tomorrow = Clock.fixed(Instant.now().plus(Duration.ofDays(1)), ZoneOffset.UTC);
        HealthzProbe probe = new HealthzProbe(dataSource, tomorrow);
        assertThat(probe.healthy(false)).isTrue();
        assertThat(probe.healthy(true)).isFalse();
    }

    @Test
    void aDatabaseErrorAnswers503WithoutDetails() throws Exception {
        DataSource broken = dataSource(() -> {
            throw new SQLException("connection refused to db.internal:5432 as afklive");
        });
        var res = new com.afklive.streamer.endpoint.HealthController(new HealthzProbe(broken, Clock.systemUTC())).healthz(null);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).isEqualTo("unavailable");
        assertThat(res.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex");
    }

    @Test
    void aStuckDatabaseAnswers503InsteadOfHanging() {
        DataSource stuck = dataSource(() -> {
            Thread.sleep(30_000);
            throw new SQLException("timed out");
        });
        HealthzProbe probe = new HealthzProbe(stuck, Clock.systemUTC());
        long started = System.nanoTime();
        assertThat(probe.healthy(false)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(probe.healthy(false)).isFalse(); // reused for a few seconds: no second stuck check
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }
}
