package com.afklive.streamer.streaming;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPS-3: YouTube's redundant ingest. Every YouTube stream key can be published to a primary and a
 * backup server at the same time; YouTube fails over between them without ending the broadcast
 * (YouTube Data API {@code liveStreams.cdn.ingestionInfo}: {@code ingestionAddress} /
 * {@code backupIngestionAddress}, used as {@code STREAM_URL/STREAM_NAME}):
 * <pre>
 *   primary  rtmp://a.rtmp.youtube.com/live2/KEY            rtmps://a.rtmps.youtube.com:443/live2/KEY
 *   backup   rtmp://b.rtmp.youtube.com/live2?backup=1/KEY   rtmps://b.rtmps.youtube.com:443/live2?backup=1/KEY
 * </pre>
 * A worker handing a stream to a new worker version publishes the new copy to the other ingest, so
 * both are connected for a few seconds and YouTube never sees the stream stop.
 * Pure functions only: everything here is unit tested.
 */
public final class YouTubeIngest implements IngestRouting {

    /** Which of YouTube's two ingest servers a run publishes to. */
    public enum Ingest {
        PRIMARY, BACKUP;

        public Ingest other() {
            return this == PRIMARY ? BACKUP : PRIMARY;
        }

        /** The value stored in {@code stream_jobs.youtube_ingest}; null (older rows) means primary. */
        public static Ingest of(String stored) {
            return "BACKUP".equalsIgnoreCase(stored) ? BACKUP : PRIMARY;
        }
    }

    public static final YouTubeIngest INSTANCE = new YouTubeIngest();

    /** scheme, server letter, rtmp/rtmps label, optional port, optional backup flag, key. */
    private static final Pattern YOUTUBE = Pattern.compile(
            "^(rtmps?)://([ab])\\.(rtmps?)\\.youtube\\.com(:\\d{1,5})?/live2(\\?backup=1)?/([^/?#\\s]+)$",
            Pattern.CASE_INSENSITIVE);

    private YouTubeIngest() {
    }

    /** True for a destination YouTube can take on either ingest: a bare stream key or a primary/backup URL. */
    @Override
    public boolean hasBackup(String destination) {
        if (destination == null || destination.isBlank()) {
            return false;
        }
        String d = destination.trim();
        return !d.contains("://") || YOUTUBE.matcher(d).matches();
    }

    /**
     * The destination as published to {@code ingest}. Anything that isn't a YouTube destination is
     * returned unchanged; a bare key becomes a full URL so the relay never has to guess.
     */
    @Override
    public String route(String destination, Ingest ingest) {
        if (!hasBackup(destination)) {
            return destination;
        }
        String d = destination.trim();
        if (!d.contains("://")) {
            // Same as FFmpegCommandBuilder's default for a bare key, on the chosen server.
            return ingest == Ingest.BACKUP
                    ? "rtmp://b.rtmp.youtube.com:1935/live2?backup=1/" + d
                    : "rtmp://a.rtmp.youtube.com:1935/live2/" + d;
        }
        Matcher m = YOUTUBE.matcher(d);
        if (!m.matches()) {
            return destination;
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        String label = m.group(3).toLowerCase(Locale.ROOT);
        String port = m.group(4) == null ? "" : m.group(4);
        String key = m.group(6);
        return ingest == Ingest.BACKUP
                ? scheme + "://b." + label + ".youtube.com" + port + "/live2?backup=1/" + key
                : scheme + "://a." + label + ".youtube.com" + port + "/live2/" + key;
    }
}
