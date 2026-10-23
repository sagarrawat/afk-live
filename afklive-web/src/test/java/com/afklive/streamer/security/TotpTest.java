package com.afklive.streamer.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ON-6: TOTP against the published test vectors. */
class TotpTest {

    /** The RFC 4226 / RFC 6238 SHA-1 test key: ASCII "12345678901234567890". */
    private static final byte[] KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String KEY_B32 = Totp.base32Encode(KEY);

    @ParameterizedTest(name = "T={0}")
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130",
    })
    void rfc6238Sha1Vectors(long time, String expected) {
        assertThat(Totp.hotp(KEY, Totp.step(time), 8)).isEqualTo(expected);
        // Six digits are the last six of the eight-digit value.
        assertThat(Totp.code(KEY_B32, time)).isEqualTo(expected.substring(2));
    }

    @Test
    void rfc4226HotpVectors() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
        for (int i = 0; i < expected.length; i++) {
            assertThat(Totp.hotp(KEY, i, 6)).isEqualTo(expected[i]);
        }
    }

    @Test
    void base32RoundTripsAndMatchesTheKnownEncoding() {
        assertThat(KEY_B32).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.base32Decode(KEY_B32)).isEqualTo(KEY);
        assertThat(Totp.base32Decode(Totp.newSecret())).hasSize(20);
    }

    @Test
    void acceptsOneStepOfDriftEitherWayAndNoMore() {
        long t = 1_700_000_000L;
        String now = Totp.code(KEY_B32, t);
        assertThat(Totp.verify(KEY_B32, now, t, null)).isEqualTo(Totp.step(t));
        assertThat(Totp.verify(KEY_B32, Totp.code(KEY_B32, t - 30), t, null)).isEqualTo(Totp.step(t) - 1);
        assertThat(Totp.verify(KEY_B32, Totp.code(KEY_B32, t + 30), t, null)).isEqualTo(Totp.step(t) + 1);
        assertThat(Totp.verify(KEY_B32, Totp.code(KEY_B32, t - 90), t, null)).isEqualTo(-1);
        assertThat(Totp.verify(KEY_B32, Totp.code(KEY_B32, t + 90), t, null)).isEqualTo(-1);
    }

    @Test
    void aCodeWorksOnceAndJunkIsRefused() {
        long t = 1_700_000_000L;
        String code = Totp.code(KEY_B32, t);
        assertThat(Totp.verify(KEY_B32, code, t, Totp.step(t))).isEqualTo(-1);
        assertThat(Totp.verify(KEY_B32, code.substring(0, 3) + " " + code.substring(3), t, null)).isEqualTo(Totp.step(t));
        assertThat(Totp.verify(KEY_B32, "abcdef", t, null)).isEqualTo(-1);
        assertThat(Totp.verify(KEY_B32, "12345", t, null)).isEqualTo(-1);
        assertThat(Totp.verify(KEY_B32, null, t, null)).isEqualTo(-1);
    }

    @Test
    void uriIsWhatAuthenticatorAppsExpect() {
        assertThat(Totp.uri("AFK Live", "a+b@example.com", "ABC"))
                .isEqualTo("otpauth://totp/AFK%20Live:a%2Bb%40example.com?secret=ABC&issuer=AFK%20Live&algorithm=SHA1&digits=6&period=30");
    }
}
