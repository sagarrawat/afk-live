package com.afklive.streamer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-4: what blue/green web deploys rely on, through a real Tomcat (so forwarded headers are
 * applied the way Caddy's requests are in production).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ZeroDowntimeWebTest extends PostgresIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    ApplicationContext context;
    @Autowired
    Environment env;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path, String forwardedFor) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (forwardedFor != null) {
            b.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessAnswersOnLocalhostWithoutCreatingASession() throws Exception {
        HttpResponse<String> r = get("/actuator/health/readiness", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"UP\"");
        // Caddy asks every second: no session rows for health checks.
        assertThat(r.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION"));

        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isNotEqualTo(403);
    }

    @Test
    void healthIsRefusedToRequestsThatCameThroughTheProxy() throws Exception {
        // Caddy connects from 127.0.0.1 but sends the visitor's address: that's not local.
        HttpResponse<String> r = get("/actuator/health/readiness", "203.0.113.7");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).doesNotContain("UP");
        assertThat(get("/actuator/health", "198.51.100.20").statusCode()).isEqualTo(403);
    }

    @Test
    void nothingElseFromTheActuatorIsExposed() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/heapdump", "/actuator/shutdown"}) {
            HttpResponse<String> r = get(path, null);
            assertThat(r.statusCode()).as(path).isIn(403, 404);
            assertThat(get(path, "203.0.113.7").statusCode()).as(path + " via proxy").isIn(403, 404);
        }
    }

    @Test
    void readinessReportsNotReadyWhileRefusingTraffic() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            HttpResponse<String> r = get("/actuator/health/readiness", null);
            assertThat(r.statusCode()).isEqualTo(503);
            // The app itself still answers while it drains.
            assertThat(get("/login", null).statusCode()).isEqualTo(200);
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void stoppingFinishesRequestsInFlight() {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("60s");
        // The bean that waits for active requests when the web server stops.
        assertThat(context.containsBean("webServerGracefulShutdown")).isTrue();
    }

    @Test
    void signInsAreSharedByEveryWebInstance() throws Exception {
        assertThat(context.getBean(SessionRepository.class)).isInstanceOf(JdbcIndexedSessionRepository.class);

        String email = "bluegreen-" + System.nanoTime() + "@example.com";
        String session = signIn(email);

        // The sign-in is a row in PostgreSQL, readable by any web instance.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, email))
                .isEqualTo(1);

        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/me"))
                .header("Cookie", "SESSION=" + session).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(email);
    }

    @Test
    void aSessionTheNewVersionCantReadSignsThatVisitorOutInsteadOfFailing() throws Exception {
        String email = "bluegreen-old-" + System.nanoTime() + "@example.com";
        String session = signIn(email);
        // As if written by a release whose classes serialize differently.
        jdbc.update("UPDATE spring_session_attributes SET attribute_bytes = ? WHERE session_primary_id = "
                        + "(SELECT primary_id FROM spring_session WHERE principal_name = ?)",
                new byte[]{(byte) 0xAC, (byte) 0xED, 0, 5, 's', 'r', 0, 9, 'g', 'o', 'n', 'e'}, email);

        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/me"))
                .header("Cookie", "SESSION=" + session).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).as(me.body()).isNotEqualTo(500).isNotEqualTo(200);
        assertThat(me.headers().firstValue("Location").orElse("")).endsWith("/login");
    }

    /** Signs in with a password through the real login form; returns the SESSION cookie. */
    private String signIn(String email) throws Exception {
        User u = new User(email);
        u.setPassword(passwordEncoder.encode("a-long-password-1"));
        u.setPlanType(PlanType.FREE);
        users.save(u);

        // CSRF tokens live in a cookie, so a form opened on one instance posts fine to the other.
        HttpResponse<String> form = get("/login", null);
        String xsrf = cookie(form, "XSRF-TOKEN");
        assertThat(xsrf).isNotBlank();

        HttpResponse<String> signIn = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "XSRF-TOKEN=" + xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                        + "&password=a-long-password-1&_csrf=" + URLEncoder.encode(xsrf, StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(signIn.statusCode()).isEqualTo(302);
        assertThat(signIn.headers().firstValue("Location").orElse("")).doesNotContain("error");
        String session = cookie(signIn, "SESSION");
        assertThat(session).as("Spring Session's cookie, not Tomcat's in-memory JSESSIONID").isNotBlank();
        return session;
    }

    private static String cookie(HttpResponse<?> r, String name) {
        return r.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst().orElse(null);
    }
}
