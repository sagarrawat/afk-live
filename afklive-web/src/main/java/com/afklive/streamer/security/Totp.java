package com.afklive.streamer.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * ON-6: time-based one-time passwords (RFC 6238 over RFC 4226), JDK only: HMAC-SHA1, 30-second
 * steps, 6 digits, and one step of clock drift either way. Secrets are 20 random bytes, shown to
 * authenticator apps in Base32 (RFC 4648, no padding).
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Steps of clock drift accepted on each side of now. */
    public static final int WINDOW = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {
    }

    /** A new random secret, Base32. */
    public static String newSecret() {
        byte[] key = new byte[20];
        RANDOM.nextBytes(key);
        return base32Encode(key);
    }

    /** The 30-second step that {@code epochSeconds} falls in. */
    public static long step(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** RFC 4226 HOTP with HMAC-SHA1: the code for {@code counter}, zero-padded to {@code digits}. */
    public static String hotp(byte[] key, long counter, int digits) {
        if (digits < 1 || digits > 8) {
            throw new IllegalArgumentException("digits must be 1 to 8");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            String code = Integer.toString(binary % POW10[digits]);
            return "0".repeat(digits - code.length()) + code;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is unavailable", e);
        }
    }

    /** The 6-digit code for a Base32 secret at a time. */
    public static String code(String base32Secret, long epochSeconds) {
        return hotp(base32Decode(base32Secret), step(epochSeconds), DIGITS);
    }

    /**
     * Checks a code against now and one step either side. Returns the step it matched, or -1. A code
     * for a step at or before {@code lastUsedStep} is refused (each code works once).
     */
    public static long verify(String base32Secret, String code, long epochSeconds, Long lastUsedStep) {
        String c = normalize(code);
        if (c == null || c.length() != DIGITS) {
            return -1;
        }
        byte[] key = base32Decode(base32Secret);
        long now = step(epochSeconds);
        long matched = -1;
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            // Compare every candidate (no early exit) so timing doesn't reveal which step matched.
            if (java.security.MessageDigest.isEqual(hotp(key, s, DIGITS).getBytes(StandardCharsets.US_ASCII),
                    c.getBytes(StandardCharsets.US_ASCII)) && matched < 0) {
                matched = s;
            }
        }
        if (matched >= 0 && lastUsedStep != null && matched <= lastUsedStep) {
            return -1;
        }
        return matched;
    }

    /** Digits only ("123 456" and "123-456" are fine); null if anything else is in it. */
    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.replaceAll("[\\s-]", "");
        return c.chars().allMatch(Character::isDigit) ? c : null;
    }

    /** The otpauth:// link authenticator apps read from the QR code. */
    public static String uri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(in.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char ch : in.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) {
                throw new IllegalArgumentException("Not Base32: " + ch);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
