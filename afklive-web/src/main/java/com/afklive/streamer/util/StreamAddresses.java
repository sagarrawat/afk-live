package com.afklive.streamer.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Catches stream addresses that are safe to connect to but can never work, so a creator hears
 * about it when saving instead of after hours of a stream that looks live and reaches nobody.
 * The common case: YouTube's server address pasted into the stream key box, saved as
 * {@code rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2}.
 */
public final class StreamAddresses {

    static final String NESTED = "This address has a second server address inside it, so the platform would refuse it. "
            + "Paste only the stream key (on YouTube: YouTube Studio, Go live → Stream → Stream key).";
    static final String YOUTUBE_NO_KEY = "The YouTube stream key is missing. "
            + "Copy it from YouTube Studio (Go live → Stream → Stream key) and paste it in the stream key box.";

    private static final Pattern STREAM_SCHEME = Pattern.compile("(?i)(rtmps?|srt)://");

    private StreamAddresses() {
    }

    /**
     * @throws IllegalArgumentException with a plain-language message when the address can't work
     */
    public static void requireWellFormed(String address) {
        if (address == null) {
            return;
        }
        String value = address.trim();
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            // A bare key goes to YouTube's server; "live2" is the server's path, not a key.
            if (value.equalsIgnoreCase("live2")) {
                throw new IllegalArgumentException(YOUTUBE_NO_KEY);
            }
            return;
        }
        String rest = value.substring(scheme + 3);
        if (STREAM_SCHEME.matcher(rest).find()) {
            throw new IllegalArgumentException(NESTED);
        }
        int slash = rest.indexOf('/');
        String host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            String path = slash < 0 ? "" : rest.substring(slash).replaceAll("/+$", "");
            String key = path.substring(path.lastIndexOf('/') + 1);
            int query = key.indexOf('?');
            if (query >= 0) {
                key = key.substring(0, query); // "live2?backup=1" is still the server path
            }
            if (key.isBlank() || key.equalsIgnoreCase("live2")) {
                throw new IllegalArgumentException(YOUTUBE_NO_KEY);
            }
        }
    }
}
