package com.afklive.streamer.util;

import java.net.InetAddress;

/** For tests outside this package: every host resolves to a public address (test machines may have no DNS). */
public final class PublicDnsForTests {

    private PublicDnsForTests() {
    }

    public static void on() {
        NetworkSafety.setResolverForTests(host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});
    }

    public static void off() {
        NetworkSafety.setResolverForTests(null);
    }
}
