package com.afklive.streamer.service;

import com.afklive.streamer.streaming.StreamRunner;

import java.util.Locale;

/**
 * Platforms by name and their errors in words a creator can act on (MS-6, RE-8). Shared by the
 * destination test and the live health, so a key that fails the test and a key that fails live read
 * the same.
 */
public final class PlatformErrors {

    private PlatformErrors() {
    }

    /** "YouTube", "Twitch", ... for a destination address or bare key; null for a server we don't know. */
    public static String platform(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String d = destination.trim();
        if (!d.contains("://")) {
            return "YouTube"; // a bare key goes to YouTube's ingest
        }
        String host = hostOf(d);
        if (host.contains("youtube.com")) return "YouTube";
        if (host.contains("facebook.com")) return "Facebook";
        if (host.contains("instagram.com") || host.contains("fbcdn.net")) return "Instagram";
        if (host.contains("twitch.tv")) return "Twitch";
        if (host.contains("kick.com") || host.contains("live-video.net")) return "Kick";
        if (host.contains("tiktok")) return "TikTok";
        if (host.contains("rumble")) return "Rumble";
        return null;
    }

    private static String hostOf(String url) {
        int start = url.indexOf("://") + 3;
        int end = start;
        while (end < url.length() && url.charAt(end) != '/' && url.charAt(end) != '?' && url.charAt(end) != ':') {
            end++;
        }
        return url.substring(start, end).toLowerCase(Locale.ROOT);
    }

    /**
     * True when what was saved as the key is really a server address (pasted from the wrong box), e.g.
     * a bare "rtmp://a.rtmp.youtube.com/live2", or a full URL whose key part is another URL.
     */
    public static boolean keyLooksLikeServerAddress(String destination) {
        if (destination == null) {
            return false;
        }
        String d = destination.trim().toLowerCase(Locale.ROOT);
        int first = d.indexOf("://");
        if (first < 0) {
            return d.startsWith("rtmp") || d.startsWith("srt") || d.contains("youtube.com/");
        }
        if (d.indexOf("://", first + 3) >= 0) {
            return true; // a URL inside the URL: the key box held an address
        }
        String path = d.substring(first + 3);
        int slash = path.indexOf('/');
        String afterHost = slash < 0 ? "" : path.substring(slash + 1);
        // rtmp://a.rtmp.youtube.com/live2 with nothing after the application: there is no key at all.
        return d.startsWith("rtmp") && !afterHost.contains("/") && afterHost.matches("live2?|app|live2/?");
    }

    /** FFmpeg's error, in words a creator can act on. */
    public static String explain(String ffmpegOutput) {
        return explain(ffmpegOutput, null);
    }

    /** {@code destination}: the address it was sent to, when known, to name the platform and spot a pasted address. */
    public static String explain(String ffmpegOutput, String destination) {
        String o = ffmpegOutput == null ? "" : ffmpegOutput.toLowerCase(Locale.ROOT);
        String platform = platform(destination);
        String the = platform == null ? "The platform" : platform;
        if (destination != null && keyLooksLikeServerAddress(destination)) {
            return "The stream key looks like a server address. Copy the stream key itself (not the server URL) from "
                    + (platform == null ? "the platform" : platform) + " and paste it again.";
        }
        if (o.contains(StreamRunner.STALLED.toLowerCase(Locale.ROOT))) {
            return the + " stopped taking the stream. We're reconnecting.";
        }
        if (o.contains("couldn't keep up")) {
            return "The connection to " + (platform == null ? "the platform" : platform)
                    + " was too slow to keep up, so we reconnected.";
        }
        if (o.contains("connection refused") || o.contains("cannot open connection") || o.contains("failed to resolve")
                || o.contains("name or service not known") || o.contains("timed out") || o.contains("network is unreachable")) {
            return "Couldn't reach the server. Check the server address.";
        }
        if (o.contains("broken pipe") || o.contains("end of file") || o.contains("input/output error")
                || o.contains("connection reset") || o.contains("unauthorized") || o.contains("403")) {
            return the + " refused the stream key. Copy the key again from " + (platform == null ? "the platform" : platform)
                    + " and save it.";
        }
        return "The stream didn't get through. Check the server address and stream key.";
    }
}
