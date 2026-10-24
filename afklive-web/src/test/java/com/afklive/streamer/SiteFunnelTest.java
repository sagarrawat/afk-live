package com.afklive.streamer;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamEvent;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.StreamEventRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.SiteFunnelService;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The homepage funnel: anonymous counters from the site, sign-up and first live from existing data, read by admins. */
@SpringBootTest
class SiteFunnelTest extends PostgresIntegrationTest {

    static final String BROWSER = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamEventRepository events;
    @Autowired StreamService streamService;
    @Autowired SiteFunnelService funnel;
    @Autowired com.afklive.streamer.service.FirstLiveService firstLive;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM site_funnel_days");
    }

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("funnel-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private long today(String step) {
        Long n = jdbc.query("SELECT count FROM site_funnel_days WHERE day = ? AND step = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, LocalDate.now(ZoneOffset.UTC), step);
        return n == null ? 0 : n;
    }

    private MvcResult beacon(String body, String userAgent) throws Exception {
        var req = post("/api/public/funnel").contentType(MediaType.TEXT_PLAIN).content(body);
        if (userAgent != null) {
            req = req.header(HttpHeaders.USER_AGENT, userAgent);
        }
        return mvc.perform(req).andExpect(status().isNoContent()).andReturn();
    }

    @Test
    void aBrowserBeaconCountsWithoutSigningInOrCsrfAndSetsNoCookie() throws Exception {
        MvcResult r = beacon("view", BROWSER);
        beacon("start", BROWSER);
        beacon("view", BROWSER);
        assertThat(today("view")).isEqualTo(2);
        assertThat(today("start")).isEqualTo(1);
        assertThat(r.getResponse().getCookies()).extracting(jakarta.servlet.http.Cookie::getName).isEmpty();
        assertThat(r.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void botsScriptsAndUnknownStepsAreNotCounted() throws Exception {
        beacon("view", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        beacon("view", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/120.0 Safari/537.36");
        beacon("view", "curl/8.4.0");
        beacon("view", null);
        beacon("signup", BROWSER);
        beacon("'; DROP TABLE users; --", BROWSER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_funnel_days", Long.class)).isZero();
        assertThat(SiteFunnelService.looksHuman(BROWSER)).isTrue();
        assertThat(SiteFunnelService.looksHuman("facebookexternalhit/1.1")).isFalse();
    }

    @Test
    void onlyAdminsReadTheDailyCounts() throws Exception {
        mvc.perform(get("/api/admin/funnel")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/admin/funnel").with(user("someone@example.com"))).andExpect(status().isForbidden());
    }

    @Test
    void theDailyCountsJoinTheBeaconWithSignUpsGoLivesAndFirstReached() throws Exception {
        beacon("view", BROWSER);
        beacon("view", BROWSER);
        beacon("start", BROWSER);
        // A new account gets created_at from the column default (the previous release doesn't know the column).
        User u = new User("funnel-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        u.setPlanType(PlanType.FREE);
        u = users.save(u);
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, u.getId())).isTrue();
        StreamJob job = new StreamJob(u, "key", "video.mp4", null, null, true, 0, "t", "d", "private",
                ZonedDateTime.now(ZoneId.of("UTC")), "YouTube");
        job = jobs.save(job);
        events.save(new StreamEvent(job.getId(), ZonedDateTime.now(ZoneId.of("UTC")), StreamEvent.Kinds.REACHED, 0, "YouTube", null));
        firstLive.recordFirstLives(); // ON-8: the step reads "first live confirmed", recorded by the web's sweep

        mvc.perform(get("/api/admin/funnel").param("days", "7").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].day").value(LocalDate.now(ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$[0].views").value(2))
                .andExpect(jsonPath("$[0].startClicks").value(1));
        SiteFunnelService.Day d = funnel.daily(1).get(0);
        assertThat(d.signUps()).isGreaterThanOrEqualTo(1);
        assertThat(d.firstGoLives()).isGreaterThanOrEqualTo(1);
        assertThat(d.firstReached()).isGreaterThanOrEqualTo(1);
    }
}
