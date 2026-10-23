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
        if (value.length() > 600) {
            throw new IllegalArgumentException("Stream address is too long (600 characters at most).");
        }
        if (!value.contains("://")) {
            if (!BARE_KEY.matcher(value).matches()) {
                throw new IllegalArgumentException("Stream key contains characters that aren't allowed.");
            }
            return;
        }
        requireSafeUrl(value, STREAM_SCHEMES, "Stream URL must start with rtmp://, rtmps:// or srt://");
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("srt://") && (lower.contains("mode=listener") || lower.contains("mode=rendezvous"))) {
            // Listener mode would make the server open a port instead of sending to the destination.
            throw new IllegalArgumentException("SRT destinations must use caller mode.");
        }
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
            return isPublicIpv4(b);
        }
        if (address instanceof Inet6Address) {
            if (isIpv4Mapped(b) || isIpv4Compatible(b)) {
                return isPublicIpv4(new byte[]{b[12], b[13], b[14], b[15]});
            }
            int first = b[0] & 0xff;
            // Only global unicast (2000::/3) is reachable on the internet.
            if ((first & 0xe0) != 0x20) return false;
            int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
            int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            if (w0 == 0x2002) return false;                  // 6to4 (embeds an IPv4 address)
            if (w0 == 0x2001 && w1 == 0x0000) return false;  // Teredo
            if (w0 == 0x2001 && w1 == 0x0db8) return false;  // documentation
            return true;
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        int third = b[2] & 0xff;
        if (first == 0 || first == 10 || first == 127) return false;              // this-network, private, loopback
        if (first == 169 && second == 254) return false;                         // link-local, cloud metadata
        if (first == 172 && second >= 16 && second <= 31) return false;          // private
        if (first == 192 && second == 168) return false;                         // private
        if (first == 100 && second >= 64 && second <= 127) return false;         // carrier-grade NAT
        if (first == 192 && second == 0 && third == 0) return false;             // IETF protocol assignments
        if (first == 192 && second == 0 && third == 2) return false;             // documentation
        if (first == 192 && second == 88 && third == 99) return false;           // 6to4 relay anycast
        if (first == 198 && (second == 18 || second == 19)) return false;        // benchmarking
        if (first == 198 && second == 51 && third == 100) return false;          // documentation
        if (first == 203 && second == 0 && third == 113) return false;           // documentation
        return first < 224;                                                       // not multicast/reserved/broadcast
    }

    private static boolean isIpv4Compatible(byte[] b) {
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) return false;
        }
        return true; // ::a.b.c.d (deprecated, but routes to an IPv4 address)
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
