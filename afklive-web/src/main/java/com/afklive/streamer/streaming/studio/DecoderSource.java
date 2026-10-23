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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes one input (a file, or a live feed) with FFmpeg into canvas-format raw frames: video on the
 * process's stdout, audio through a named pipe (or stdout, for audio-only sources).
 * <p>
 * File sources are pulled: the decoder runs ahead by a few frames and then waits. Live sources are
 * pushed: the decoder runs in real time, and {@link #poll} drops the oldest frames when the buffer
 * grows, which keeps the delay low. A live decoder that exits (the phone disconnected) is restarted
 * every second until the source is closed.
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

    /** Frames buffered by a file source: enough to cover a decoder hiccup, small enough to keep 1080p memory bounded. */
    static final int FILE_BUFFER_FRAMES = 8;
    /** Live buffer limit; {@link #poll} trims back to {@link #LIVE_TARGET_FRAMES} when it's exceeded. */
    static final int LIVE_BUFFER_FRAMES = 15;
    static final int LIVE_TARGET_FRAMES = 3;
    static final long LIVE_RETRY_MILLIS = 1000;

    private final Canvas canvas;
    private final Options options;
    private final Path workDir;
    private final String ffmpeg;
    private final String name;

    private final int capacity;
    private final BlockingQueue<byte[]> videoFree;
    private final BlockingQueue<byte[]> videoFilled;
    private final BlockingQueue<byte[]> audioFilled;

    private volatile boolean closed;
    private volatile Process process;
    private volatile boolean decoderDone;
    private volatile boolean processExited;
    private volatile int exitCode = -1;
    private volatile String lastError;
    private final AtomicLong framesOut = new AtomicLong();
    private volatile long lastFrameNanos;
    private volatile long startedNanos;
    private Thread supervisor;

    public DecoderSource(Canvas canvas, Options options, Path workDir, String ffmpeg, String name) {
        this.canvas = canvas;
        this.options = options;
        this.workDir = workDir;
        this.ffmpeg = ffmpeg;
        this.name = name;
        this.capacity = options.live() ? LIVE_BUFFER_FRAMES : FILE_BUFFER_FRAMES;
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
        // Audio chunks are small; allow more of them so the audio reader never stalls the decoder first.
        this.audioFilled = options.audio() ? new ArrayBlockingQueue<>(capacity * 4) : null;
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
        if (decoderDone && isDrained()) {
            return framesOut.get() == 0 && exitCode != 0 ? State.FAILED : State.ENDED;
        }
        return framesOut.get() > 0 ? State.PLAYING : State.STARTING;
    }

    /** True once the decoder process has exited: a file has been read to the end (its last frames may still be queued). */
    public boolean decoderFinished() {
        return processExited || decoderDone;
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
        boolean videoEmpty = videoFilled == null || videoFilled.isEmpty();
        boolean audioEmpty = audioFilled == null || audioFilled.isEmpty() || options.video();
        return videoEmpty && audioEmpty;
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
                    byte[] stale = videoFilled.poll();
                    if (stale != null) {
                        videoFree.offer(stale);
                    }
                    if (audioFilled != null) {
                        audioFilled.poll();
                    }
                }
            }
            byte[] v = videoFilled.poll();
            if (v == null) {
                return false;
            }
            System.arraycopy(v, 0, out.video, 0, out.video.length);
            videoFree.offer(v);
            takeAudio(out, true);
        } else {
            if (!takeAudio(out, false)) {
                return false;
            }
        }
        framesOut.incrementAndGet();
        lastFrameNanos = System.nanoTime();
        return true;
    }

    private boolean takeAudio(Frame out, boolean silenceIfMissing) {
        byte[] a = audioFilled == null ? null : audioFilled.poll();
        if (a == null && audioFilled != null && options.video() && !decoderDone && !options.live()) {
            // Video and audio come from the same decoder; give the audio reader a moment to catch up.
            try {
                a = audioFilled.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (a != null) {
            System.arraycopy(a, 0, out.audio, 0, out.audio.length);
            return true;
        }
        if (silenceIfMissing) {
            out.silence();
        }
        return false;
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
        try {
            if (options.video() && options.audio()) {
                fifo = workDir.resolve("a-" + UUID.randomUUID().toString().substring(0, 8) + ".pcm");
                Fifos.create(fifo);
            }
            Process p = new ProcessBuilder(command(fifo)).start();
            process = p;
            if (closed) {
                p.destroyForcibly();
            }
            Deque<String> errors = new ArrayDeque<>();
            Thread err = Thread.ofPlatform().daemon(true).name("decoder-err-" + name).start(() -> readErrors(p.getErrorStream(), errors));
            if (options.video()) {
                readers.add(Thread.ofPlatform().daemon(true).name("decoder-v-" + name)
                        .start(() -> readChunks(p.getInputStream(), canvas.frameBytes(), true)));
                if (options.audio()) {
                    Path audioPipe = fifo;
                    readers.add(Thread.ofPlatform().daemon(true).name("decoder-a-" + name).start(() -> readFifo(audioPipe, p)));
                }
            } else {
                readers.add(Thread.ofPlatform().daemon(true).name("decoder-a-" + name)
                        .start(() -> readChunks(p.getInputStream(), canvas.audioBytesPerFrame(), false)));
            }
            int code = p.waitFor();
            if (!options.live()) {
                processExited = true;
            }
            for (Thread t : readers) {
                if (fifo != null && t.isAlive()) {
                    Fifos.release(fifo); // a decoder that died before opening the pipe must not leave the reader blocked
                }
                t.join(closed ? 1000 : 0);
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
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.addAll(List.of("-hide_banner", "-nostdin", "-loglevel", "error"));
        if (options.live()) {
            // Start fast: each reconnect of the phone is a new decoder run, so its timestamps are continuous.
            cmd.addAll(List.of("-fflags", "nobuffer", "-analyzeduration", "1000000", "-probesize", "1000000"));
        } else if (options.startSeconds() > 0) {
            cmd.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.3f", options.startSeconds())));
        }
        cmd.addAll(options.input());
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
            if (options.audio()) {
                graph.append(";[0:a:0]").append(audioChain).append("[a]");
            }
            cmd.addAll(List.of("-filter_complex", graph.toString()));
            cmd.addAll(List.of("-map", "[v]", "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1"));
            if (options.audio()) {
                cmd.addAll(List.of("-map", "[a]", "-f", "s16le", "-ar", String.valueOf(canvas.sampleRate()), "-ac", "2",
                        "-y", audioFifo.toString()));
            }
        } else {
            cmd.addAll(List.of("-vn", "-af", audioChain, "-f", "s16le", "-ar", String.valueOf(canvas.sampleRate()), "-ac", "2", "pipe:1"));
        }
        return cmd;
    }

    private void readFifo(Path fifo, Process p) {
        try (InputStream in = Files.newInputStream(fifo)) {
            readChunks(in, canvas.audioBytesPerFrame(), false);
        } catch (IOException e) {
            if (!closed && p.isAlive()) {
                log.debug("Audio pipe for {} failed: {}", name, e.getMessage());
            }
        }
    }

    private void readChunks(InputStream in, int size, boolean video) {
        try (in) {
            while (!closed) {
                byte[] buf = video ? videoFree.poll(500, TimeUnit.MILLISECONDS) : new byte[size];
                if (buf == null) {
                    continue; // consumer hasn't freed a buffer yet (paused file source)
                }
                if (!readFully(in, buf, size)) {
                    if (video) {
                        videoFree.offer(buf);
                    }
                    return;
                }
                BlockingQueue<byte[]> target = video ? videoFilled : audioFilled;
                if (options.live()) {
                    while (!target.offer(buf)) {
                        byte[] dropped = target.poll(); // never block a live decoder: drop the oldest
                        if (video && dropped != null) {
                            videoFree.offer(dropped);
                        }
                    }
                } else {
                    while (!closed && !target.offer(buf, 500, TimeUnit.MILLISECONDS)) {
                        // wait for the consumer
                    }
                }
            }
        } catch (IOException e) {
            // decoder ended
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        closed = true;
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
