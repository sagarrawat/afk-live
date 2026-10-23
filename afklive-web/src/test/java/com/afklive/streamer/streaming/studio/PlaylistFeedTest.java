package com.afklive.streamer.streaming.studio;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import static com.afklive.streamer.streaming.studio.StudioFixtures.CANVAS;
import static org.assertj.core.api.Assertions.assertThat;

/** Playlist playback with real decoders, driven as fast as the decoders allow (no clock). */
class PlaylistFeedTest {

    @TempDir
    static Path dir;
    static PlaylistFeed.Item a;
    static PlaylistFeed.Item b;
    static PlaylistFeed.Item c;

    @BeforeAll
    static void media() throws Exception {
        StudioFixtures.requireFfmpeg();
        a = new PlaylistFeed.Item("a", "Clip A", StudioFixtures.clip(dir, "a", 320, 240, 2, 440), true, 2);
        b = new PlaylistFeed.Item("b", "Clip B", StudioFixtures.clip(dir, "b", 640, 360, 2, 880), true, 2);
        c = new PlaylistFeed.Item("c", "Clip C", StudioFixtures.clip(dir, "c", 320, 180, 2, 0), false, 2);
    }

    /** Pulls frames until the playlist finishes; returns the item ids in the order they played. */
    private static List<String> drain(PlaylistFeed feed, int maxTicks) throws InterruptedException {
        Frame f = new Frame(CANVAS);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> order = new ArrayList<>();
        long deadline = System.nanoTime() + 60_000_000_000L;
        for (int i = 0; i < maxTicks && !feed.finished() && System.nanoTime() < deadline; i++) {
            feed.next(f);
            PlaylistFeed.NowPlaying np = feed.nowPlaying();
            if (np != null && np.item() != null && (order.isEmpty() || !order.getLast().equals(np.item().id()))) {
                order.add(np.item().id());
                seen.add(np.item().id());
            }
            if (i % 20 == 0) {
                Thread.sleep(1); // let decoder threads run
            }
        }
        return order;
    }

