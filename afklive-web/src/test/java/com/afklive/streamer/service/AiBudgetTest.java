package com.afklive.streamer.service;

import com.afklive.streamer.PostgresIntegrationTest;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI-1 FinOps: tokens priced and counted in the database, the monthly cap across instances, alerts at 80% and 100%. */
@SpringBootTest
class AiBudgetTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notifications;
    @Autowired UserRepository users;
    @Autowired WebApplicationContext context;

    String tag;
    /** A month of its own (a far-future year), so other tests' spend doesn't count here. */
    ZonedDateTime now;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        now = ZonedDateTime.of(2100 + (int) (Math.abs((long) tag.hashCode()) % 7000), 5, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    private AiBudget budget(String usd, ZonedDateTime at) {
        AiBudget b = new AiBudget(jdbc, notifications, users, usd, "", "");
        b.setClock(Clock.fixed(at.toInstant(), ZoneOffset.UTC));
        return b;
    }

    private User account(String prefix, String role) {
        User u = new User(prefix + "-" + tag + "@example.com");
        u.setPlanType(PlanType.FREE);
        if (role != null) {
            u.setRole(role);
        }
        return users.save(u);
    }

    private long alerts(User u, String month, int threshold) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND dedupe_key = ?", Long.class,
                u.getId(), "ai-budget:" + month + ":" + threshold);
        return n == null ? 0 : n;
    }

    @Test
    void settingsDefaultToTenDollarsAndHaikuPrices() {
        AiBudget b = budget("", now);
        assertThat(b.budgetUsd()).isEqualTo(10);
        assertThat(b.cost(1_000_000, 0)).isCloseTo(0.10, within(1e-9));
        assertThat(b.cost(0, 1_000_000)).isCloseTo(0.50, within(1e-9));
        assertThat(b.cost(1200, 300)).isCloseTo(0.00027, within(1e-9));
        assertThat(AiBudget.number("X", "abc", 10)).isEqualTo(10);
        assertThat(AiBudget.number("X", "-1", 10)).isEqualTo(10);
        assertThat(AiBudget.number("X", " 25 ", 10)).isEqualTo(25);
        assertThat(AiBudget.number("X", "0", 10)).isZero();
    }

    @Test
    void spendIsCountedPerMonthAndAiStopsAtTheCapOnEveryInstance() {
        User admin = account("ai-admin", "ROLE_ADMIN");
        User creator = account("ai-creator", null);
        AiBudget web1 = budget("1", now);
        AiBudget web2 = budget("1", now);
        String month = web1.month();

        web1.record("anthropic", "claude-haiku-5-5", 1_000_000, 0); // $0.10
        assertThat(web1.spentUsd()).isCloseTo(0.10, within(1e-6));
        assertThat(web2.allows()).isTrue();
        assertThat(alerts(admin, month, 80)).isZero();

        web2.record("anthropic", "claude-haiku-5-5", 0, 1_400_000); // +$0.70 = $0.80: the 80% alert, once
        assertThat(alerts(admin, month, 80)).isEqualTo(1);
        assertThat(alerts(creator, month, 80)).as("admins only").isZero();
        assertThat(web1.allows()).isTrue();
        web1.record("anthropic", "claude-haiku-5-5", 10, 10);
        assertThat(alerts(admin, month, 80)).as("not again").isEqualTo(1);

        web1.record("gemini", "gemini-flash-lite-latest", 0, 400_000); // +$0.20: $1.00, the cap
        assertThat(alerts(admin, month, 100)).isEqualTo(1);
        assertThat(web1.allows()).isFalse();
        assertThat(web2.allows()).as("counted in the database, so the other instance stops too").isFalse();
        assertThat(web2.alertIfCrossed(month, web2.spentUsd())).isNull();

        AiBudget.Report r = web1.report("anthropic", "claude-haiku-5-5");
        assertThat(r.month()).isEqualTo(month);
        assertThat(r.stopped()).isTrue();
        assertThat(r.percent()).isCloseTo(100, within(0.01));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().getFirst().provider()).isEqualTo("anthropic");
        assertThat(r.rows().getFirst().calls()).isEqualTo(3);
        assertThat(r.rows().getFirst().inputTokens()).isEqualTo(1_000_010);

        // The next UTC month starts from zero.
        AiBudget next = budget("1", now.plusMonths(1));
        assertThat(next.allows()).isTrue();
        assertThat(next.spentUsd()).isZero();
    }

    @Test
    void jumpingStraightPastTheCapSendsOnlyTheHundredPercentAlert() {
        User admin = account("ai-admin2", "ROLE_ADMIN");
        AiBudget b = budget("1", now);
        b.record("anthropic", "m", 0, 3_000_000); // $1.50
        assertThat(alerts(admin, b.month(), 80)).isZero();
        assertThat(alerts(admin, b.month(), 100)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_budget_alerts WHERE month = ?", Integer.class, b.month())).isEqualTo(2);
    }

    @Test
    void aZeroCapMeansNoAiCallsAtAll() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider fake = new AiProvider() {
            public String name() {
                return "anthropic";
            }

            public String model() {
                return "m";
            }

            public Reply complete(String prompt, boolean json) {
                calls.incrementAndGet();
                return new Reply("{}", 10, 10);
            }
        };
        AiService off = new AiService(fake, budget("0", now));
        assertThat(off.isAvailable()).isFalse();
        assertThat(off.completeJson("p")).isNull();
        assertThat(calls).hasValue(0);

        AiBudget b = budget("1", now);
        AiService on = new AiService(fake, b);
        assertThat(on.completeJson("p")).isEqualTo("{}");
        assertThat(calls).hasValue(1);
        assertThat(b.report("anthropic", "m").rows().getFirst().calls()).isEqualTo(1);
    }

    @Test
    void onlyAdminsSeeTheAiSpend() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/admin/ai-spend").with(user("someone@example.com").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ai-spend").with(user("ops@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetUsd").value(10.0))
                .andExpect(jsonPath("$.inputPerMillion").value(0.10))
                .andExpect(jsonPath("$.outputPerMillion").value(0.50))
                .andExpect(jsonPath("$.provider").value(org.hamcrest.Matchers.nullValue()));
    }
}
