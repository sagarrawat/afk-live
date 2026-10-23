package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.CreateStream;
import com.afklive.streamer.dto.StreamDtos.ScheduleRequest;
import com.afklive.streamer.dto.StreamDtos.TargetRequest;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.AccountService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.StreamScheduleService;
import com.afklive.streamer.service.StreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ON-7: data export (never secrets) and account deletion with a 7-day grace period. */
@SpringBootTest
class AccountDataTest extends PostgresIntegrationTest {

    static final String STREAM_KEY = "abcd-SECRET-KEY-9999";
    static final String PASSWORD = "a long enough password";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired LiveStreamService liveStreams;
    @Autowired StreamScheduleService schedules;
    @Autowired LiveStreamRepository streams;
    @Autowired MediaAssetRepository media;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamService streamService;
    @Autowired AccountService accounts;
    @Autowired FileStorageService storage;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    String email;
    User owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "acct-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.FREE);
        u.setFullName("Meera Iyer");
        u.setPassword(encoder.encode(PASSWORD));
        owner = users.save(u);
    }

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("acct-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private record Fixture(LiveStream stream, LiveStream phone, MediaAsset video, StreamDestination destination, String invoiceNumber) {
    }

    /** A playlist stream with a stored video, a destination with a key and a schedule; a phone stream; an invoice. */
    private Fixture fixture() {
        byte[] bytes = "not really a video".getBytes(StandardCharsets.UTF_8);
        String key = storage.uploadFile(new ByteArrayInputStream(bytes), "rain.mp4", bytes.length);
        MediaAsset v = new MediaAsset(owner, "rain.mp4", key, (long) bytes.length, MediaAsset.ContentType.VIDEO);
        v.setDurationSeconds(60.0);
        v.setHasAudio(true);
        v = media.save(v);
        users.addStorage(owner.getId(), bytes.length);

        LiveStream s = liveStreams.create(email, new CreateStream("Rainy Lofi", null, null, 720, 30));
        liveStreams.setItems(email, s.getId(), StreamItem.Kind.VIDEO, List.of(v.getId()));
        StreamDestination d = new StreamDestination();
        d.setName("Main channel");
        d.setStreamKey(STREAM_KEY);
        d.setType("RTMP");
        d.setUser(owner);
        d = destinations.save(d);
        liveStreams.setTargets(email, s.getId(), List.of(new TargetRequest(d.getId(), true)));
        schedules.create(email, s.getId(), new ScheduleRequest("DAILY", null, null, null, "21:00", "23:00", "Asia/Kolkata", null, true));

        LiveStream phone = liveStreams.create(email, new CreateStream("Phone IRL", "PHONE", null, 720, 30));

        String number = "AFK/TEST/" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("INSERT INTO invoices (number, user_id, description, currency, taxable_amount, total, customer_name, customer_address, "
                + "customer_state, issued_at) VALUES (?, ?, 'Essentials plan, monthly', 'INR', 10000, 11800, 'Meera Iyer', '12 MG Road', 'KA', now())",
                number, owner.getId());
        return new Fixture(s, streams.findById(phone.getId()).orElseThrow(), v, d, number);
    }

    private Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void exportHasTheAccountsDataButNoKeys() throws Exception {
        Fixture f = fixture();
        MvcResult r = mvc.perform(get("/api/account/export").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"afk-live-data-")))
                .andReturn();
        Map<String, String> files = unzip(r.getResponse().getContentAsByteArray());
        assertThat(files).containsKeys("afk-live-data.json", "README.txt");
        String json = files.get("afk-live-data.json");

        assertThat(json).contains(email, "Meera Iyer", "Rainy Lofi", "Phone IRL", "Main channel", "rain.mp4", "DAILY", "21:00",
                "Asia/Kolkata", f.invoiceNumber(), "11800");
        // No secrets: not the destination's stream key, the phone stream's ingest key, or storage keys.
        assertThat(f.phone().getIngestKey()).isNotBlank();
        assertThat(json).doesNotContain(STREAM_KEY, f.phone().getIngestKey(), f.video().getS3Key())
                .doesNotContainIgnoringCase("streamKey").doesNotContainIgnoringCase("ingestKey").doesNotContain("password");
    }

    @Test
    void exportNeedsSignIn() throws Exception {
        mvc.perform(get("/api/account/export")).andExpect(status().is3xxRedirection());
    }

    @Test
    void deletionClosesTheAccountAndCanBeCancelledFromSignIn() throws Exception {
        Fixture f = fixture();
        StreamJob job = liveStreams.goLive(email, f.stream().getId(), null);

        mvc.perform(post("/api/account/delete").with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"someone@else.com\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.findByEmail(email).orElseThrow().getDeletionScheduledFor()).isNull();

        MvcResult r = mvc.perform(post("/api/account/delete").with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email.toUpperCase() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        ZonedDateTime when = users.findByEmail(email).orElseThrow().getDeletionScheduledFor();
        assertThat(when).isBetween(ZonedDateTime.now(ZoneId.of("UTC")).plusDays(7).minusMinutes(5),
                ZonedDateTime.now(ZoneId.of("UTC")).plusDays(7).plusMinutes(5));
        // The live stream was stopped, and it can't start again.
        assertThat(jobs.findById(job.getId()).orElseThrow().isLive()).isFalse();
        assertThatThrownBy(() -> liveStreams.goLive(email, f.stream().getId(), null))
                .hasMessageContaining("scheduled for deletion");

        // The browser that asked is signed out but offered to keep the account.
        MockHttpSession afterDelete = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get("/login").session(afterDelete))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("scheduled for deletion")));

        // Signing in again with the password doesn't open the app; it offers to keep the account.
        MvcResult login = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD)).andReturn();
        MockHttpSession signedIn = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult closed = mvc.perform(get("/api/me").session(signedIn)).andExpect(status().isUnauthorized()).andReturn();
        // The signed-in session was ended; the browser now has a fresh one that may restore the account.
        assertThat(signedIn.isInvalid()).isTrue();
        MockHttpSession session = (MockHttpSession) closed.getRequest().getSession(false);
        mvc.perform(get("/login").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Keep my account")));

        // Still in the grace period: nothing is deleted.
        assertThat(accounts.purgeDue(ZonedDateTime.now(ZoneId.of("UTC")))).isZero();

        mvc.perform(post("/account/restore").session(session).with(csrf())).andExpect(redirectedUrl("/login?restored"));
        assertThat(users.findByEmail(email).orElseThrow().getDeletionScheduledFor()).isNull();
        MvcResult again = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD)).andReturn();
        mvc.perform(get("/api/me").session((MockHttpSession) again.getRequest().getSession(false))).andExpect(status().isOk());

        // Nothing was lost.
        assertThat(streams.findAllByUserEmailOrderByCreatedAtAsc(email)).hasSize(2);
        assertThat(storage.loadFileAsResource(f.video().getS3Key()).exists()).isTrue();
    }

    @Test
    void restoreNeedsAProvenAccount() throws Exception {
        accounts.requestDeletion(email, email);
        mvc.perform(post("/account/restore").with(csrf())).andExpect(redirectedUrl("/login"));
        assertThat(users.findByEmail(email).orElseThrow().getDeletionScheduledFor()).isNotNull();
    }

    @Test
    void afterTheGracePeriodTheDataAndFilesAreDeletedAndInvoicesAnonymised() throws Exception {
        Fixture f = fixture();
        accounts.requestDeletion(email, email);
        Long id = owner.getId();

        assertThat(accounts.purgeDue(ZonedDateTime.now(ZoneId.of("UTC")).plusDays(6))).isZero();
        assertThat(accounts.purgeDue(ZonedDateTime.now(ZoneId.of("UTC")).plusDays(8))).isGreaterThanOrEqualTo(1);

        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(streams.findAllByUserEmailOrderByCreatedAtAsc(email)).isEmpty();
        assertThat(media.findById(f.video().getId())).isEmpty();
        assertThat(destinations.findById(f.destination().getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_schedules WHERE stream_id = ?", Integer.class, f.stream().getId())).isZero();
        assertThatThrownBy(() -> storage.loadFileAsResource(f.video().getS3Key())).hasMessageContaining("not found");

        // The invoice stays for the tax records, without the person.
        Map<String, Object> inv = jdbc.queryForMap("SELECT user_id, customer_name, customer_address, customer_state, total FROM invoices WHERE number = ?",
                f.invoiceNumber());
        assertThat(inv.get("user_id")).isNull();
        assertThat(inv.get("customer_name")).isNull();
        assertThat(inv.get("customer_address")).isNull();
        assertThat(inv.get("customer_state")).isEqualTo("KA");
        assertThat(((Number) inv.get("total")).longValue()).isEqualTo(11800);

        // Running again is harmless.
        assertThat(accounts.purgeDue(ZonedDateTime.now(ZoneId.of("UTC")).plusDays(8))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    void cancellingKeepsTheAccountFromBeingPurged() {
        fixture();
        accounts.requestDeletion(email, email);
        accounts.cancelDeletion(email);
        assertThat(accounts.purgeDue(ZonedDateTime.now(ZoneId.of("UTC")).plusDays(30))).isZero();
        assertThat(users.findByEmail(email)).isPresent();
    }
}
