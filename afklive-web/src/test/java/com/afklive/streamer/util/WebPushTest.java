package com.afklive.streamer.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushTest {

    private static byte[] b64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    @Test
    void encryptsExactlyLikeTheRfc8291Example() throws Exception {
        // RFC 8291, Appendix A.
        KeyPair as = new KeyPair(
                WebPush.publicKey(b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPush.privateKey(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] out = WebPush.encrypt(b64("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24"),
                b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                b64("BTBZMqHH6r4Tts7J_aSIgg"), as, b64("DGv6ra1nlYgDCS1FRnbzlw"));

        byte[] header = b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] ciphertext = b64("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ");
        byte[] expected = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, expected, 0, header.length);
        System.arraycopy(ciphertext, 0, expected, header.length, ciphertext.length);
        assertThat(out).isEqualTo(expected);
    }

    @Test
    void vapidTokensVerifyWithTheServersPublicKey() throws Exception {
        KeyPair vapid = WebPush.newKeyPair();
        String auth = WebPush.vapidAuthorization(vapid, "https://fcm.googleapis.com", "mailto:support@afklive.in", 1_800_000_000L);
        assertThat(auth).startsWith("vapid t=").contains(", k=");
        String jwt = auth.substring("vapid t=".length(), auth.indexOf(','));
        String[] parts = jwt.split("\\.");
        assertThat(new String(b64(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1800000000,\"sub\":\"mailto:support@afklive.in\"}");
        byte[] sig = b64(parts[2]);
        assertThat(sig).hasSize(64);
        // Back to DER and verify, as a push service would.
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify(vapid.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(sig)).isTrue();
        assertThat(b64(auth.substring(auth.indexOf("k=") + 2))).isEqualTo(WebPush.raw((ECPublicKey) vapid.getPublic()));
    }

    @Test
    void readsTheKeyPairOpensslWrites() throws Exception {
        // `openssl ecparam -name prime256v1 -genkey -noout -outform DER | base64 -w0`
        Process p = new ProcessBuilder("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-outform", "DER").start();
        byte[] der = p.getInputStream().readAllBytes();
        org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0 && der.length > 0, "openssl not available");
        KeyPair kp = WebPush.fromSec1(der);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(new byte[]{1, 2, 3});
        byte[] sig = s.sign();
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(kp.getPublic());
        v.update(new byte[]{1, 2, 3});
        assertThat(v.verify(sig)).as("the public half matches the private half").isTrue();
    }
}
