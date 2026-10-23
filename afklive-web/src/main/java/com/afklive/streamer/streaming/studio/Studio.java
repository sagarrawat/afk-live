package com.afklive.streamer.streaming.studio;

import com.afklive.streamer.service.FFmpegCommandBuilder;
import com.afklive.streamer.streaming.StreamRunner;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The cloud studio for one stream: a real-time clock that, 30 (or 60) times a second, takes a frame
 * from the active scene, mixes its audio with the scene's music, draws the overlays, and hands the
 * result to one long-lived encoder as raw video (stdin) and PCM (a named pipe).
 * <p>
 * Everything a creator changes while live (scene, overlays, playlist, skip) happens here, between
 * two frames, so the encoder and the connections to YouTube and other platforms are never restarted
 * for it. Timestamps come from the frame count, so audio and video stay in step for as long as the
 * stream runs. If the encoder itself crashes, the runner relaunches it and the studio carries on
 * feeding the new one from where it was.
 */
@Slf4j
public final class Studio implements StreamRunner.EncoderLauncher, AutoCloseable {

    /**
     * A scene: what's on screen and what's heard.
     *
     * @param fallbackScene for a scene whose video is a {@link LiveFeed}: the scene whose music and
     *                      overlays apply while the live input is down (its video is the feed's fallback)
     */
    public record Scene(String name, Feed video, Feed music, double videoVolume, double musicVolume,
                        List<Overlay> overlays, String fallbackScene) {
        public Scene {
            overlays = overlays == null ? List.of() : List.copyOf(overlays);
        }
    }

    /** What the studio is doing, for the health panel and the remote. */
    public record Status(String scene, Boolean inputLive, PlaylistFeed.NowPlaying nowPlaying,
                         long framesOut, long framesDropped, boolean finished) {
    }

    public interface Listener {
        default void onInputSwitch(boolean live) {
        }

        default void onFinished(String reason) {
        }
    }

    /** Frames held between the clock and the encoder; beyond this the encoder is too slow and frames are dropped. */
    static final int OUTPUT_QUEUE_FRAMES = 4;

    private final Canvas canvas;
    private final Path workDir;
    private final String ffmpeg;
    private final Listener listener;
    private final long endAfterInputLostMillis;
    private final OverlayCompositor overlays;
    private final Map<String, Scene> scenes = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Frame base;
    private final Frame music;

    private volatile String activeScene;
    private volatile String shownScene;
    private volatile List<Overlay> globalOverlays = List.of();
    private final Map<String, Boolean> visibility = new HashMap<>();
    private volatile boolean closed;
    private volatile boolean finished;
    private volatile String finishReason;
    private volatile Output output;
    private final AtomicLong framesOut = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private Thread clock;
    private Boolean lastInputLive;

    /** Output side of one encoder run. */
    private final class Output {
        final Process process;
        final Path audioFifo;
        final BlockingQueue<byte[]> freeVideo = new ArrayBlockingQueue<>(OUTPUT_QUEUE_FRAMES + 1);
        final BlockingQueue<byte[]> video = new ArrayBlockingQueue<>(OUTPUT_QUEUE_FRAMES);
        final BlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(OUTPUT_QUEUE_FRAMES * 75);
        volatile boolean dead;
        volatile boolean ending;
        Thread videoWriter;
        Thread audioWriter;

        Output(Process process, Path audioFifo) {
            this.process = process;
            this.audioFifo = audioFifo;
            for (int i = 0; i < OUTPUT_QUEUE_FRAMES + 1; i++) {
                freeVideo.add(new byte[canvas.frameBytes()]);
            }
        }

        void start() {
            videoWriter = Thread.ofPlatform().daemon(true).name("studio-video-out").start(this::writeVideo);
            audioWriter = Thread.ofPlatform().daemon(true).name("studio-audio-out").start(this::writeAudio);
        }

