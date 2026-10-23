package com.afklive.streamer.streaming.studio;

import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes one input (a file, or a live feed) with FFmpeg into canvas-format raw frames: video on the
 * process's stdout, audio through a named pipe (or stdout, for audio-only sources).
 * <p>
 * Every video frame and every audio chunk (exactly one frame's worth of samples) carries a sequence
 * number, and {@link #poll} pairs them by number, never by queue position. So A/V alignment can't
 * drift whatever gets dropped: audio for a frame that was skipped is discarded with it, and a frame
 * whose audio was lost plays with silence.
 * <p>
 * File sources are pulled: the decoder runs a few frames ahead and then waits. When a file's audio
 * outlasts its video, the surplus is discarded so the decoder can finish. Live sources are pushed:
 * the decoder runs in real time, {@link #poll} skips ahead when frames pile up (keeping the delay
 * low), and a decoder that exits (the phone disconnected) is restarted until the source is closed.
 */
@Slf4j
public final class DecoderSource implements AutoCloseable {

    public enum State { STARTING, PLAYING, ENDED, FAILED }

    /**
     * @param input        FFmpeg input arguments, ending with {@code -i <url or path>}
     * @param video        decode video (else audio only)
     * @param audio        decode audio (else the source is silent)
     * @param live         real-time source: low-latency buffering, restart on exit
     * @param startSeconds start offset for files (resume, or seek)
     */
    public record Options(List<String> input, boolean video, boolean audio, boolean live, double startSeconds) {
        public static Options file(Path path, boolean hasAudio, double startSeconds) {
            return new Options(List.of("-i", path.toString()), true, hasAudio, false, startSeconds);
        }

        public static Options audioFile(Path path, double startSeconds) {
            return new Options(List.of("-i", path.toString()), false, true, false, startSeconds);
        }

        public static Options live(String url) {
            return new Options(List.of("-i", url), true, true, true, 0);
        }
    }

    /** Produces FFmpeg input arguments; may block (runs on the decoder's own thread). */
    @FunctionalInterface
    public interface InputResolver {
        Resolved input() throws IOException;
    }

    /**
     * Input arguments, plus facts only known once the file is here: whether it has audio and its
     * duration in seconds (null: unknown / as configured).
     */
    public record Resolved(List<String> input, Boolean hasAudio, Double durationSeconds) {
        public Resolved(List<String> input, Boolean hasAudio) {
            this(input, hasAudio, null);
        }
    }

    /** Frames buffered by a file source: covers a decoder hiccup while keeping 1080p memory bounded (~18 MB). */
    static final int FILE_BUFFER_FRAMES = 6;
    /** Live buffer limit; {@link #poll} trims back to {@link #LIVE_TARGET_FRAMES} when it's exceeded. */
    static final int LIVE_BUFFER_FRAMES = 12;
    static final int LIVE_TARGET_FRAMES = 3;
    static final long LIVE_RETRY_MILLIS = 250;

    /** A frame's worth of video or audio with its position in the run. */
    private record Chunk(long seq, byte[] data) {
    }

    private final Canvas canvas;
    private final Options options;
    private final Path workDir;
    private final String ffmpeg;
    private final String name;

    private final BlockingQueue<byte[]> videoFree;
    private final BlockingQueue<Chunk> videoFilled;
    private final BlockingQueue<Chunk> audioFilled;

    private volatile boolean closed;
    private volatile Process process;
    /** The supervisor has finished for good (file decoded and drained, or source closed). */
    private volatile boolean decoderDone;
    private volatile boolean processExited;
    /** This run's video reached its end: no more frames will come. */
    private volatile boolean videoEnded;
    private volatile long lastVideoSeq = -1;
    /** This run's audio reached its end (or the input has none): never wait for more. */
    private volatile boolean audioEnded;
    private volatile int exitCode = -1;
    private volatile String lastError;
    private volatile Double knownDuration;
    private final AtomicLong framesOut = new AtomicLong();
    private volatile long lastFrameNanos;
    private volatile long startedNanos;
    /** Sequence numbers keep increasing across live restarts, so old and new runs never pair up. */
    private long generationBase;
    private Thread supervisor;
    private volatile InputResolver resolver;
    private volatile Runnable onClose;

    public DecoderSource(Canvas canvas, Options options, Path workDir, String ffmpeg, String name) {
        this.canvas = canvas;
        this.options = options;
        this.workDir = workDir;
        this.ffmpeg = ffmpeg;
        this.name = name;
        int capacity = options.live() ? LIVE_BUFFER_FRAMES : FILE_BUFFER_FRAMES;
        if (options.video()) {
            this.videoFree = new ArrayBlockingQueue<>(capacity + 1);
            this.videoFilled = new ArrayBlockingQueue<>(capacity + 1);
            for (int i = 0; i < capacity + 1; i++) {
                videoFree.add(new byte[canvas.frameBytes()]);
            }
        } else {
            this.videoFree = null;
            this.videoFilled = null;
        }
        // Audio chunks are small (6 KB); room for a few seconds so audio never stalls the decoder first.
        this.audioFilled = options.audio() ? new ArrayBlockingQueue<>(capacity * 8 + 32) : null;
    }

    /** Resolve the input lazily, on the decoder thread, right before each start. */
    public DecoderSource resolveInputWith(InputResolver r) {
        this.resolver = r;
        return this;
    }

    /** Runs once when the source is closed (e.g. to release a cached file). */
    public DecoderSource whenClosed(Runnable r) {
        this.onClose = r;
        return this;
    }

    public synchronized DecoderSource start() {
        if (supervisor == null) {
            startedNanos = System.nanoTime();
            supervisor = Thread.ofPlatform().daemon(true).name("decoder-" + name).start(this::supervise);
        }
        return this;
    }

    public String name() {
        return name;
    }

    public boolean isLive() {
        return options.live();
    }

    public State state() {
        if (options.live()) {
            return framesOut.get() > 0 ? State.PLAYING : State.STARTING;
        }
        boolean ended = options.video() ? videoEnded && videoFilled.isEmpty() : audioEnded && audioFilled.isEmpty();
        if (ended || decoderDone) {
            if (framesOut.get() > 0) {
                return ended || isDrained() ? State.ENDED : State.PLAYING;
            }
            // Nothing played: a broken file, or one with no frames. Wait for the exit code to say which.
            if (!decoderDone) {
                return State.STARTING;
            }
            return exitCode == 0 ? State.ENDED : State.FAILED;
        }
        return framesOut.get() > 0 ? State.PLAYING : State.STARTING;
    }

    /** True once the input has been read to its end (its last frames may still be queued): time to preroll. */
    public boolean decoderFinished() {
        return (options.video() ? videoEnded : audioEnded) || processExited || decoderDone;
    }

    /** The input's duration if the resolver found it out, else null. */
    public Double knownDurationSeconds() {
        return knownDuration;
    }

    public String lastError() {
        return lastError;
    }

    public long framesOut() {
        return framesOut.get();
    }

    /** Milliseconds since the last frame came out of this source, or since it started if none has. */
    public long millisSinceLastFrame() {
        long last = lastFrameNanos != 0 ? lastFrameNanos : startedNanos;
        return last == 0 ? 0 : (System.nanoTime() - last) / 1_000_000;
    }

    private boolean isDrained() {
        return (videoFilled == null || videoFilled.isEmpty()) && (audioFilled == null || options.video() || audioFilled.isEmpty());
    }

    public int bufferedFrames() {
        return options.video() ? videoFilled.size() : audioFilled.size();
    }

    /**
     * Takes the next frame into {@code out} if one is ready. Video-less sources leave the picture
     * alone; silent sources write silence. Returns false when nothing is ready (nothing is changed).
     */
    public boolean poll(Frame out) {
        if (options.video()) {
            if (options.live()) {
                // Keep the delay low: if frames piled up (our clock ran slow, or a burst arrived), skip ahead.
                while (videoFilled.size() > LIVE_TARGET_FRAMES + 2) {
                    Chunk stale = videoFilled.poll();
                    if (stale != null) {
                        videoFree.offer(stale.data());
                    }
                }
            }
            Chunk v = videoFilled.poll();
            if (v == null) {
                return false;
            }
            System.arraycopy(v.data(), 0, out.video, 0, out.video.length);
            videoFree.offer(v.data());
            pairAudio(out, v.seq());
        } else {
            Chunk a = audioFilled.poll();
            if (a == null) {
                return false;
            }
            System.arraycopy(a.data(), 0, out.audio, 0, out.audio.length);
        }
        framesOut.incrementAndGet();
        lastFrameNanos = System.nanoTime();
        return true;
    }

    /** Fills {@code out.audio} with the chunk numbered {@code seq}, discarding older ones; silence if it's missing. */
    private void pairAudio(Frame out, long seq) {
        if (audioFilled == null) {
            out.silence();
            return;
        }
        long waitUntil = 0;
        while (true) {
            Chunk a = audioFilled.peek();
            if (a == null) {
                // For files, audio is decoded alongside video and is rarely late; wait a moment unless it has ended.
                if (options.live() || audioEnded) {
                    break;
                }
                long now = System.nanoTime();
                if (waitUntil == 0) {
                    waitUntil = now + 5_000_000L;
                }
                if (now >= waitUntil) {
                    break;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(250_000L);
                continue;
            }
            if (a.seq() < seq) {
                audioFilled.poll(); // audio for a frame that was skipped
                continue;
            }
            if (a.seq() == seq) {
                audioFilled.poll();
                System.arraycopy(a.data(), 0, out.audio, 0, out.audio.length);
                return;
            }
            break; // the next chunk is for a later frame: this frame's audio was lost
        }
        out.silence();
    }

    /** Test hook: queue a chunk as if the decoder had produced it (no process involved). */
    void inject(boolean video, long seq, byte[] data) {
        if (video) {
            byte[] buf = videoFree.poll();
            System.arraycopy(data, 0, buf, 0, Math.min(data.length, buf.length));
            videoFilled.offer(new Chunk(seq, buf));
        } else {
            audioFilled.offer(new Chunk(seq, data));
        }
    }

    // ---------------------------------------------------------------- decoder process

    private void supervise() {
        try {
            do {
                runOnce();
                if (!options.live() || closed) {
                    break;
                }
                sleepQuietly(LIVE_RETRY_MILLIS);
            } while (!closed);
        } finally {
            decoderDone = true;
        }
    }

    private void runOnce() {
        Path fifo = null;
        List<Thread> readers = new ArrayList<>();
        long base = generationBase;
        generationBase += 1L << 40;
        videoEnded = false;
        audioEnded = false;
        lastVideoSeq = -1;
        try {
            InputResolver r = resolver;
            Resolved resolved = r != null ? r.input() : new Resolved(options.input(), null);
            if (resolved.durationSeconds() != null && resolved.durationSeconds() > 0) {
                knownDuration = resolved.durationSeconds();
            }
            boolean audio = options.audio() && !Boolean.FALSE.equals(resolved.hasAudio());
            if (!audio) {
                audioEnded = true;
            }
            if (closed) {
                return;
            }
            if (options.video() && audio) {
                fifo = workDir.resolve("a-" + UUID.randomUUID().toString().substring(0, 8) + ".pcm");
                Files.createDirectories(workDir);
                Fifos.create(fifo);
            }
            Process p = new ProcessBuilder(command(fifo, resolved.input(), audio)).start();
            process = p;
            if (closed) {
                p.destroyForcibly();
            }
            Deque<String> errors = new ArrayDeque<>();
            Thread err = Thread.ofPlatform().daemon(true).name("decoder-err-" + name).start(() -> readErrors(p.getErrorStream(), errors));
            if (options.video()) {
                readers.add(Thread.ofPlatform().daemon(true).name("decoder-v-" + name)
                        .start(() -> readChunks(p.getInputStream(), canvas.frameBytes(), true, base)));
                if (audio) {
                    Path audioPipe = fifo;
                    readers.add(Thread.ofPlatform().daemon(true).name("decoder-a-" + name).start(() -> readFifo(audioPipe, p, base)));
                }
            } else {
                readers.add(Thread.ofPlatform().daemon(true).name("decoder-a-" + name)
                        .start(() -> readChunks(p.getInputStream(), canvas.audioBytesPerFrame(), false, base)));
            }
            int code = p.waitFor();
            if (!options.live()) {
                processExited = true;
            }
            for (Thread t : readers) {
                // Readers finish once the consumer has taken (or discarded) what's left. A reader still opening
                // the audio pipe of a decoder that died early is released by opening the other end ourselves.
                while (t.isAlive()) {
                    if (fifo != null) {
                        Fifos.release(fifo);
                    }
                    t.join(200);
                }
            }
            err.join(1000);
            exitCode = code;
            synchronized (errors) {
                if (!errors.isEmpty()) {
                    lastError = String.join(" | ", errors);
                }
            }
            if (code != 0 && !closed) {
                log.debug("Decoder {} exited with {}: {}", name, code, lastError);
            }
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage();
            log.warn("Decoder {} could not start: {}", name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            videoEnded = true;
            audioEnded = true;
            if (fifo != null) {
                try {
                    Files.deleteIfExists(fifo);
                } catch (IOException ignored) {
                    // temp pipe
                }
            }
        }
    }

    public List<String> command(Path audioFifo) {
        return command(audioFifo, options.input(), options.audio());
    }

    List<String> command(Path audioFifo, List<String> input, boolean audio) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.addAll(List.of("-hide_banner", "-nostdin", "-loglevel", "error"));
        if (options.live()) {
            // Start fast: each reconnect of the phone is a new decoder run, so its timestamps are continuous.
            cmd.addAll(List.of("-fflags", "nobuffer", "-analyzeduration", "500000", "-probesize", "500000"));
        } else if (options.startSeconds() > 0) {
            cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", options.startSeconds())));
        }
        cmd.addAll(input);
        // Files start both streams at 0 so a late first audio packet becomes silence rather than drift.
        String startAt = options.live() ? "" : ":first_pts=0";
        String audioChain = "aresample=" + canvas.sampleRate() + ":async=1000" + startAt + ","
                + "aformat=sample_fmts=s16:channel_layouts=stereo";
        if (options.video()) {
            StringBuilder graph = new StringBuilder();
            graph.append("[0:v:0]scale=").append(canvas.width()).append(':').append(canvas.height())
                    .append(":force_original_aspect_ratio=decrease,pad=").append(canvas.width()).append(':').append(canvas.height())
                    .append(":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=").append(canvas.fps())
                    .append(options.live() ? "" : ":start_time=0").append(",format=yuv420p[v]");
            if (audio) {
                graph.append(";[0:a:0]").append(audioChain).append("[a]");
            }
            cmd.addAll(List.of("-filter_complex", graph.toString()));
            cmd.addAll(List.of("-map", "[v]", "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1"));
            if (audio) {
                cmd.addAll(List.of("-map", "[a]", "-f", "s16le", "-ar", String.valueOf(canvas.sampleRate()), "-ac", "2",
                        "-y", audioFifo.toString()));
            }
        } else {
            cmd.addAll(List.of("-vn", "-af", audioChain, "-f", "s16le", "-ar", String.valueOf(canvas.sampleRate()), "-ac", "2", "pipe:1"));
        }
        return cmd;
    }

    private void readFifo(Path fifo, Process p, long base) {
        try (InputStream in = Files.newInputStream(fifo)) {
            readChunks(in, canvas.audioBytesPerFrame(), false, base);
        } catch (IOException e) {
            if (!closed && p.isAlive()) {
                log.debug("Audio pipe for {} failed: {}", name, e.getMessage());
            }
        }
    }

    private void readChunks(InputStream in, int size, boolean video, long base) {
        long seq = base;
        try (in) {
            while (!closed) {
                byte[] buf = video ? videoFree.poll(200, TimeUnit.MILLISECONDS) : new byte[size];
                if (buf == null) {
                    continue; // consumer hasn't freed a buffer yet (paused file source)
                }
                if (!readFully(in, buf, size)) {
                    if (video) {
                        videoFree.offer(buf);
                    }
                    return;
                }
                Chunk chunk = new Chunk(seq, buf);
                if (video) {
                    lastVideoSeq = seq;
                }
                seq++;
                BlockingQueue<Chunk> target = video ? videoFilled : audioFilled;
                if (options.live()) {
                    while (!target.offer(chunk)) {
                        Chunk dropped = target.poll(); // never block a live decoder: drop the oldest
                        if (video && dropped != null) {
                            videoFree.offer(dropped.data());
                        }
                    }
                } else {
                    while (!closed && !target.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
                        if (!video && options.video() && videoEnded && chunk.seq() > lastVideoSeq) {
                            break; // audio past the end of the video: nobody will play it, let the decoder finish
                        }
                    }
                }
            }
        } catch (IOException e) {
            // decoder ended
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (video) {
                videoEnded = true;
            } else {
                audioEnded = true;
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int size) throws IOException {
        int off = 0;
        while (off < size) {
            int n = in.read(buf, off, size - off);
            if (n < 0) {
                if (off > 0) {
                    throw new EOFException("partial frame");
                }
                return false;
            }
            off += n;
        }
        return true;
    }

    private static void readErrors(InputStream err, Deque<String> errors) {
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(err, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    synchronized (errors) {
                        errors.addLast(line.length() > 300 ? line.substring(0, 300) : line);
                        while (errors.size() > 3) {
                            errors.removeFirst();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Runnable done = onClose;
        if (done != null) {
            try {
                done.run();
            } catch (RuntimeException e) {
                log.debug("onClose for {} failed", name, e);
            }
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            p.onExit().orTimeout(3, TimeUnit.SECONDS).exceptionally(t -> {
                p.destroyForcibly();
                return p;
            });
        }
        if (videoFilled != null) {
            videoFilled.clear();
        }
        if (audioFilled != null) {
            audioFilled.clear();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "DecoderSource[" + name + "]";
    }

    /** Named pipes. */
    static final class Fifos {
        private Fifos() {
        }

        static void create(Path path) throws IOException {
            try {
                Process p = new ProcessBuilder("mkfifo", "-m", "600", path.toString()).redirectErrorStream(true).start();
                if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new IOException("mkfifo failed for " + path);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted creating " + path, e);
            }
        }

        /**
         * Unblocks a thread stuck opening {@code path} for reading or writing: opening a FIFO read-write
         * never blocks on Linux and satisfies the other side's open; closing it then gives EOF / EPIPE.
         */
        static void release(Path path) {
            if (!Files.exists(path)) {
                return;
            }
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                raf.getFD(); // opened and closed
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
