package com.afklive.streamer.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AD-1 / AD-3: what support may see of a destination address, and a quick check of its shape. The key
 * itself never leaves the server: only its first and last four characters when it's long enough that
 * those don't give it away, and its length.
 */
public final class DestinationInspector {

    public enum Level { OK, WARN, BAD }

    /**
     * @param server    scheme, host, port and path up to the key; no password, no query
     * @param keyMasked e.g. "abcd…wxyz"; never the whole key
     * @param keyLength characters in the key (0: none)
     */
    public record Inspection(String server, String keyMasked, int keyLength, Level level, String check) {
    }

    /** Below this length a key is shown only as dots (first + last four would be most of it). */
    static final int SHOW_ENDS_FROM = 16;

    private static final Pattern YOUTUBE_KEY = Pattern.compile("(?i)[a-z0-9]{4}(-[a-z0-9]{4}){3,5}");
    private static final Pattern NESTED = Pattern.compile("(?i)(rtmps?|srt)://");

    private DestinationInspector() {
    }

    public static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() < SHOW_ENDS_FROM) {
            return "••••";
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    public static Inspection inspect(String address) {
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) {
            return new Inspection("", "", 0, Level.BAD, "Empty address");
        }
        String server;
        String key;
        int scheme = a.indexOf("://");
        if (scheme < 0) {
            server = "rtmp://a.rtmp.youtube.com/live2";
            key = a;
        } else if (a.regionMatches(true, 0, "srt://", 0, 6)) {
            int q = a.indexOf('?');
            server = q < 0 ? a : a.substring(0, q);
            key = queryValue(a, "streamid");
        } else {
            String rest = a.substring(scheme + 3);
            int q = rest.indexOf('?');
            String path = q < 0 ? rest : rest.substring(0, q);
            int slash = path.lastIndexOf('/');
            if (slash < 0) {
                server = a.substring(0, scheme + 3) + path;
                key = "";
            } else {
                server = a.substring(0, scheme + 3) + path.substring(0, slash);
                key = path.substring(slash + 1);
            }
        }
        server = withoutPassword(server);
        return new Inspection(server, mask(key), key.length(), level(server, key, scheme < 0), check(server, key, scheme < 0));
    }

    private static Level level(String server, String key, boolean bare) {
        String c = check(server, key, bare);
        return c.equals(OK) ? Level.OK : c.startsWith("Doesn't look like") ? Level.WARN : Level.BAD;
    }

    static final String OK = "Looks right";

    private static String check(String server, String key, boolean bare) {
        if (key.isEmpty()) {
            return server.regionMatches(true, 0, "srt://", 0, 6) ? "No stream id in the address" : "Empty key";
        }
        String k = key.toLowerCase(Locale.ROOT);
        if (NESTED.matcher(key).find() || k.contains(".com") || k.contains(".tv") || k.equals("live2") || k.equals("live")
                || k.equals("app") || (bare && key.contains("/"))) {
            return "Key looks like a server address";
        }
        if (key.chars().anyMatch(Character::isWhitespace)) {
            return "Key has spaces in it";
        }
        String host = host(server);
        if ((host.equals("youtube.com") || host.endsWith(".youtube.com")) && !YOUTUBE_KEY.matcher(key).matches()) {
            return "Doesn't look like a YouTube key (usually xxxx-xxxx-xxxx-xxxx-xxxx)";
        }
        if ((host.endsWith("twitch.tv") || host.endsWith("contribute.live-video.net")) && host.contains("twitch") && !k.startsWith("live_")) {
            return "Doesn't look like a Twitch key (starts with live_)";
        }
        return OK;
    }

    static String host(String server) {
        int scheme = server.indexOf("://");
        String rest = scheme < 0 ? server : server.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        int colon = hostPort.lastIndexOf(':');
        return (colon >= 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase(Locale.ROOT);
    }

    private static String withoutPassword(String server) {
        int scheme = server.indexOf("://");
        if (scheme < 0) {
            return server;
        }
        String rest = server.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return server;
        }
        return server.substring(0, scheme + 3) + "••••@" + rest.substring(at + 1);
    }

    private static String queryValue(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase(name)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }
}