        /** Queues one frame; returns false (and drops it) if the encoder isn't keeping up. */
        boolean offer(Frame f) {
            if (dead || ending) {
                return false;
            }
            byte[] v = freeVideo.poll();
            if (v == null || audio.remainingCapacity() == 0) {
                if (v != null) {
                    freeVideo.offer(v);
                }
                return false;
            }
            System.arraycopy(f.video, 0, v, 0, v.length);
            audio.offer(f.audio.clone());
            video.offer(v);
            return true;
        }

        /** No more frames: let the encoder finish what it has and exit normally. */
        void end() {
            ending = true;
        }

        private void writeVideo() {
            try (OutputStream out = process.getOutputStream()) {
                while (!dead) {
                    byte[] v = video.poll(100, TimeUnit.MILLISECONDS);
                    if (v == null) {
                        if (ending) {
                            break;
                        }
                        continue;
                    }
                    out.write(v);
                    freeVideo.offer(v);
                }
            } catch (IOException e) {
                dead = true; // encoder gone
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void writeAudio() {
            // Opening a FIFO blocks until the encoder opens its end, which it does after reading some video.
            try (OutputStream out = Files.newOutputStream(audioFifo)) {
                while (!dead) {
                    byte[] a = audio.poll(100, TimeUnit.MILLISECONDS);
                    if (a == null) {
                        if (ending) {
                            // Close audio as soon as it's all written, without waiting for video: the encoder
                            // may be waiting to complete its last audio packet before it reads more video.
                            break;
                        }
                        continue;
                    }
                    out.write(a);
                }
            } catch (IOException e) {
                dead = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            dead = true;
            DecoderSource.Fifos.release(audioFifo); // unblock a writer still waiting to open the pipe
            joinQuietly(videoWriter);
            joinQuietly(audioWriter);
            try {
                Files.deleteIfExists(audioFifo);
            } catch (IOException ignored) {
                // temp pipe
            }
        }
    }

    /**
     * @param endAfterInputLostMillis end the stream when a live input has been down this long (0 = never)
     */
    public Studio(Canvas canvas, Path workDir, String ffmpeg, List<Scene> sceneList, String initialScene,
                  List<Overlay> global, long endAfterInputLostMillis, Listener listener) {
        this.canvas = canvas;
        this.workDir = workDir;
        this.ffmpeg = ffmpeg;
        this.listener = listener == null ? new Listener() { } : listener;
        this.endAfterInputLostMillis = endAfterInputLostMillis;
        this.overlays = new OverlayCompositor(canvas);
        for (Scene s : sceneList) {
            scenes.put(s.name(), s);
        }
        if (!scenes.containsKey(initialScene)) {
            throw new IllegalArgumentException("Unknown scene " + initialScene);
        }
        this.activeScene = initialScene;
        this.globalOverlays = global == null ? List.of() : List.copyOf(global);
        this.base = new Frame(canvas);
        this.music = new Frame(canvas);
        Frame.fill(base.video, canvas, 0x000000);
    }

    public Canvas canvas() {
        return canvas;
    }

    public synchronized void start() {
        if (clock == null) {
            clock = Thread.ofPlatform().daemon(true).name("studio-clock").priority(Thread.MAX_PRIORITY).start(this::runClock);
        }
    }

    // ---------------------------------------------------------------- control (any thread; applied between frames)

    public void switchScene(String name) {
        commands.add(() -> {
            if (scenes.containsKey(name)) {
                activeScene = name;
            }
        });
    }

    public void setOverlayVisible(String overlayId, boolean visible) {
        commands.add(() -> visibility.put(overlayId, visible));
    }

    public void setGlobalOverlays(List<Overlay> list) {
        List<Overlay> copy = List.copyOf(list);
        commands.add(() -> globalOverlays = copy);
    }

    /** Replaces a scene (its old feeds are closed unless the new scene reuses them). */
    public void putScene(Scene scene) {
        commands.add(() -> {
            Scene old = scenes.put(scene.name(), scene);
            if (old != null) {
                if (old.video() != scene.video() && !usedElsewhere(old.video())) {
                    old.video().close();
                }
                if (old.music() != null && old.music() != scene.music() && !usedElsewhere(old.music())) {
                    old.music().close();
                }
            }
        });
    }

    public void skip() {
        commands.add(() -> {
            if (scenes.get(activeScene).video() instanceof PlaylistFeed p) {
                p.skip();
            }
        });
    }

    public Scene scene(String name) {
        return scenes.get(name);
    }

    public Status status() {
        Scene s = scenes.get(activeScene);
        Boolean inputLive = s != null && s.video() instanceof LiveFeed lf ? lf.onLive() : null;
        PlaylistFeed.NowPlaying np = null;
        if (s != null && s.video() instanceof PlaylistFeed p) {
            np = p.nowPlaying();
        }
        return new Status(shownScene != null ? shownScene : activeScene, inputLive, np, framesOut.get(), framesDropped.get(), finished);
    }

    // ---------------------------------------------------------------- encoder (StreamRunner.EncoderLauncher)

    @Override
    public String finishReason() {
        return finishReason;
    }

    @Override
    public synchronized Process launch() throws IOException {
        Files.createDirectories(workDir);
        Path fifo = workDir.resolve("studio-" + UUID.randomUUID().toString().substring(0, 8) + ".pcm");
        DecoderSource.Fifos.create(fifo);
        List<String> cmd = encoderCommand(fifo);
        Process p = new ProcessBuilder(cmd).start();
        Output o = new Output(p, fifo);
        o.start();
        output = o;
        if (finished) {
            o.end();
        }
        return p;
    }

    @Override
    public synchronized void ended(Process process) {
        Output o = output;
        if (o != null && o.process == process) {
            output = null;
            o.release();
        }
    }

    List<String> encoderCommand(Path audioFifo) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-progress", "pipe:2", "-stats_period", "2",
                // Raw inputs with known formats: no probing, or the encoder would wait for seconds of
                // one input while we wait for it to read the other.
                "-thread_queue_size", "8", "-probesize", "32", "-analyzeduration", "0",
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", canvas.width() + "x" + canvas.height(),
                "-framerate", String.valueOf(canvas.fps()), "-i", "pipe:0",
                "-thread_queue_size", "4096", "-probesize", "32", "-analyzeduration", "0",
                "-f", "s16le", "-ar", String.valueOf(canvas.sampleRate()), "-ac", "2", "-i", audioFifo.toString(),
                "-map", "0:v", "-map", "1:a"));
        cmd.addAll(FFmpegCommandBuilder.liveEncodeArgs(Math.min(canvas.width(), canvas.height()), canvas.fps()));
        cmd.addAll(List.of("-f", "mpegts", "-mpegts_flags", "+resend_headers", "-muxdelay", "0", "pipe:1"));
        return cmd;
    }

