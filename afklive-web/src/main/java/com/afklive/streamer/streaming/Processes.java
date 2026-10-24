package com.afklive.streamer.streaming;

/**
 * Stopping FFmpeg processes without ever blocking the caller.
 * <p>
 * {@link Process#destroy()} closes the process's pipes first, and closing stdin flushes it under the
 * stream's lock. If another thread is blocked writing frames to that stdin (the encoder fell behind,
 * so the pipe is full), the caller waits for that lock forever. That froze the worker's loop in
 * production (October 2026): a stop on the loop thread hung, so no stream was claimed or reported.
 * Signalling through {@link ProcessHandle} sends SIGTERM/SIGKILL without touching the pipes; the
 * blocked writer then fails with a broken pipe and lets go.
 */
public final class Processes {

    private Processes() {
    }

    /** SIGTERM: never blocks. */
    public static void terminate(Process p) {
        if (p != null) {
            p.toHandle().destroy();
        }
    }

    /** SIGKILL: never blocks. */
    public static void kill(Process p) {
        if (p != null) {
            p.toHandle().destroyForcibly();
        }
    }
}
