package com.afklive.streamer;

import com.afklive.streamer.dto.StreamDtos;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.StreamDestinationRepository;
import com.afklive.streamer.repository.StreamItemRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.MediaProcessor;
import com.afklive.streamer.service.MediaService;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.streaming.StudioSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ML-1..ML-6 against local storage and real FFmpeg. */
@SpringBootTest(properties = "app.media.min-part-bytes=65536")
class MediaLibraryTest extends PostgresIntegrationTest {

    static final int PART = 65536;
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dir;
    static byte[] video;
    static byte[] audio;
    static byte[] png;

    @Autowired WebApplicationContext context;
    @Autowired MediaService media;
    @Autowired MediaProcessor processor;
    @Autowired MediaAssetRepository assets;
    @Autowired UserRepository users;
    @Autowired FileStorageService storage;
    @Autowired LiveStreamService liveStreams;
    @Autowired StreamItemRepository items;
    @Autowired StreamDestinationRepository destinations;
    @Autowired StreamJobRepository jobs;
    @Autowired StreamService streamService;

    MockMvc mvc;
    String email;

    @BeforeAll
    static void makeFiles() throws Exception {
        Path v = dir.resolve("clip.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=4", "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "25", "-c:a", "aac", "-shortest", v.toString());
        video = Files.readAllBytes(v);
        Path a = dir.resolve("tone.m4a");
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=330:duration=3", "-c:a", "aac", a.toString());
        audio = Files.readAllBytes(a);
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        png = out.toByteArray();
        assertThat(video.length).isGreaterThan(PART); // more than one part
    }

    static void ffmpeg(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "ml-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(email);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
    }

    @AfterEach
    void endRuns() {
        for (StreamJob j : jobs.findAll()) {
            if (j.isLive() && j.getUser().getEmail().startsWith("ml-")) {
                streamService.finalizeJob(j.getId(), "test");
            }
        }
    }

    private long used() {
        return users.usedStorage(users.findByEmail(email).orElseThrow().getId());
    }

    /** Uploads through the HTTP API, part by part; returns the new file's id. */
    private long upload(String name, byte[] bytes) throws Exception {
        String created = mvc.perform(post("/api/media/uploads").with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("filename", name, "size", bytes.length))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode up = JSON.readTree(created);
        String id = up.get("id").asText();
        int parts = up.get("partCount").asInt();
        for (int n = 1; n <= parts; n++) {
            sendPart(id, n, bytes);
        }
        String done = mvc.perform(post("/api/media/uploads/" + id + "/complete").with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(done).get("id").asLong();
    }

    private void sendPart(String id, int n, byte[] bytes) throws Exception {
        String target = mvc.perform(post("/api/media/uploads/" + id + "/parts/" + n).with(user(email)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode t = JSON.readTree(target);
        assertThat(t.get("direct").asBoolean()).isFalse(); // local storage: parts come through the app
        int from = (n - 1) * PART;
        byte[] chunk = Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + PART));
        mvc.perform(put(t.get("url").asText()).with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(chunk))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }

    // ------------------------------------------------------------------ ML-1 / ML-2

    @Test
    void aVideoUploadsInPartsAndIsCheckedBeforeItCanStream() throws Exception {
        long id = upload("Rainy night.mp4", video);
        MediaAsset a = assets.findById(id).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(MediaStatus.PROCESSING);
        assertThat(a.getFileSize()).isEqualTo(video.length);
        assertThat(used()).isEqualTo(video.length);
        try (var in = storage.downloadFile(a.getS3Key())) {
            assertThat(in.readAllBytes()).isEqualTo(video); // parts joined in order
        }

        assertThat(processor.process(id)).isTrue();
        assertThat(processor.process(id)).isFalse(); // done; nothing to claim
        // AU-5: its loudness is measured for playout.
        MediaAsset done = assets.findById(id).orElseThrow();
        assertThat(done.isLoudnessChecked()).isTrue();
        assertThat(done.getLoudnessLufs()).isBetween(-60.0, 0.0);

        mvc.perform(get("/api/media/" + id).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.type").value("VIDEO"))
                .andExpect(jsonPath("$.width").value(320))
                .andExpect(jsonPath("$.height").value(240))
                .andExpect(jsonPath("$.hasAudio").value(true))
                .andExpect(jsonPath("$.durationSeconds").value(org.hamcrest.Matchers.closeTo(4.0, 0.3)))
                .andExpect(jsonPath("$.thumbnailUrl").value("/api/media/" + id + "/thumbnail"));
        byte[] thumb = mvc.perform(get("/api/media/" + id + "/thumbnail").with(user(email)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage t = ImageIO.read(new ByteArrayInputStream(thumb));
        assertThat(t.getWidth()).isEqualTo(320);

        // the file is only for its owner
        mvc.perform(get("/api/media/" + id + "/file").with(user("someone-else@example.com"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/media/" + id + "/file").with(user(email))).andExpect(status().isOk());
    }

    @Test
    void anInterruptedUploadResumesFromThePartsAlreadyStored() throws Exception {
        MediaService.UploadView up = media.startUpload(email, "long.mp4", video.length);
        assertThat(up.partCount()).isGreaterThan(1);
        sendPart(up.id(), 1, video);

        // the page reloads: the open upload and its stored parts are still there
        mvc.perform(get("/api/media/uploads").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(up.id()))
                .andExpect(jsonPath("$[0].uploadedParts[0]").value(1))
                .andExpect(jsonPath("$[0].uploadedBytes").value(PART));

        assertThatThrownBy(() -> media.complete(email, up.id(), null)).hasMessageContaining("hasn't arrived");
        for (int n = 2; n <= up.partCount(); n++) {
            sendPart(up.id(), n, video);
        }
        sendPart(up.id(), 1, video); // re-sending a part replaces it
        MediaService.MediaView done = media.complete(email, up.id(), null);
        assertThat(done.status()).isEqualTo("PROCESSING");
        assertThat(media.complete(email, up.id(), null).id()).isEqualTo(done.id()); // completing twice is harmless
        assertThat(used()).isEqualTo(video.length);
    }

    @Test
    void partsMustHaveTheExpectedSize() throws Exception {
        MediaService.UploadView up = media.startUpload(email, "clip.mp4", video.length);
        mvc.perform(put("/api/media/uploads/" + up.id() + "/parts/1").with(user(email)).with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[10]))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/media/uploads/" + up.id() + "/parts/" + (up.partCount() + 1)).with(user(email)).with(csrf()))
                .andExpect(status().isBadRequest());
        // another user can't touch it
        mvc.perform(post("/api/media/uploads/" + up.id() + "/parts/1").with(user("ml-other@example.com")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedFilesAreRefusedWithAReason() throws Exception {
        mvc.perform(post("/api/media/uploads").with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"notes.docx\",\"size\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(".docx files aren't supported")));
        assertThat(used()).isZero();
    }

    @Test
    void aBrokenVideoFailsWithAPlainReason() throws Exception {
        byte[] junk = new byte[1000];
        new java.util.Random(1).nextBytes(junk);
        long id = upload("broken.mp4", junk);
        processor.process(id);
        MediaAsset a = assets.findById(id).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(MediaStatus.FAILED);
        assertThat(a.getStatusReason()).contains("couldn't be read as a video");
    }

    @Test
    void audioAndImagesAreCheckedToo() throws Exception {
        long song = upload("tone.m4a", audio);
        long pic = upload("cover.png", png);
        long notAnImage = upload("fake.jpg", "hello".getBytes());
        processor.sweep();
        MediaAsset s = assets.findById(song).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(s.getContentType()).isEqualTo(MediaAsset.ContentType.AUDIO);
        assertThat(s.getDurationSeconds()).isBetween(2.5, 3.5);
        MediaAsset p = assets.findById(pic).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(p.getWidth()).isEqualTo(640);
        assertThat(p.getThumbnailKey()).isNotNull();
        assertThat(assets.findById(notAnImage).orElseThrow().getStatus()).isEqualTo(MediaStatus.FAILED);
    }

    @Test
    void aClaimFromAnInstanceThatDiedIsTakenOver() throws Exception {
        long id = upload("clip.mp4", video);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        assertThat(assets.claimProcessing(id, now, now.minusMinutes(10))).isEqualTo(1); // "another instance"
        assertThat(processor.process(id)).isFalse(); // still fresh
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.PROCESSING);

        org.springframework.jdbc.core.JdbcTemplate jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("UPDATE media_assets SET processing_claimed_at = now() - interval '11 minutes' WHERE id = ?", id);
        processor.sweep();
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.READY);
    }

    // ------------------------------------------------------------------ ML-5

    @Test
    void storageIsReservedWhenAnUploadStartsAndGivenBackIfItIsAbandoned() throws Exception {
        User u = users.findByEmail(email).orElseThrow();
        u.setPlanType(PlanType.FREE); // 2 GB
        users.save(u);
        long gb = 1024L * 1024 * 1024;
        MediaService.UploadView big = media.startUpload(email, "big.mp4", (long) (1.7 * gb));
        mvc.perform(get("/api/media/storage").with(user(email)))
                .andExpect(jsonPath("$.warning").value(true))
                .andExpect(jsonPath("$.full").value(false))
                .andExpect(jsonPath("$.limitBytes").value(2 * gb));
        // a second large file doesn't fit while the first is still uploading
        mvc.perform(post("/api/media/uploads").with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"second.mp4\",\"size\":" + gb + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Not enough storage")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/media/uploads/" + big.id())
                .with(user(email)).with(csrf())).andExpect(status().isNoContent());
        assertThat(used()).isZero();

        MediaService.UploadView stale = media.startUpload(email, "stale.mp4", gb);
        assertThat(media.expireUploads(ZonedDateTime.now(ZoneId.of("UTC")).plusHours(25))).isGreaterThanOrEqualTo(1);
        assertThat(used()).isZero();
        assertThatThrownBy(() -> media.partTarget(email, stale.id(), 1)).hasMessageContaining("cancelled or expired");
    }

    // ------------------------------------------------------------------ ML-3 / ML-4

    @Test
    void theLibraryCanBeSearchedFilteredAndSorted() throws Exception {
        long b = upload("Beta lofi.mp4", video);
        long a = upload("alpha.m4a", audio);
        long c = upload("Gamma lofi.png", png);
        processor.sweep();

        mvc.perform(get("/api/media").param("q", "LOFI").param("sort", "name").with(user(email)))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(b))
                .andExpect(jsonPath("$.content[1].id").value(c));
        mvc.perform(get("/api/media").param("type", "audio").with(user(email)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a));
        mvc.perform(get("/api/media").param("sort", "duration").param("dir", "desc").with(user(email)))
                .andExpect(jsonPath("$.content[0].id").value(b))
                .andExpect(jsonPath("$.content[2].id").value(c)); // no duration: last
        mvc.perform(get("/api/media").param("status", "failed").with(user(email)))
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get("/api/media").param("sort", "colour").with(user(email))).andExpect(status().isBadRequest());
        // a second file with the same name gets a numbered title
        long again = upload("alpha.m4a", audio);
        assertThat(assets.findById(again).orElseThrow().getTitle()).isEqualTo("alpha (2).m4a");
    }

    // ------------------------------------------------------------------ ML-6

    @Test
    void filesUsedByALiveStreamCantBeDeletedOthersCan() throws Exception {
        long onAir = upload("on-air.mp4", video);
        long draftOnly = upload("draft.mp4", video);
        long cover = upload("cover.png", png);
        long loose = upload("loose.m4a", audio);
        processor.sweep();
        long before = used();

        // a live stream plays on-air.mp4
        LiveStream live = liveStreams.create(email, new StreamDtos.CreateStream("Live", null, null, 720, 30));
        liveStreams.setItems(email, live.getId(), StreamItem.Kind.VIDEO, List.of(onAir));
        StreamDestination d = new StreamDestination();
        d.setName("YouTube");
        d.setStreamKey("abcd-efgh-ijkl-mnop");
        d.setType("RTMP");
        d.setUser(users.findByEmail(email).orElseThrow());
        destinations.save(d);
        liveStreams.setTargets(email, live.getId(), List.of(new StreamDtos.TargetRequest(d.getId(), true)));
        liveStreams.goLive(email, live.getId(), null);

        // a draft uses draft.mp4 and cover.png as an overlay
        LiveStream draft = liveStreams.create(email, new StreamDtos.CreateStream("Draft", null, null, 720, 30));
        liveStreams.setItems(email, draft.getId(), StreamItem.Kind.VIDEO, List.of(draftOnly));
        liveStreams.setOverlays(email, draft.getId(), List.of(new StudioSpec.OverlaySetting("logo", "IMAGE", true, 0.05, 0.05, 0.1, 0,
                null, cover, null, null, null, 1.0, null, null, 0, null)));

        mvc.perform(get("/api/media/" + onAir).with(user(email)))
                .andExpect(jsonPath("$.usedBy[0]").value("Live"))
                .andExpect(jsonPath("$.inLiveUse").value(true));

        String res = mvc.perform(post("/api/media/delete").with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("ids", List.of(onAir, draftOnly, cover, loose)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = JSON.readTree(res);
        assertThat(r.get("deleted")).hasSize(3);
        assertThat(r.get("blocked")).hasSize(1);
        assertThat(r.get("blocked").get(0).get("id").asLong()).isEqualTo(onAir);
        assertThat(r.get("blocked").get(0).get("reason").asText()).contains("Live", "live");

        assertThat(assets.findById(draftOnly)).isEmpty();
        assertThat(items.findAllByStreamIdOrderByPositionAsc(draft.getId())).isEmpty();
        assertThat(liveStreams.view(email, draft.getId()).overlays()).noneMatch(o -> o.id().equals("logo"));
        assertThat(used()).isEqualTo(before - video.length - png.length - audio.length); // on-air.mp4 remains

        // deleting only a blocked file is a conflict with the reason
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/media/" + onAir)
                        .with(user(email)).with(csrf()))
                .andExpect(status().isConflict());
        // someone else's file isn't found
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/media/" + onAir)
                        .with(user("ml-stranger@example.com")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameKeepsTheFile() throws Exception {
        long id = upload("clip.mp4", video);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/media/" + id)
                        .with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Sunset loop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sunset loop"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/media/" + id)
                        .with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
