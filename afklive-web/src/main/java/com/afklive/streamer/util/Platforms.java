package com.afklive.streamer.util;

import java.util.Locale;

/** Which platform a destination address goes to, for words like "On YouTube" (RE-8) and the app's icons. */
public final class Platforms {

    private Platforms() {
    }

    /** YOUTUBE, FACEBOOK, INSTAGRAM, TWITCH, KICK, TIKTOK, RUMBLE or CUSTOM. A bare key is a YouTube key. */
    public static String code(String address) {
        if (address == null || address.isBlank()) {
            return "CUSTOM";
        }
        String a = address.trim();
        if (!a.contains("://")) {
            return "YOUTUBE";
        }
        String host = host(a);
        return host.contains("youtube.com") ? "YOUTUBE" : host.contains("facebook.com") ? "FACEBOOK"
                : host.contains("instagram.com") || host.contains("fbcdn.net") ? "INSTAGRAM" : host.contains("twitch.tv") ? "TWITCH"
                : host.contains("kick.com") || host.contains("live-video.net") ? "KICK"
                : host.contains("tiktok") ? "TIKTOK" : host.contains("rumble") ? "RUMBLE" : "CUSTOM";
    }

    /** "YouTube", "Twitch", ...; null for a server we don't know (callers then use the destination's name). */
    public static String label(String address) {
        return switch (code(address)) {
            case "YOUTUBE" -> "YouTube";
            case "FACEBOOK" -> "Facebook";
            case "INSTAGRAM" -> "Instagram";
            case "TWITCH" -> "Twitch";
            case "KICK" -> "Kick";
            case "TIKTOK" -> "TikTok";
            case "RUMBLE" -> "Rumble";
            default -> null;
        };
    }

    private static String host(String url) {
        int start = url.indexOf("://") + 3;
        int end = start;
        while (end < url.length() && url.charAt(end) != '/' && url.charAt(end) != '?' && url.charAt(end) != ':') {
            end++;
        }
        return url.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
