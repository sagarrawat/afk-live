package com.afklive.streamer.util;

import java.util.regex.Pattern;

/** Removes stream keys from text (FFmpeg errors quote the full output URL, key included). */
public final class Redact {

    private static final Pattern STREAM_URL = Pattern.compile("(?i)\\b(rtmps?|srt)://\\S+");

    private Redact() {
    }

    public static String streamKeys(String text) {
        if (text == null) {
            return null;
        }
        return STREAM_URL.matcher(text).replaceAll("$1://[REDACTED]");
    }
}
