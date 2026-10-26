package com.afklive.streamer.streaming;

import com.afklive.streamer.model.StreamCommand;
import com.afklive.streamer.repository.LiveStreamRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.MediaProbe;
import com.afklive.streamer.service.PlayoutCopies;
import com.afklive.streamer.streaming.studio.Canvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FO-2: an overlay shown or hidden live (an OVERLAY command, which the studio keeps over the spec) is part
 * of the run's settings as copy mode reads them, so the copies it asks for carry what's on screen: a logo
 * the creator hid isn't baked into them, and showing it again asks for other copies.
 */
class StudioRunsOverlayVisibilityTest {

    @TempDir
    Path dir;

    @Test
    void aLogoHiddenLiveIsLeftOutOfTheCopiesAndShowingItAgainNeedsOthers() throws Exception {
        Process v = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0, "ffmpeg not installed");
        Path clip = dir.resolve("clip.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-f", "lavfi",
                "-i", "sine=f=440:r=44100", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                clip.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();

        FileStorageService storage = mock(FileStorageService.class);
        doAnswer(inv -> Files.copy(clip, inv.<Path>getArgument(1), StandardCopyOption.REPLACE_EXISTING)).when(storage)
                .downloadFileToPath(anyString(), any(Path.class));
        MediaCache cache = new MediaCache(storage, dir.resolve("cache").toString(), 50);
        StreamPreparer preparer = new StreamPreparer(storage, null, null, null);
        ReflectionTestUtils.setField(preparer, "workDirRoot", dir.resolve("work").toString());
        PlayoutCopies copies = mock(PlayoutCopies.class);
        when(copies.keyFor(anyString(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        StudioRuns runs = new StudioRuns(new StudioBuilder(cache, mock(IngestReader.class), mock(MediaProbe.class), copies,
                EncoderSettings.DEFAULT), mock(LiveStreamRepository.class), mock(StreamJobRepository.class), preparer);

        StudioSpec.OverlaySetting brand = StaticOverlaysTest.text("brand", "Chill Corner");
        StudioSpec studio = new StudioSpec(180, false, 30, "PLAYLIST", List.of(new StudioSpec.Media("1", "media/clip.mp4", "clip", 2, true)),
                "IN_ORDER", 0, List.of(), "IN_ORDER", "KEEP", 1.0, 1.0, List.of(StaticOverlaysTest.watermark(), brand), List.of(),
                StudioSpec.LIVE, "UTC", null, null, false, "FIT", 0).withVideoKbps(2500); // FO-4: the run's bitrate
        StudioRuns.Handle h = runs.start(1L, StreamSpec.studio("o@example.com", null, studio, List.of()), StudioSpec.LIVE, null);
        try {
            Canvas canvas = Canvas.of(180, false, 30);
            String both = StaticOverlays.identity(StaticOverlays.staticSet(runs.spec(h)), canvas);
            String markOnly = StaticOverlays.identity(List.of(StaticOverlaysTest.watermark()), canvas);
            assertThat(both).isNotEqualTo(markOnly);

            runs.apply(h, new StreamCommand(1L, StreamCommand.Type.OVERLAY, "{\"id\":\"brand\",\"visible\":false}"));
            assertThat(StaticOverlays.staticSet(runs.spec(h))).extracting(StudioSpec.OverlaySetting::id)
                    .containsExactly(com.afklive.streamer.streaming.studio.OverlayCompositor.WATERMARK_ID);
            assertThat(StaticOverlays.identity(StaticOverlays.staticSet(runs.spec(h)), canvas)).isEqualTo(markOnly);
            // FO-4: the spec copy mode prepares from keeps the run's bitrate, so it asks for copies at 2500.
            assertThat(runs.spec(h).videoKbps()).isEqualTo(2500);
            assertThat(h.studio().videoKbps()).isEqualTo(2500);

            runs.apply(h, new StreamCommand(1L, StreamCommand.Type.OVERLAY, "{\"id\":\"brand\",\"visible\":true}"));
            assertThat(StaticOverlays.identity(StaticOverlays.staticSet(runs.spec(h)), canvas)).isEqualTo(both);

            // A reload built without a bitrate (or from a new plan) never changes the running stream's.
            runs.apply(h, new StreamCommand(1L, StreamCommand.Type.RELOAD, StudioSpec.json(studio.withVideoKbps(6000))));
            assertThat(runs.spec(h).videoKbps()).isEqualTo(2500);
            runs.apply(h, new StreamCommand(1L, StreamCommand.Type.RELOAD, StudioSpec.json(studio.withVideoKbps(null))));
            assertThat(runs.spec(h).videoKbps()).isEqualTo(2500);
        } finally {
            runs.close(h);
        }
    }
}
