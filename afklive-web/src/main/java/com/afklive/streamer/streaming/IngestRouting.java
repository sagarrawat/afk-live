package com.afklive.streamer.streaming;

/**
 * OPS-3: which destinations have a second ingest a handover can publish to, and the address for each.
 * Production uses {@link YouTubeIngest}; tests route local sinks the same way.
 */
public interface IngestRouting {

    /** True when the destination accepts the same stream on a primary and a backup ingest at once. */
    boolean hasBackup(String destination);

    /** The destination as published to {@code ingest}; unchanged for one without a backup. */
    String route(String destination, YouTubeIngest.Ingest ingest);
}