    @Test
    void playsEveryItemInOrderWithMixedSizesAndAMissingAudioTrack() throws Exception {
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, b, c), PlaylistFeed.Order.IN_ORDER, 1,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            List<String> order = drain(feed, 200_000);
            assertThat(feed.finished()).isTrue();
            assertThat(order).containsExactly("a", "b", "c");
            // 3 clips x 2 s at 30 fps: every frame played, none lost at the joins.
            assertThat(feed.framesPlayed()).isBetween(175L, 185L);
        }
    }

    @Test
    void loopsTheRequestedNumberOfTimes() throws Exception {
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, c), PlaylistFeed.Order.IN_ORDER, 2,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            List<String> order = drain(feed, 200_000);
            assertThat(order).containsExactly("a", "c", "a", "c");
            assertThat(feed.framesPlayed()).isBetween(235L, 245L);
        }
    }

    @Test
    void shufflePlaysEachItemOncePerPassAndNeverTheSameTwiceInARow() throws Exception {
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, b, c), PlaylistFeed.Order.SHUFFLE, 3,
                null, StudioFixtures.factory(dir), false, new Random(7))) {
            List<String> order = drain(feed, 400_000);
            assertThat(order).hasSize(9);
            for (int pass = 0; pass < 3; pass++) {
                assertThat(order.subList(pass * 3, pass * 3 + 3)).containsExactlyInAnyOrder("a", "b", "c");
            }
        }
    }

    @Test
    void resumesFromASavedPosition() throws Exception {
        PlaylistFeed.Position at = new PlaylistFeed.Position("b", 1.0, 0);
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, b, c), PlaylistFeed.Order.IN_ORDER, 1,
                at, StudioFixtures.factory(dir), false, new Random(1))) {
            List<String> order = drain(feed, 200_000);
            assertThat(order).containsExactly("b", "c");
            // The rest of b (1 s) then all of c (2 s).
            assertThat(feed.framesPlayed()).isBetween(85L, 95L);
        }
    }

    @Test
    void skipMovesToTheNextItemAndAnEditKeepsTheCurrentOne() throws Exception {
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, b, c), PlaylistFeed.Order.IN_ORDER, 1,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            Frame f = new Frame(CANVAS);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (feed.framesPlayed() < 15 && System.nanoTime() < deadline) {
                feed.next(f);
                Thread.sleep(1);
            }
            feed.skip();
            feed.next(f);
            while ((feed.nowPlaying().item() == null || feed.nowPlaying().item().id().equals("a")) && System.nanoTime() < deadline) {
                feed.next(f);
                Thread.sleep(1);
            }
            assertThat(feed.nowPlaying().item().id()).isEqualTo("b");

            // Remove c and add a again at the end: b keeps playing, then a.
            feed.setItems(List.of(b, a), PlaylistFeed.Order.IN_ORDER, 1);
            List<String> rest = drain(feed, 200_000);
            assertThat(rest).containsExactly("b", "a");
        }
    }

    @Test
    void aBrokenFileIsSkipped() throws Exception {
        Path junk = dir.resolve("junk.mp4");
        java.nio.file.Files.writeString(junk, "not a video");
        PlaylistFeed.Item broken = new PlaylistFeed.Item("x", "Broken", junk, true, 0);
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(broken, c), PlaylistFeed.Order.IN_ORDER, 1,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            List<String> order = drain(feed, 200_000);
            assertThat(order).last().isEqualTo("c");
            assertThat(feed.finished()).isTrue();
            assertThat(feed.framesPlayed()).isBetween(55L, 65L);
        }
    }

    @Test
    void audioOnlyPlaylistMakesMusic() throws Exception {
        PlaylistFeed.Item song = new PlaylistFeed.Item("s", "Song", StudioFixtures.audio(dir, "song", 1.5, 330), true, 1.5);
        try (PlaylistFeed music = new PlaylistFeed(CANVAS, List.of(song), PlaylistFeed.Order.IN_ORDER, 1,
                null, StudioFixtures.factory(dir), true, new Random(1))) {
            drain(music, 100_000);
            assertThat(music.framesPlayed()).isBetween(43L, 47L);
        }
    }

    @Test
    void aFileWhoseAudioOutlastsItsVideoEndsWithTheVideo() throws Exception {
        Path longAudio = dir.resolve("long-audio.mp4");
        StudioFixtures.run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-t", "2", "-i", "testsrc2=size=320x180:rate=25",
                "-f", "lavfi", "-t", "5", "-i", "sine=f=500:r=48000", "-map", "0:v", "-map", "1:a",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", longAudio.toString()));
        PlaylistFeed.Item x = new PlaylistFeed.Item("x", "Long audio", longAudio, true, 0);
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(x, c), PlaylistFeed.Order.IN_ORDER, 1,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            List<String> order = drain(feed, 200_000);
            assertThat(order).containsExactly("x", "c");
            assertThat(feed.finished()).isTrue();
            assertThat(feed.framesPlayed()).isBetween(115L, 125L);
        }
    }

    @Test
    void aFileWhoseVideoOutlastsItsAudioPlaysWithoutWaitingForAudio() throws Exception {
        Path shortAudio = dir.resolve("short-audio.mp4");
        StudioFixtures.run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-t", "4", "-i", "testsrc2=size=320x180:rate=25",
                "-f", "lavfi", "-t", "1", "-i", "sine=f=500:r=48000", "-map", "0:v", "-map", "1:a",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", shortAudio.toString()));
        PlaylistFeed.Item y = new PlaylistFeed.Item("y", "Short audio", shortAudio, true, 4);
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(y), PlaylistFeed.Order.IN_ORDER, 1,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            Frame f = new Frame(CANVAS);
            int slowTicks = 0;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!feed.finished() && System.nanoTime() < deadline) {
                long t = System.nanoTime();
                feed.next(f);
                if (feed.framesPlayed() > 40 && System.nanoTime() - t > 8_000_000L) {
                    slowTicks++;
                }
                Thread.sleep(1);
            }
            assertThat(feed.framesPlayed()).isBetween(115L, 125L);
            assertThat(slowTicks).isLessThan(5);
        }
    }

    @Test
    void aPositionSavedWhileTheNextPassIsPrerolledStillCountsThatPass() throws Exception {
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, c), PlaylistFeed.Order.IN_ORDER, 2,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            Frame f = new Frame(CANVAS);
            long deadline = System.nanoTime() + 30_000_000_000L;
            // Play into c (the last item of pass 1); its preroll starts the next pass immediately (2 s items).
            while ((feed.nowPlaying() == null || feed.nowPlaying().item() == null || !"c".equals(feed.nowPlaying().item().id())
                    || feed.nowPlaying().elapsedSeconds() < 0.5) && System.nanoTime() < deadline) {
                feed.next(f);
                Thread.sleep(1);
            }
            PlaylistFeed.Position saved = feed.position();
            assertThat(saved.itemId()).isEqualTo("c");
            assertThat(saved.loopsCompleted()).isZero();
            try (PlaylistFeed resumed = new PlaylistFeed(CANVAS, List.of(a, c), PlaylistFeed.Order.IN_ORDER, 2,
                    saved, StudioFixtures.factory(dir), false, new Random(1))) {
                assertThat(drain(resumed, 200_000)).containsExactly("c", "a", "c");
            }
        }
    }

    @Test
    void endAfterThisPassFinishesAForeverPlaylistAtTheEndOfTheList() throws Exception {
        try (PlaylistFeed feed = new PlaylistFeed(CANVAS, List.of(a, c), PlaylistFeed.Order.IN_ORDER, 0,
                null, StudioFixtures.factory(dir), false, new Random(1))) {
            Frame f = new Frame(CANVAS);
            while (feed.framesPlayed() < 10) {
                feed.next(f);
                Thread.sleep(1);
            }
            feed.endAfterThisPass();
            assertThat(drain(feed, 200_000)).containsExactly("a", "c");
            assertThat(feed.finished()).isTrue();
        }
    }
}
