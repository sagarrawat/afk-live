package com.afklive.streamer.service;

import com.afklive.streamer.PostgresIntegrationTest;
import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OPS-4: every web deploy stops a web slot. A playout copy it was making must go back to the queue at
 * once, not wait out the 8-hour stale-claim timeout (the stream would play the expensive way all that time).
 */
@SpringBootTest
class PlayoutCopiesShutdownTest extends PostgresIntegrationTest {

    @TempDir
    Path dir;

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired MediaAssetRepository assets;

    @Test
    void stoppingHandsTheCopyInProgressBackAndEndsItsFfmpeg() throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        assumeTrue(new ProcessBuilder("mkfifo", dir.resolve("fifo").toString()).start().waitFor() == 0, "mkfifo");
        // FFmpeg blocks reading a pipe nobody writes to: a copy that is still being made when the slot stops.
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.readLocation(anyString())).thenReturn(dir.resolve("fifo").toString());

        User u = new User("copystop-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        u.setPlanType(PlanType.ESSENTIALS);
        u = users.save(u);
        MediaAsset a = new MediaAsset(u, "clip.mp4", "clip-" + UUID.randomUUID() + ".mp4", 1000L, MediaAsset.ContentType.VIDEO);
        a.setStatus(MediaStatus.READY);
        a = assets.save(a);
        // Anything else still queued by other tests is done first, so this copy is the one being made.
        jdbc.update("UPDATE media_playout_copies SET status = 'FAILED' WHERE status IN ('PENDING', 'WORKING')");
        jdbc.update("INSERT INTO media_playout_copies (media_id, canvas_width, canvas_height, fps, status) VALUES (?, 640, 360, 30, 'PENDING')",
                a.getId());

        PlayoutCopies copies = new PlayoutCopies(jdbc, storage, "web", false, EncoderSettings.DEFAULT);
        CompletableFuture<Boolean> making = CompletableFuture.supplyAsync(copies::prepareNext);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!"WORKING".equals(status(a)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(status(a)).isEqualTo("WORKING");
        Thread.sleep(300); // let FFmpeg start and block on the pipe

        copies.stop();

        Map<String, Object> row = jdbc.queryForMap("SELECT status, claimed_at FROM media_playout_copies WHERE media_id = ?", a.getId());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("claimed_at")).isNull();
        // Its FFmpeg was stopped, so the making thread returns, without recording a failure or claiming more.
        assertThat(making.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(status(a)).isEqualTo("PENDING");
        assertThat(copies.prepareNext()).isFalse();
    }

    private String status(MediaAsset a) {
        return jdbc.queryForObject("SELECT status FROM media_playout_copies WHERE media_id = ?", String.class, a.getId());
    }
}
