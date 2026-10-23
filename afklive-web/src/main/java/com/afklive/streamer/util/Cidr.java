package com.afklive.streamer.util;

import java.math.BigInteger;
import java.net.InetAddress;

/** IPv4/IPv6 CIDR matching without DNS lookups. */
public final class Cidr {

    private Cidr() {
    }

    public static boolean contains(String cidr, String ip) {
        try {
            String[] parts = cidr.split("/");
            if (!isLiteral(parts[0]) || !isLiteral(ip)) {
                return false;
            }
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            byte[] addr = InetAddress.getByName(ip).getAddress();
            if (net.length != addr.length) {
                return false;
            }
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : net.length * 8;
            int total = net.length * 8;
            BigInteger mask = BigInteger.ONE.shiftLeft(total).subtract(BigInteger.ONE)
                    .shiftRight(bits).xor(BigInteger.ONE.shiftLeft(total).subtract(BigInteger.ONE));
            return new BigInteger(1, net).and(mask).equals(new BigInteger(1, addr).and(mask));
        } catch (Exception e) {
            return false;
        }
    }

    /** Only IP literals: never resolve names here. */
    private static boolean isLiteral(String s) {
        return s.matches("[0-9.]+") || s.contains(":");
    }
}
