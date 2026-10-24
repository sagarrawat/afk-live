package com.afklive.streamer.streaming.soak;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * OPS-6: a stand-in for a platform's ingest server on 127.0.0.1. A relay sends it FLV over TCP exactly as it
 * would to YouTube (the relay command is the production one with a {@code tcp://} URL), so nothing leaves the
 * machine and nothing large is written to disk. Every connection is counted (a planned switch must never
 * reconnect) and read by its own {@link FlvTimeline}.
 */
public final class SoakSink implements AutoCloseable {

    private final String name;
    private final long warmupNanos;
    private final ServerSocket server;
    private final List<FlvTimeline> connections = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public SoakSink(String name, long warmupNanos) throws IOException {
        this.name = name;
        this.warmupNanos = warmupNanos;
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon(true).name("soak-sink-" + name).start(this::accept);
    }

    public String name() {
        return name;
    }

    public int port() {
        return server.getLocalPort();
    }

    /** What the relay is given as its destination. */
    public String url() {
        return "tcp://127.0.0.1:" + port();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket s = server.accept();
                sockets.add(s);
                FlvTimeline t = new FlvTimeline(warmupNanos);
                connections.add(t);
                Thread.ofPlatform().daemon(true).name("soak-sink-" + name + "-" + connections.size()).start(() -> read(s, t));
            } catch (IOException e) {
                if (!closed) {
                    sleepQuietly();
                }
            }
        }
    }

    private void read(Socket s, FlvTimeline t) {
        byte[] chunk = new byte[64 * 1024];
        try (s; InputStream in = s.getInputStream()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                t.feed(chunk, n, System.nanoTime());
            }
        } catch (IOException | RuntimeException e) {
            // the relay went away; a reconnect is a new connection
        } finally {
            t.finishCapture();
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Every connection the relay made, oldest first; more than one means the destination reconnected. */
    public List<FlvTimeline> connections() {
        return List.copyOf(connections);
    }

    /** Cuts a sample from the current connection; false when there is none or one is being cut. */
    public boolean capture(Path file, double seconds, String reason, Consumer<FlvTimeline.Capture> done) {
        if (connections.isEmpty()) {
            return false;
        }
        try {
            return connections.getLast().capture(file, seconds, reason, done);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }
}