    // ---------------------------------------------------------------- clock

    private void runClock() {
        // Don't consume anything until the first encoder is attached: nothing played may be lost.
        while (!closed && output == null) {
            LockSupport.parkNanos(5_000_000L);
        }
        long frameNanos = canvas.frameNanos();
        long t0 = System.nanoTime();
        long n = 0;
        while (!closed) {
            long due = t0 + n * frameNanos;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                continue;
            }
            if (-wait > 1_000_000_000L) {
                // More than a second behind (machine overloaded or paused): carry on from now, don't burst.
                t0 = System.nanoTime() - n * frameNanos;
            }
            try {
                tick(n);
            } catch (Exception e) {
                log.error("Studio frame {} failed", n, e);
            }
            n++;
            if (finished) {
                Output o = output;
                if (o != null) {
                    o.end();
                }
                break;
            }
        }
    }

    private void tick(long n) {
        Runnable c;
        while ((c = commands.poll()) != null) {
            c.run();
        }
        Scene scene = scenes.get(activeScene);
        scene.video().next(base);

        Scene shown = scene;
        if (scene.video() instanceof LiveFeed lf) {
            boolean live = lf.onLive();
            if (lastInputLive == null || lastInputLive != live) {
                lastInputLive = live;
                safely(() -> listener.onInputSwitch(live));
            }
            if (!live && scene.fallbackScene() != null && scenes.containsKey(scene.fallbackScene())) {
                shown = scenes.get(scene.fallbackScene());
            }
            if (!live && endAfterInputLostMillis > 0 && lf.millisOnFallback() >= endAfterInputLostMillis) {
                finish("Phone input lost for too long");
            }
        }
        shownScene = shown.name();

        // Audio: the picture's own sound at its volume, plus the scene's music.
        Yuv.gain(base.audio, shown.videoVolume());
        if (shown.music() != null) {
            shown.music().next(music);
            Yuv.mix(base.audio, 1.0, music.audio, shown.musicVolume());
        }
        if (scene.video().finished()) {
            finish("Playlist finished");
        }

        Output o = output;
        if (o == null) {
            framesDropped.incrementAndGet(); // between encoder runs: keep time moving, nothing to send to
            return;
        }
        overlays.setOverlays(effectiveOverlays(shown));
        Frame frame = base;
        if (!overlays.overlays().isEmpty()) {
            // Draw on a copy: the base picture is held over to the next tick when a source has no new frame.
            frame = outFrame();
            frame.copyFrom(base);
            overlays.blend(frame.video, n, Instant.now(), vars(scene));
        }
        if (o.offer(frame)) {
            framesOut.incrementAndGet();
        } else {
            framesDropped.incrementAndGet();
        }
    }

    private Frame scratch;

    private Frame outFrame() {
        if (scratch == null) {
            scratch = new Frame(canvas);
        }
        return scratch;
    }

    private List<Overlay> effectiveOverlays(Scene shown) {
        List<Overlay> list = new ArrayList<>(globalOverlays.size() + shown.overlays().size());
        for (Overlay o : globalOverlays) {
            list.add(applyVisibility(o));
        }
        for (Overlay o : shown.overlays()) {
            list.add(applyVisibility(o));
        }
        return list;
    }

    private Overlay applyVisibility(Overlay o) {
        Boolean v = visibility.get(o.id());
        return v == null || v == o.visible() ? o : o.withVisible(v);
    }

    private static Map<String, String> vars(Scene scene) {
        if (scene.video() instanceof PlaylistFeed p && p.nowPlaying() != null) {
            PlaylistFeed.NowPlaying np = p.nowPlaying();
            Map<String, String> m = new HashMap<>();
            m.put("title", np.item() == null ? "" : np.item().title());
            m.put("next", np.upNext().isEmpty() ? "" : np.upNext().get(0).title());
            return m;
        }
        return Map.of();
    }

    private void finish(String reason) {
        if (!finished) {
            finishReason = reason;
            finished = true;
            log.info("Studio finished: {}", reason);
            safely(() -> listener.onFinished(reason));
        }
    }

    private boolean usedElsewhere(Feed feed) {
        for (Scene s : scenes.values()) {
            if (s.video() == feed || s.music() == feed) {
                return true;
            }
            if (s.video() instanceof LiveFeed) {
                // A live scene's fallback is another scene's video: keep it while that scene exists.
                Scene fb = scenes.get(s.fallbackScene());
                if (fb != null && fb.video() == feed) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Studio listener failed", e);
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread t = clock;
        if (t != null) {
            joinQuietly(t);
        }
        Output o = output;
        if (o != null) {
            o.release();
        }
        java.util.Set<Feed> feeds = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Scene s : scenes.values()) {
            feeds.add(s.video());
            if (s.music() != null) {
                feeds.add(s.music());
            }
        }
        for (Feed f : feeds) {
            try {
                f.close();
            } catch (Exception e) {
                log.debug("Closing feed failed", e);
            }
        }
    }
}
