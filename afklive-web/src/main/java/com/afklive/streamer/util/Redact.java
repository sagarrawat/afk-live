package com.afklive.streamer.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Removes stream keys from text (FFmpeg errors quote the full output URL, key included). */
public final class Redact {

    public static final String MASK = "[REDACTED]";

    private static final Pattern STREAM_URL = Pattern.compile("(?i)\\b(rtmps?|srt)://\\S+");
    /**
     * A key passed as a query or option value without its URL: "?key=…", "streamid=…", "passphrase=…".
     * The value runs to the next separator; SRT stream ids contain commas, so a comma doesn't end it.
     */
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(key|stream_?key|streamid|passphrase|pass|password|pwd|token|secret|auth|sig|signature)=([^&\\s'\"]+)");
    /** Secrets shorter than this aren't searched for: they'd blank out ordinary words. */
    static final int MIN_SECRET = 6;

    private Redact() {
    }

    public static String streamKeys(String text) {
        if (text == null) {
            return null;
        }
        return STREAM_URL.matcher(text).replaceAll("$1://[REDACTED]");
    }

    /**
     * A line of FFmpeg output safe to store and show admins: every stream URL is cut, and so is every
     * known secret wherever it appears on its own (FFmpeg sometimes prints a path or a key without the
     * scheme). {@code secrets} are values from {@link #secretsOf}.
     */
    public static String line(String text, Collection<String> secrets) {
        String out = streamKeys(text);
        if (out == null) {
            return null;
        }
        out = NAMED_SECRET.matcher(out).replaceAll("$1=" + MASK);
        if (secrets == null) {
            return out;
        }
        // Longest first, so a full address is cut before the key inside it. FFmpeg may print a value
        // URL-encoded or decoded, so both forms are searched for.
        List<String> sorted = new ArrayList<>();
        for (String s : secrets) {
            if (s == null) {
                continue;
            }
            sorted.add(s);
            try {
                sorted.add(java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // not valid percent-encoding
            }
            sorted.add(java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8));
        }
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        for (String s : sorted) {
            if (s != null && s.length() >= MIN_SECRET) {
                out = out.replace(s, MASK);
            }
        }
        return out;
    }

    /**
     * The secret parts of a destination address: the whole address, its last path segment (the stream
     * key on RTMP), any password in it, and every query value (SRT's streamid, passphrase).
     */
    public static List<String> secretsOf(String address) {
        List<String> out = new ArrayList<>();
        if (address == null || address.isBlank()) {
            return out;
        }
        String a = address.trim();
        out.add(a);
        int scheme = a.indexOf("://");
        if (scheme < 0) {
            out.add(a); // a bare key
            return out;
        }
        String rest = a.substring(scheme + 3);
        int at = rest.indexOf('@');
        int firstSlash = rest.indexOf('/');
        if (at > 0 && (firstSlash < 0 || at < firstSlash)) {
            String userInfo = rest.substring(0, at);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                out.add(userInfo.substring(colon + 1));
            }
        }
        int q = rest.indexOf('?');
        String path = q < 0 ? rest : rest.substring(0, q);
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq >= 0 && eq < pair.length() - 1) {
                    out.add(pair.substring(eq + 1));
                }
            }
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            out.add(path.substring(slash + 1));
        }
        return out;
    }
}
