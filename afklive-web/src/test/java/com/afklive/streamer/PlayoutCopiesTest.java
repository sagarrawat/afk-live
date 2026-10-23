package com.afklive.streamer;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.MediaProbe;
import com.afklive.streamer.service.MediaService;
import com.afklive.streamer.service.PlayoutCopies;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Playout copies end to end: a worker asks, the web side makes it with real FFmpeg, playout switches, delete cleans up. */
@SpringBootTest
class PlayoutCopiesTest extends PostgresIntegrationTest {

    @TempDir
    static Path dir;
    static Path source;

    @Autowired PlayoutCopies copies;
    @Autowired MediaAssetRepository assets;
    @Autowired UserRepository users;
    @Autowired FileStorageService storage;
    @Autowired MediaService media;
    @Autowired MediaProbe probe;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void makeSource() throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg");
        // A small stand-in for a vertical phone clip at 60 fps.
        source = dir.resolve("vertical60.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=60",
                "-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", source.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }

    private MediaAsset upload(String email) throws Exception {
        User u = users.findByEmail(email).orElseGet(() -> {
            User n = new User(email);
            n.setPlanType(PlanType.ESSENTIALS);
            return users.save(n);
        });
        String key;
        try (InputStream in = Files.newInputStream(source)) {
            key = storage.uploadFile(in, "vertical60.mp4", Files.size(source));
        }
        MediaAsset a = new MediaAsset(u, "vertical60.mp4", key, Files.size(source), MediaAsset.ContentType.VIDEO);
        a.setStatus(MediaStatus.READY);
        a.setWidth(360);
        a.setHeight(640);
        a.setFps(60.0);
        a.setDurationSeconds(3.0);
        a.setHasAudio(true);
        return assets.save(a);
    }

    @Test
    void aVideoBiggerOrFasterThanTheStreamIsCopiedOnceAndPlayedFromTheCopy() throws Exception {
        String email = "copies-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MediaAsset a = upload(email);
        copies.forget();

        // A vertical 180x320 stream at 30 fps plays it: the original for now, and a copy is asked for.
        assertThat(copies.keyFor(a.getS3Key(), 180, 320, 30)).isEqualTo(a.getS3Key());
        assertThat(jdbc.queryForObject("SELECT status FROM media_playout_copies WHERE media_id = ?", String.class, a.getId()))
                .isEqualTo("PENDING");
        // Asking again doesn't queue a second one.
        copies.forget();
        copies.keyFor(a.getS3Key(), 180, 320, 30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ?", Integer.class, a.getId())).isEqualTo(1);

        assertThat(copies.prepareNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM media_playout_copies WHERE media_id = ?", String.class, a.getId()))
                .isEqualTo("READY");

        copies.forget();
        String copy = copies.keyFor(a.getS3Key(), 180, 320, 30);
        assertThat(copy).isNotEqualTo(a.getS3Key());
        MediaProbe.MediaInfo info = probe.probe(storage.readLocation(copy));
        assertThat(info.width()).isEqualTo(180);
        assertThat(info.height()).isEqualTo(320);
        assertThat(info.fps()).isBetween(29.0, 31.0);
        assertThat(info.hasAudio()).isTrue();
        assertThat(info.durationSeconds()).isBetween(2.8, 3.2);

        // Deleting the file deletes its copy too.
        media.delete(email, List.of(a.getId()));
        assertThatThrownBy(() -> storage.size(copy)).isInstanceOf(Exception.class);
    }

    @Test
    void aVideoAlreadyAtTheStreamsSizeAndRateIsPlayedAsIs() throws Exception {
        MediaAsset a = upload("copies-same@example.com");
        copies.forget();
        // A 360x640 60 fps stream: the clip already is exactly that.
        assertThat(copies.keyFor(a.getS3Key(), 360, 640, 60)).isEqualTo(a.getS3Key());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ?", Integer.class, a.getId())).isZero();
    }

    @Test
    void copiesNobodyPlayedFor30DaysAreDeletedAndRecentlyPlayedOnesKept() throws Exception {
        MediaAsset a = upload("copies-old@example.com");
        copies.forget();
        copies.keyFor(a.getS3Key(), 180, 320, 30);
        copies.keyFor(a.getS3Key(), 90, 160, 30);
        while (copies.prepareNext()) {
            // make both
        }
        copies.forget();
        String played = copies.keyFor(a.getS3Key(), 180, 320, 30); // records a play
        String idle = jdbc.queryForObject("SELECT storage_key FROM media_playout_copies WHERE media_id = ? AND canvas_width = 90",
                String.class, a.getId());
        // Made long ago; only the 180x320 one has been played since.
        jdbc.update("UPDATE media_playout_copies SET created_at = now() - interval '40 days', finished_at = now() - interval '40 days' WHERE media_id = ?",
                a.getId());

        assertThat(copies.deleteUnplayed()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT canvas_width FROM media_playout_copies WHERE media_id = ?", Integer.class, a.getId()))
                .containsExactly(180);
        assertThatThrownBy(() -> storage.size(idle)).isInstanceOf(Exception.class);
        assertThat(storage.size(played)).isPositive();
    }
}
