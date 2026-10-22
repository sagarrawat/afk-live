package com.afklive.streamer.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards every place the server connects to an address a user typed (stream destinations,
 * import URLs), so it can't be pointed at our own network, cloud metadata or local files.
 */
public final class NetworkSafety {

    private static final Set<String> STREAM_SCHEMES = Set.of("rtmp", "rtmps", "srt");
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
    /** A bare stream key (YouTube style). Anything else must be a full URL. */
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_\\-.]{1,128}");

    /** Resolves host names; replaceable in tests. */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static volatile Resolver resolver = InetAddress::getAllByName;

    private NetworkSafety() {
    }

    static void setResolverForTests(Resolver testResolver) {
        resolver = testResolver == null ? InetAddress::getAllByName : testResolver;
    }

    /**
     * Validates a stream destination: either a bare stream key (sent to YouTube's ingest)
     * or an rtmp/rtmps/srt URL whose host resolves only to public addresses.
     *
     * @throws IllegalArgumentException with a user-facing message when the destination is not allowed
     */
    public static void requireSafeStreamDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Stream destination is empty.");
        }
        String value = destination.trim();
        if (!value.contains("://")) {
            if (!BARE_KEY.matcher(value).matches()) {
                throw new IllegalArgumentException("Stream key contains characters that aren't allowed.");
            }
            return;
        }
        requireSafeUrl(value, STREAM_SCHEMES, "Stream URL must start with rtmp://, rtmps:// or srt://");
    }

    /**
     * Validates a web URL the server will fetch (imports): http/https only, public hosts only.
     */
    public static void requireSafeWebUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is empty.");
        }
        requireSafeUrl(url.trim(), WEB_SCHEMES, "URL must start with http:// or https://");
    }

    private static void requireSafeUrl(String value, Set<String> allowedSchemes, String schemeMessage) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new IllegalArgumentException(schemeMessage);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host.");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not find the server " + host + ".");
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Could not find the server " + host + ".");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("Private or local network addresses aren't allowed.");
            }
        }
    }

    /** True only for globally routable unicast addresses. */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) return false;                                   // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return false; // 100.64.0.0/10 carrier-grade NAT
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (first == 198 && (second == 18 || second == 19)) return false; // 198.18.0.0/15 benchmarking
            if (first >= 240) return false;                                  // reserved + broadcast
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;                        // fc00::/7 unique local
            if (isIpv4Mapped(b)) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
