package com.afklive.streamer;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.EncoderSettings;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Test
    void aCopyOfAVideoALiveRunPlaysIsNeverDeletedEvenIfItLooksUnplayed() throws Exception {
        String email = "copies-live-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MediaAsset a = upload(email);
        copies.forget();
        // Copy mode looks its profile copy up once, at the start of a run: a 24/7 run never touches it again.
        copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true);
        while (copies.prepareNext()) {
            // make it
        }
        String key = copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, false);
        assertThat(key).isNotNull();
        jdbc.update("UPDATE media_playout_copies SET created_at = now() - interval '40 days', finished_at = now() - interval '40 days', "
                + "last_played_at = now() - interval '35 days' WHERE media_id = ?", a.getId());
        // A live run whose spec (encrypted at rest, as the app writes it) plays the original.
        Long userId = a.getUser().getId();
        Long jobId = jdbc.queryForObject("INSERT INTO stream_jobs (user_id, stream_key, is_live, pid, start_time, status, desired_state, spec, "
                        + "applied_command_id, restarts, accumulated_cost) VALUES (?, 'k', TRUE, 0, now() - interval '31 days', 'LIVE', 'RUN', ?, 0, 0, 0) RETURNING id",
                Long.class, userId, com.afklive.streamer.util.SecretBox.encrypt("{\"maxHeight\":320,\"studio\":{\"height\":320,\"fps\":30,"
                        + "\"videos\":[{\"id\":\"" + a.getId() + "\",\"key\":\"" + a.getS3Key() + "\",\"duration\":3.0}]}}"));
        try {
            assertThat(copies.deleteUnplayed()).isZero();
            assertThat(storage.size(key)).isPositive();
            assertThat(copies.stats().kinds()).anySatisfy(k -> {
                assertThat(k.kind()).isEqualTo("PROFILE");
                assertThat(k.dueForDeletion()).isPositive();
            });
        } finally {
            jdbc.update("UPDATE stream_jobs SET is_live = FALSE, end_time = now() WHERE id = ?", jobId);
        }
        // Once that run ends, the copy goes, with its file.
        assertThat(copies.deleteUnplayed()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ?", Integer.class, a.getId())).isZero();
        assertThatThrownBy(() -> storage.size(key)).isInstanceOf(Exception.class);
    }

    @Test
    void twoWebInstancesDeleteAnOldCopyOnceAndCopySizesAreKept() throws Exception {
        MediaAsset a = upload("copies-twice-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        copies.forget();
        copies.keyFor(a.getS3Key(), 180, 320, 30);
        while (copies.prepareNext()) {
            // make it
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT storage_key, size_bytes FROM media_playout_copies WHERE media_id = ?", a.getId());
        String key = (String) row.get("storage_key");
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(storage.size(key));
        // A copy made before sizes were recorded gets its size from storage.
        jdbc.update("UPDATE media_playout_copies SET size_bytes = NULL WHERE media_id = ?", a.getId());
        assertThat(copies.fillSizes()).isPositive();
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM media_playout_copies WHERE media_id = ?", Long.class, a.getId()))
                .isEqualTo(storage.size(key));
        PlayoutCopies.Stats stats = copies.stats();
        assertThat(stats.keepDays()).isEqualTo(30);
        assertThat(stats.bytes()).isGreaterThanOrEqualTo(storage.size(key));

        jdbc.update("UPDATE media_playout_copies SET created_at = now() - interval '40 days', finished_at = now() - interval '40 days', "
                + "last_played_at = NULL WHERE media_id = ?", a.getId());
        PlayoutCopies second = new PlayoutCopies(jdbc, storage, "web", false, EncoderSettings.DEFAULT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> one = pool.submit(() -> {
                go.await();
                return copies.deleteUnplayed();
            });
            Future<Integer> two = pool.submit(() -> {
                go.await();
                return second.deleteUnplayed();
            });
            go.countDown();
            one.get();
            two.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ?", Integer.class, a.getId())).isZero();
        assertThatThrownBy(() -> storage.size(key)).isInstanceOf(Exception.class);
    }

    @Test
    void aCopyBeingMadeAgainIsNotDeletedByTheSweeper() throws Exception {
        MediaAsset a = upload("copies-remake-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        copies.forget();
        copies.keyFor(a.getS3Key(), 180, 320, 30);
        while (copies.prepareNext()) {
            // make it
        }
        jdbc.update("UPDATE media_playout_copies SET created_at = now() - interval '40 days', finished_at = now() - interval '40 days', "
                + "status = 'PENDING', storage_key = NULL WHERE media_id = ?", a.getId());
        copies.deleteUnplayed();
        assertThat(jdbc.queryForObject("SELECT status FROM media_playout_copies WHERE media_id = ?", String.class, a.getId())).isEqualTo("PENDING");
    }

    @Test
    void profileCopiesAreEncodedAsTheStreamSendsThemAndJoinWithoutReencoding() throws Exception {
        MediaAsset a = upload("copies-profile@example.com");
        // Asked for, not ready yet.
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", -3.2, true)).isNull();
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", -3.2, true)).isNull(); // asked only once
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                Integer.class, a.getId())).isEqualTo(1);
        // A playout copy for the same canvas is a separate thing.
        copies.forget();
        assertThat(copies.keyFor(a.getS3Key(), 180, 320, 30)).isEqualTo(a.getS3Key());
        while (copies.prepareNext()) {
            // make both
        }
        String profile = copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", -3.2, false);
        assertThat(profile).isNotNull().endsWith(".ts");
        copies.forget();
        assertThat(copies.keyFor(a.getS3Key(), 180, 320, 30)).isNotEqualTo(profile).endsWith(".mp4");

        MediaProbe.MediaInfo info = probe.probe(storage.readLocation(profile));
        assertThat(info.width()).isEqualTo(180);
        assertThat(info.height()).isEqualTo(320);
        assertThat(info.fps()).isBetween(29.9, 30.1);
        assertThat(info.hasAudio()).isTrue();

        // Copy mode's core: the same file three times, joined by stream copy, is one clean programme.
        Path list = dir.resolve("list.ffconcat");
        String file = storage.readLocation(profile);
        Files.writeString(list, "ffconcat version 1.0\nfile '" + file + "'\nfile '" + file + "'\nfile '" + file + "'\n");
        Path joined = dir.resolve("joined.ts");
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list.toString(),
                "-map", "0", "-c", "copy", "-f", "mpegts", joined.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        com.afklive.streamer.streaming.TsStitcherTest.assertContinuous(com.afklive.streamer.streaming.TsStitcherTest.probe(joined), 9);
    }

    /** The x264 settings line x264 writes into the first keyframe of a stream. */
    private String x264Settings(String storageKey) throws Exception {
        Path h264 = dir.resolve(UUID.randomUUID() + ".h264");
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", storage.readLocation(storageKey), "-map", "0:v:0",
                "-c", "copy", "-frames:v", "1", "-f", "h264", h264.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(log).isZero();
        String all = new String(Files.readAllBytes(h264), java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = all.indexOf("x264 - core");
        assertThat(at).isNotNegative();
        return all.substring(at, all.indexOf('\0', at));
    }

    @Test
    void profileCopiesAreMadeWithTheRequestingWorkersPresetAndOldOnesOfTheSameFamilyStayInUse() throws Exception {
        MediaAsset a = upload("copies-preset@example.com");
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true)).isNull();
        assertThat(jdbc.queryForObject("SELECT x264_preset FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                String.class, a.getId())).isEqualTo(EncoderSettings.DEFAULT_PRESET);
        while (copies.prepareNext()) {
            // make it
        }
        String made = copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, false);
        assertThat(made).isNotNull();
        assertThat(x264Settings(made)).contains(" subme=1 ").contains(" me=dia "); // superfast's search, not veryfast's
        assertThat(jdbc.queryForObject("SELECT x264_preset FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                String.class, a.getId())).isEqualTo("superfast");

        // A copy made before presets were recorded (veryfast, NULL) is the same family: a superfast worker plays it.
        jdbc.update("UPDATE media_playout_copies SET x264_preset = NULL WHERE media_id = ? AND kind = 'PROFILE'", a.getId());
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true)).isEqualTo(made);
        assertThat(storage.size(made)).isPositive();
    }

    @Test
    void profileCopiesAreKeptPerBitrateSoARunNeverPlaysOneMadeAtAnother() throws Exception {
        // FO-4: a 180x320 30 fps canvas is the 720 class: 4000 kbps built in. One run at that, one at a plan's 1500.
        MediaAsset a = upload("copies-bitrate@example.com");
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true)).isNull();
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, 1500, true)).isNull();
        // The built-in bitrate is stored as 0, like every copy made before bitrates were recorded.
        assertThat(jdbc.queryForList("SELECT video_kbps FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE' ORDER BY video_kbps",
                Integer.class, a.getId())).containsExactly(0, 1500);
        // The previous release asks without a bitrate: it gets the built-in row, never a second one.
        jdbc.update("INSERT INTO media_playout_copies (media_id, kind, canvas_width, canvas_height, fps, fit, gain_tenths, status, x264_preset) "
                + "VALUES (?, 'PROFILE', 180, 320, 30, 'FIT', 0, 'PENDING', 'superfast') ON CONFLICT ON CONSTRAINT uq_media_playout_copies DO NOTHING",
                a.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                Integer.class, a.getId())).isEqualTo(2);
        while (copies.prepareNext()) {
            // make both
        }
        String builtIn = copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, false);
        String low = copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, 1500, false);
        assertThat(builtIn).isNotNull();
        assertThat(low).isNotNull().isNotEqualTo(builtIn);
        // Asking with the built-in value spelled out is the same copy.
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, 4000, false)).isEqualTo(builtIn);
        // Each was encoded at its own bitrate (x264 writes its settings into the stream).
        assertThat(x264Settings(builtIn)).contains(" bitrate=4000 ");
        assertThat(x264Settings(low)).contains(" bitrate=1500 ");
        // A bitrate with no copy yet: nothing to play, and a third row is asked for.
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, 2500, true)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                Integer.class, a.getId())).isEqualTo(3);
    }

    @Test
    void aCopyOfAnotherPresetFamilyIsMadeAgainUnlessAWorkerIsPlayingIt() throws Exception {
        MediaAsset a = upload("copies-family@example.com");
        copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true);
        while (copies.prepareNext()) {
            // a superfast copy
        }
        String superfastCopy = copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, false);
        assertThat(superfastCopy).isNotNull();

        // A worker set to ultrafast (no B-frames, another profile) can't splice that copy into its stream.
        PlayoutCopies ultrafastWorker = new PlayoutCopies(jdbc, storage, "worker", false, new EncoderSettings("ultrafast", 0));
        // Played within the hour (by a superfast worker): left alone.
        assertThat(ultrafastWorker.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                String.class, a.getId())).isEqualTo("READY");
        assertThat(storage.size(superfastCopy)).isPositive();

        // Nobody has played it lately: made again with ultrafast, the old file deleted.
        jdbc.update("UPDATE media_playout_copies SET last_played_at = now() - interval '2 hours' WHERE media_id = ?", a.getId());
        assertThat(ultrafastWorker.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true)).isNull();
        assertThat(jdbc.queryForMap("SELECT status, x264_preset, storage_key FROM media_playout_copies WHERE media_id = ? AND kind = 'PROFILE'",
                a.getId())).containsEntry("status", "PENDING").containsEntry("x264_preset", "ultrafast").containsEntry("storage_key", null);
        assertThatThrownBy(() -> storage.size(superfastCopy)).isInstanceOf(Exception.class);
        // Until it's made, the superfast worker doesn't get it either (it would be the wrong family once made).
        assertThat(copies.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, false)).isNull();
        while (copies.prepareNext()) {
            // the web side makes it as asked, whatever its own setting
        }
        String ultrafastCopy = ultrafastWorker.profileKeyFor(a.getS3Key(), 180, 320, 30, "FIT", 0, true);
        assertThat(ultrafastCopy).isNotNull();
        assertThat(x264Settings(ultrafastCopy)).contains(" cabac=0 ").contains(" bframes=0 ");
    }
}
