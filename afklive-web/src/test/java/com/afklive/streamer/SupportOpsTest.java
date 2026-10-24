package com.afklive.streamer;

import com.afklive.streamer.model.AdminAction;
import com.afklive.streamer.model.DestinationTest;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.AdminActionRepository;
import com.afklive.streamer.repository.DestinationTestRepository;
import com.afklive.streamer.repository.EmailLogRepository;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamJobLogStore;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.SupportService;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StreamStates;
import com.afklive.streamer.streaming.StudioSpec;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OPS-1 (email works or we say so), OPS-2 (friendly errors), AD-6 (a run as support sees it).
 * Tests run without ZeptoMail settings, as production does today.
 */
@SpringBootTest
class SupportOpsTest extends PostgresIntegrationTest {

    static final String ADMIN = "support-admin@example.com";
    static final String KEY = "abcd-efgh-ijkl-mnop-qrst";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired EmailService emailService;
    @Autowired EmailLogRepository emailLog;
    @Autowired AdminActionRepository audit;
    @Autowired SupportService support;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamJobLogStore jobLogs;
    @Autowired MediaAssetRepository media;
    @Autowired DestinationTestRepository destinationTests;

    MockMvc mvc;
    User customer;
    User adminUser;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = new User("ops-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        customer.setPlanType(PlanType.FREE);
        customer = users.save(customer);
        adminUser = users.findByEmail(ADMIN).orElseGet(() -> {
            User a = new User(ADMIN);
            a.setRole("ROLE_ADMIN");
            return users.save(a);
        });
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN).roles("ADMIN");
    }

    private static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.replace('\'', '"'));
    }

    // ------------------------------------------------------------------ OPS-1

    @Test
    void withEmailOffASignUpCanSignInAndTheResetPageSaysSo() throws Exception {
        assertThat(emailService.isConfigured()).isFalse();
        String email = "signup-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/register").with(csrf()).param("email", email).param("password", "Passw0rd!x").param("name", "New"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your account is ready")));
        User created = users.findByEmail(email).orElseThrow();
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getVerificationToken()).isNull();

        mvc.perform(get("/forgot-password"))
                .andExpect(content().string(containsString("support@afklive.in")))
                .andExpect(content().string(not(containsString("Send reset link"))));
        mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("We can&#39;t send email right now")))
                .andExpect(content().string(not(containsString("has been sent"))));
        assertThat(users.findByEmail(email).orElseThrow().getResetToken()).isNull();
    }

    @Test
    void waitingSignUpsAreVerifiedButAccountsAnAdminDisabledAreNot() {
        User waiting = new User("waiting-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        waiting.setPassword("{noop}x");
        waiting.setEnabled(false);
        waiting.setVerificationToken(UUID.randomUUID().toString());
        waiting = users.save(waiting);
        User banned = new User("banned-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        banned.setPassword("{noop}x");
        banned.setEnabled(false);
        banned.setVerificationToken(UUID.randomUUID().toString());
        banned = users.save(banned);
        AdminAction a = new AdminAction();
        a.setAdminEmail(ADMIN);
        a.setAction("USER_DISABLE");
        a.setTargetType("user");
        a.setTargetId(String.valueOf(banned.getId()));
        a.setDetail("abuse");
        a.setCreatedAt(ZonedDateTime.now(ZoneId.of("UTC")));
        audit.save(a);

        assertThat(support.verifyWaitingSignUps()).isGreaterThanOrEqualTo(1);

        assertThat(users.findById(waiting.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(users.findById(banned.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void everySendIsRecordedAndShownInTheUserLookupWithoutTheBody() throws Exception {
        emailService.sendAlert(customer.getEmail(), "AFK Live: Stream down", "Stream down", "Your stream <b>x</b> is down", "/studio");

        mvc.perform(get("/api/admin/users/" + customer.getId() + "/support").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].kind").value("ALERT"))
                .andExpect(jsonPath("$.emails[0].ok").value(false))
                .andExpect(jsonPath("$.emails[0].error").value(containsString("isn't set up")))
                .andExpect(content().string(not(containsString("is down"))))
                .andExpect(jsonPath("$.awaitingVerification").value(false));
        mvc.perform(get("/api/admin/users/" + customer.getId() + "/support").with(user(customer.getEmail()))).andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanGiveSomeoneLockedOutAResetLinkThatUsesThePublicAddress() throws Exception {
        String email = "locked-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User locked = new User(email);
        locked.setPassword("{noop}old");
        locked = users.save(locked);
        String url = "/api/admin/users/" + locked.getId() + "/reset-link";

        mvc.perform(postJson(url, "{'reason':'x'}").with(user(customer.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(postJson(url, "{}").with(admin())).andExpect(status().isBadRequest());
        String body = mvc.perform(postJson(url, "{'reason':'Ticket 40: checked their payment id'}").with(admin()).header("Host", "evil.example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String link = com.jayway.jsonpath.JsonPath.parse(body).read("$.link", String.class);
        String token = users.findById(locked.getId()).orElseThrow().getResetToken();
        assertThat(token).isNotBlank();
        assertThat(link).doesNotContain("evil.example.com").endsWith("/reset-password?token=" + token);
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("user", String.valueOf(locked.getId())))
                .anyMatch(x -> x.getAction().equals("PASSWORD_RESET_LINK") && x.getDetail().contains("Ticket 40"));

        User google = new User("google-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        google = users.save(google);
        mvc.perform(postJson("/api/admin/users/" + google.getId() + "/reset-link", "{'reason':'Ticket 41'}").with(admin()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void adminsSeeEmailIsOffAndCanSendAnAuditedTestEmail() throws Exception {
        mvc.perform(get("/api/admin/email").with(user(customer.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(postJson("/api/admin/email/test", "{'reason':'checking'}").with(user(customer.getEmail()))).andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/email").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(postJson("/api/admin/email/test", "{}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/email/test", "{'reason':'After setting ZeptoMail'}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(containsString("ZEPTOMAIL_URL")));

        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("setting", "email"))
                .anyMatch(x -> x.getAction().equals("EMAIL_TEST") && x.getAdminEmail().equals(ADMIN) && x.getDetail().contains("After setting ZeptoMail"));
        assertThat(emailLog.findTop50ByUserIdOrderByCreatedAtDesc(adminUser.getId()))
                .anyMatch(e -> e.getKind().equals("TEST") && !e.isOk());
        mvc.perform(get("/api/admin/email").with(admin())).andExpect(jsonPath("$.failed24h").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ------------------------------------------------------------------ OPS-2

    @Test
    void anExpiredSignInFormOpensAgainSayingSo() throws Exception {
        mvc.perform(post("/login").param("username", "x@example.com").param("password", "y")).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(post("/register").param("email", "x@example.com")).andExpect(redirectedUrl("/register?expired"));
        mvc.perform(post("/forgot-password").param("email", "x@example.com")).andExpect(redirectedUrl("/forgot-password?expired"));
        mvc.perform(post("/reset-password").param("token", "ab c&d").param("password", "x"))
                .andExpect(redirectedUrl("/reset-password?token=ab%20c%26d&expired"));
        mvc.perform(post("/login").with(csrf().useInvalidToken()).param("username", "x@example.com").param("password", "y"))
                .andExpect(redirectedUrl("/login?expired"));

        mvc.perform(get("/login").param("expired", "")).andExpect(content().string(containsString("This page expired. Please try again.")));
        mvc.perform(get("/reset-password").param("token", "abc").param("expired", ""))
                .andExpect(content().string(containsString("This page expired. Please try again.")));
    }

    @Test
    void anExpiredTokenOnTheApiIsA403TheAppCanRecognise() throws Exception {
        mvc.perform(post("/api/admin/email/test").with(admin()).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf"))
                .andExpect(jsonPath("$.message").value("This page expired. Please try again."));
        // ... and it can fetch a fresh token without being signed in.
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent());
        // Other refusals stay ordinary 403s.
        mvc.perform(get("/api/admin/email").with(user(customer.getEmail()))).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("csrf"))));
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        MockMvc withFilters = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(com.afklive.streamer.filter.RequestIdFilter.class)).apply(springSecurity()).build();
        withFilters.perform(get("/login")).andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[a-z0-9]{8}")));
    }

    @Test
    void theErrorPageSaysOurSideOnlyForServerErrorsAndShowsTheCode() throws Exception {
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/studio/thing")
                        .requestAttr(com.afklive.streamer.filter.RequestIdFilter.ATTRIBUTE, "abcd2345"))
                .andExpect(content().string(containsString("Something went wrong on our side.")))
                .andExpect(content().string(containsString("abcd2345")));
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                .andExpect(content().string(not(containsString("our side"))))
                .andExpect(content().string(containsString("You can&#39;t open this page.")));
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403)
                        .requestAttr(com.afklive.streamer.security.FriendlyAccessDeniedHandler.EXPIRED_ATTRIBUTE, true))
                .andExpect(content().string(containsString("This page expired.")));
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 405))
                .andExpect(content().string(not(containsString("our side"))));
        mvc.perform(get("/error").accept(MediaType.APPLICATION_JSON).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(com.afklive.streamer.filter.RequestIdFilter.ATTRIBUTE, "abcd2345"))
                .andExpect(jsonPath("$.requestId").value("abcd2345"));
    }

    // ------------------------------------------------------------------ AD-6

    private StreamJob run(boolean live, List<StreamSpec.Destination> destinations, StudioSpec studio) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MICROS);
        StreamSpec spec = StreamSpec.studio(customer.getEmail(), null, studio, destinations);
        StreamJob job = new StreamJob(customer, "k", "video", null, null, live, 0, "My stream", null, null, now, "YouTube", false);
        job.setStatus(live ? StreamStates.LIVE : StreamStates.ENDED);
        job.setDesiredState(live ? StreamStates.RUN : StreamStates.STOP);
        job.setSpec(spec.toJson());
        job.setRestarts(2);
        if (!live) {
            job.setEndTime(now);
            job.setEndReason("Stopped by user");
        }
        for (int i = 0; i < destinations.size(); i++) {
            StreamJobDestination d = new StreamJobDestination(job, i, destinations.get(i).name());
            d.setRestarts(i == 0 ? 3 : 0);
            d.setLastError(i == 0 ? "Error opening output rtmp://a.rtmp.youtube.com/live2/" + KEY + ": Broken pipe" : null);
            job.getDestinations().add(d);
        }
        return jobs.save(job);
    }

    private StudioSpec playlist(MediaAsset... files) {
        List<StudioSpec.Media> videos = java.util.Arrays.stream(files)
                .map(m -> new StudioSpec.Media(String.valueOf(m.getId()), m.getS3Key(), m.getTitle(), 60, true)).toList();
        return new StudioSpec(1080, false, 30, "PLAYLIST", videos, "IN_ORDER", 0, List.of(), "IN_ORDER", "KEEP", 1, 0.6,
                List.of(), List.of(), "LIVE", "UTC", null, null, false);
    }

    @Test
    void aRunShowsSourceOutputDestinationsAndLogButNeverAKey() throws Exception {
        MediaAsset clip = new MediaAsset(customer, "lofi.mp4", "key/" + UUID.randomUUID() + ".mp4", 1L, MediaAsset.ContentType.VIDEO);
        clip.setWidth(1080);
        clip.setHeight(1920);
        clip.setFps(30.0);
        clip.setVideoCodec("h264");
        clip.setHasAudio(true);
        clip = media.save(clip);
        StreamJob job = run(false, List.of(
                new StreamSpec.Destination("YouTube", "rtmp://a.rtmp.youtube.com/live2/" + KEY),
                new StreamSpec.Destination("Pasted wrong", "rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2")), playlist(clip));
        jobLogs.save(job.getId(), "12:00:00 dest 1: Broken pipe", ZonedDateTime.now(ZoneId.of("UTC")));

        String body = mvc.perform(get("/api/admin/jobs/" + job.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEmail").value(customer.getEmail()))
                .andExpect(jsonPath("$.source.kind").value("PLAYLIST"))
                .andExpect(jsonPath("$.source.media[0].width").value(1080))
                .andExpect(jsonPath("$.source.media[0].height").value(1920))
                .andExpect(jsonPath("$.source.media[0].shape").value("Vertical"))
                .andExpect(jsonPath("$.output.width").value(1920))
                .andExpect(jsonPath("$.output.height").value(1080))
                .andExpect(jsonPath("$.output.videoKbps").value(6000))
                .andExpect(jsonPath("$.destinations[0].server").value("rtmp://a.rtmp.youtube.com/live2"))
                .andExpect(jsonPath("$.destinations[0].keyMasked").value("••••qrst"))
                .andExpect(jsonPath("$.destinations[0].checkLevel").value("OK"))
                .andExpect(jsonPath("$.destinations[0].restarts").value(3))
                .andExpect(jsonPath("$.destinations[0].lastError").value(containsString("Broken pipe")))
                .andExpect(jsonPath("$.destinations[1].checkLevel").value("BAD"))
                .andExpect(jsonPath("$.destinations[1].check").value(containsString("second server address")))
                .andExpect(jsonPath("$.log").value("12:00:00 dest 1: Broken pipe"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(KEY).doesNotContain("efgh-ijkl-mnop");

        mvc.perform(get("/api/admin/jobs/" + job.getId()).with(user(customer.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/jobs/999999999").with(admin())).andExpect(status().isNotFound());
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * RE-8 status shows when the worker reports it (reach, reach_reason from feature/truth-of-live), and the view
     * works without those columns (main today). The positive case reads a table shaped like that branch's, so
     * this test never changes the shared schema.
     */
    @Test
    void aRunShowsWhetherThePlatformIsReceivingWhenTheWorkerReportsIt() throws Exception {
        StreamJob job = run(true, List.of(new StreamSpec.Destination("YouTube", "rtmp://a.rtmp.youtube.com/live2/" + KEY)), playlist());
        try {
            mvc.perform(get("/api/admin/jobs/" + job.getId()).with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.destinations[0].reach").value(org.hamcrest.Matchers.nullValue()));
            assertThat(support.reachOf(job.getId())).isEmpty();

            jdbc.execute("CREATE TABLE IF NOT EXISTS test_reach_destinations (job_id BIGINT, position INT, reach VARCHAR(20), reach_reason TEXT)");
            jdbc.update("INSERT INTO test_reach_destinations VALUES (?, 0, 'NOT_REACHING', ?)", job.getId(),
                    "Error opening output rtmp://a.rtmp.youtube.com/live2/" + KEY + ": Connection refused");
            String table = support.reachTable("test_reach_destinations");
            try {
                String body = mvc.perform(get("/api/admin/jobs/" + job.getId()).with(admin()))
                        .andExpect(jsonPath("$.destinations[0].reach").value("NOT_REACHING"))
                        .andExpect(jsonPath("$.destinations[0].reachReason").value(containsString("Connection refused")))
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body).doesNotContain(KEY);
            } finally {
                support.reachTable(table);
            }
        } finally {
            jobs.findById(job.getId()).ifPresent(j -> {
                j.setLive(false);
                jobs.save(j);
            });
        }
    }

    @Test
    void supportCanTestADestinationWithAReasonButNotWhileItIsLive() throws Exception {
        // A bare key (sent to YouTube's server): the address check needs no DNS, which tests don't have.
        StreamJob ended = run(false, List.of(new StreamSpec.Destination("YouTube", KEY)), playlist());
        String url = "/api/admin/jobs/" + ended.getId() + "/destinations/0/test";
        mvc.perform(postJson(url, "{'reason':'try'}").with(user(customer.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(postJson(url, "{}").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/admin/jobs/" + ended.getId() + "/destinations/5/test", "{'reason':'Ticket 12'}").with(admin()))
                .andExpect(status().isNotFound());

        String body = mvc.perform(postJson(url, "{'reason':'Ticket 12: says YouTube shows nothing'}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(KEY);
        Long testId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        DestinationTest t = destinationTests.findById(testId).orElseThrow();
        assertThat(t.getUserId()).isEqualTo(customer.getId());
        assertThat(t.getUrl()).isEqualTo(KEY);
        mvc.perform(get("/api/admin/destination-tests/" + testId).with(admin())).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(audit.findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc("job", String.valueOf(ended.getId())))
                .anyMatch(x -> x.getAction().equals("DESTINATION_TEST") && x.getDetail().contains("Ticket 12"));
        // One test at a time per account.
        mvc.perform(postJson(url, "{'reason':'again'}").with(admin())).andExpect(status().isConflict());
        destinationTests.deleteById(testId);

        StreamJob live = run(true, List.of(new StreamSpec.Destination("YouTube", KEY)), playlist());
        mvc.perform(postJson("/api/admin/jobs/" + live.getId() + "/destinations/0/test", "{'reason':'Ticket 13'}").with(admin()))
                .andExpect(status().isConflict());
        jobs.findById(live.getId()).ifPresent(j -> {
            j.setLive(false);
            jobs.save(j);
        });
    }
}
