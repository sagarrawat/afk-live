package com.afklive.streamer.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in for a platform's RTMP ingest on 127.0.0.1, for the end-to-end test: FFmpeg in listen mode takes
 * one publisher at a time (the destination test, then the live relay), so a new listener starts as soon as
 * the previous one ends. It counts the FLV bytes each publisher sent; nothing is written to disk.
 */
final class RtmpSink implements AutoCloseable {

    private static final String KEY = "e2e-sink";

    private final int port;
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile Process current;
    private volatile boolean closed;
    private final Thread loop;

    RtmpSink() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        loop = Thread.ofPlatform().daemon(true).name("e2e-rtmp-sink").start(this::serve);
    }

    /** What the creator types as the destination's stream address. */
    String url() {
        return "rtmp://127.0.0.1:" + port + "/live/" + KEY;
    }

    /** FLV bytes received from every publisher so far. */
    long bytes() {
        return bytes.get();
    }

    /** Publishers that sent at least one byte. */
    int connections() {
        return connections.get();
    }

    private void serve() {
        while (!closed) {
            try {
                Process p = new ProcessBuilder(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-listen", "1", "-f", "flv", "-i", url(), "-c", "copy", "-f", "flv", "pipe:1"))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                current = p;
                byte[] chunk = new byte[64 * 1024];
                boolean counted = false;
                try (InputStream in = p.getInputStream()) {
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        if (!counted) {
                            connections.incrementAndGet();
                            counted = true;
                        }
                        bytes.addAndGet(n);
                    }
                }
                p.waitFor();
            } catch (IOException e) {
                if (!closed) {
                    pause();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        Process p = current;
        if (p != null) {
            p.destroyForcibly();
        }
        loop.interrupt();
    }
}
