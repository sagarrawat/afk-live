package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.*;
import com.afklive.streamer.repository.*;
import com.afklive.streamer.service.IngestService;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.streaming.StudioSpec;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MS-1 / MS-6: phone ingest setup, the ingest server's authorization, and stream keys encrypted at rest. */
@SpringBootTest
class PhoneIngestTest extends PostgresIntegrationTest {

    @Autowired LiveStreamService streams;
    @Autowired IngestService ingest;
    @Autowired LiveStreamRepository streamRepository;
    @Autowired UserRepository users;
    @Autowired StreamDestinationRepository destinations;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String newUser() {
        String email = "phone-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
        return email;
    }

    private LiveStream phoneStream(String email) {
        return streams.create(email, new CreateStream("IRL walk", "PHONE", "VERTICAL", 720, 30));
    }

    private String authBody(String action, String path, String user, String pass) {
        return "{\"user\":\"" + user + "\",\"password\":\"" + pass + "\",\"token\":\"\",\"ip\":\"1.2.3.4\",\"action\":\"" + action
                + "\",\"path\":\"" + path + "\",\"protocol\":\"rtmp\",\"id\":\"x\",\"query\":\"\",\"userAgent\":\"Larix\"}";
    }

    @Test
    void aPhoneStreamGetsAPrivateKeyAppLinksAndAQrCodeThatScans() throws Exception {
        String email = newUser();
        LiveStream s = phoneStream(email);
        IngestService.Setup setup = ingest.setup(ingest.ensureKey(s));
        assertThat(setup.streamKey()).hasSize(24);
        assertThat(setup.rtmpServer()).isEqualTo("rtmp://ingest.test/live");
        assertThat(setup.rtmpUrl()).isEqualTo("rtmp://ingest.test/live/" + setup.streamKey());
        assertThat(setup.srtUrl()).isEqualTo("srt://ingest.test:8890?streamid=publish:live/" + setup.streamKey() + "&latency=1000000");
        assertThat(setup.larixLink()).startsWith("larix://set/v1?conn[][url]=rtmp%3A%2F%2Fingest.test%2Flive%2F" + setup.streamKey());

        byte[] png = mvc.perform(get("/api/streams/" + s.getId() + "/ingest/qr.png?type=rtmp").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(img.getWidth(), img.getHeight(), px)))).getText();
        assertThat(decoded).isEqualTo(setup.rtmpUrl());

        // The key is stored encrypted, never in plain text.
        String stored = jdbc.queryForObject("SELECT ingest_key FROM streams WHERE id = ?", String.class, s.getId());
        assertThat(stored).startsWith("enc:v1:").doesNotContain(setup.streamKey());

        // Someone else can't see it.
        mvc.perform(get("/api/streams/" + s.getId() + "/ingest").with(user(newUser()).roles("USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theIngestServerOnlyLetsValidKeysPublishAndWorkersRead() throws Exception {
        String email = newUser();
        LiveStream s = ingest.ensureKey(phoneStream(email));
        String path = "live/" + s.getIngestKey();
        String url = "/internal/ingest/auth?secret=test-auth-secret";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(authBody("publish", path, "", "")))
                .andExpect(status().isOk());
        assertThat(streamRepository.findById(s.getId()).orElseThrow().getIngestLastPublishAt()).isNotNull();
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(authBody("publish", "live/notAKey123456", "", "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(authBody("read", path, "worker", "test-read-pass")))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(authBody("read", path, "worker", "guess")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(authBody("api", "", "", "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/ingest/auth?secret=wrong").contentType(MediaType.APPLICATION_JSON).content(authBody("publish", path, "", "")))
                .andExpect(status().isForbidden());

        // A new key: the old one stops working at once.
        streams.get(email, s.getId());
        ingest.regenerate(streamRepository.findById(s.getId()).orElseThrow());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(authBody("publish", path, "", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void goingLiveFromAPhoneGivesTheWorkerTheReadAddressAndGraceSettings() {
        String email = newUser();
        LiveStream s = phoneStream(email);
        StreamDestination d = new StreamDestination();
        d.setName("YouTube");
        d.setStreamKey("abcd-efgh-ijkl-mnop");
        d.setUser(users.findByEmail(email).orElseThrow());
        d = destinations.save(d);
        streams.setTargets(email, s.getId(), List.of(new TargetRequest(d.getId(), true)));
        streams.update(email, s.getId(), new UpdateStream(null, null, null, null, null, null, null, null, null, null, null,
                true, 300, 2000));

        Preflight pf = streams.preflight(email, s.getId());
        assertThat(pf.ok()).isTrue(); // no playlist needed; the phone just hasn't connected yet (a note, not a blocker)
        assertThat(pf.checks()).anyMatch(c -> c.id().equals("phone") && c.message().contains("hasn't connected"));

        StreamJob job = streams.goLive(email, s.getId(), null);
        StudioSpec studio = StreamSpec.fromJson(job.getSpec()).studio();
        LiveStream saved = streamRepository.findById(s.getId()).orElseThrow();
        assertThat(studio.source()).isEqualTo("PHONE");
        assertThat(studio.phone().ingestUrl()).isEqualTo("rtmp://127.0.0.1:1935/live/" + saved.getIngestKey() + "?user=worker&pass=test-read-pass");
        assertThat(studio.phone().graceSeconds()).isEqualTo(300);
        assertThat(studio.overlays()).anyMatch(o -> "Be right back".equals(o.text()) && "BRB".equals(o.scene()));
        com.afklive.streamer.service.StreamService ss = context.getBean(com.afklive.streamer.service.StreamService.class);
        ss.finalizeJob(job.getId(), "test");
    }

    @Test
    void streamKeysAreEncryptedAtRestAndReadBackPlain() {
        String email = newUser();
        StreamDestination d = new StreamDestination();
        d.setName("YouTube");
        d.setStreamKey("abcd-efgh-ijkl-mnop");
        d.setUser(users.findByEmail(email).orElseThrow());
        d = destinations.save(d);
        String stored = jdbc.queryForObject("SELECT stream_key FROM stream_destinations WHERE id = ?", String.class, d.getId());
        assertThat(stored).startsWith("enc:v1:").doesNotContain("abcd");
        assertThat(destinations.findById(d.getId()).orElseThrow().getStreamKey()).isEqualTo("abcd-efgh-ijkl-mnop");
        assertThat(destinations.findByStreamKeyAndUser("abcd-efgh-ijkl-mnop", d.getUser())).hasSize(1);

        // Rows saved before encryption was turned on still read correctly.
        jdbc.update("UPDATE stream_destinations SET stream_key = 'legacy-plain-key' WHERE id = ?", d.getId());
        assertThat(destinations.findById(d.getId()).orElseThrow().getStreamKey()).isEqualTo("legacy-plain-key");
    }

    @Test
    void playlistStreamsHaveNoPhoneSetup() throws Exception {
        String email = newUser();
        LiveStream s = streams.create(email, new CreateStream("Lofi", null, null, 720, 30));
        mvc.perform(get("/api/streams/" + s.getId() + "/ingest").with(user(email).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("phone")));
    }
}
