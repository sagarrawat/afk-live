package com.afklive.streamer.util;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push with the JDK only (NT-1, MB-2): message encryption (RFC 8291, aes128gcm per RFC 8188) and
 * VAPID authentication (RFC 8292). Keys are P-256; public keys travel as 65-byte uncompressed points.
 */
public final class WebPush {

    public static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256;

    static {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            P256 = p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WebPush() {
    }

    // ---------------------------------------------------------------- keys

    public static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    public static ECPrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    public static byte[] raw(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(key.getW().getAffineX(), out, 1);
        copy32(key.getW().getAffineY(), out, 33);
        return out;
    }

    /**
     * The VAPID key pair from a SEC1 "EC PRIVATE KEY" in DER, as written by
     * {@code openssl ecparam -name prime256v1 -genkey -noout -outform DER}: it holds both halves.
     */
    public static KeyPair fromSec1(byte[] der) throws GeneralSecurityException {
        // 30 77 02 01 01 04 20 <d:32> a0 0a 06 08 <oid> a1 44 03 42 00 <public:65>
        if (der.length < 7 + 32 + 65 || der[0] != 0x30 || der[5] != 0x04 || der[6] != 0x20) {
            throw new GeneralSecurityException("Not a SEC1 P-256 private key");
        }
        byte[] d = Arrays.copyOfRange(der, 7, 39);
        byte[] pub = Arrays.copyOfRange(der, der.length - 65, der.length);
        return new KeyPair(publicKey(pub), privateKey(d));
    }

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    // ---------------------------------------------------------------- encryption (RFC 8291)

    /** Encrypts for one browser with a fresh key pair and salt: the request body, header included. */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, newKeyPair(), salt);
    }

    /** As above with the sender's key pair and salt given (tests use RFC 8291's example values). */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length > RECORD_SIZE - 16 - 1 - 86) {
            throw new GeneralSecurityException("Push message too long");
        }
        byte[] asPublic = raw((ECPublicKey) as.getPublic());
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(publicKey(uaPublic), true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdh);
        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic);
        byte[] ikm = Arrays.copyOf(hmac(prkKey, concat(keyInfo, new byte[]{1})), 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] body = c.doFinal(concat(plaintext, new byte[]{2})); // 0x02: the last (only) record

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), body);
    }

    // ---------------------------------------------------------------- VAPID (RFC 8292)

    /** The Authorization header for one push service ({@code audience} = its origin, e.g. https://fcm.googleapis.com). */
    public static String vapidAuthorization(KeyPair vapid, String audience, String subject, long expiresEpochSeconds)
            throws GeneralSecurityException {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresEpochSeconds + ",\"sub\":\""
                + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(vapid.getPrivate());
        s.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String jwt = header + "." + claims + "." + b64.encodeToString(derToJose(s.sign()));
        return "vapid t=" + jwt + ", k=" + b64.encodeToString(raw((ECPublicKey) vapid.getPublic()));
    }

    /** ECDSA signatures come out of the JDK as DER; JWS wants r and s as two 32-byte numbers. */
    static byte[] derToJose(byte[] der) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset += der[1] & 0x7f;
        }
        int rLen = der[offset + 1];
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLen);
        int sStart = offset + 2 + rLen;
        int sLen = der[sStart + 1];
        byte[] s = Arrays.copyOfRange(der, sStart + 2, sStart + 2 + sLen);
        byte[] out = new byte[64];
        copy32(new BigInteger(1, r), out, 0);
        copy32(new BigInteger(1, s), out, 32);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static void copy32(BigInteger v, byte[] out, int at) {
        byte[] b = v.toByteArray();
        int start = Math.max(0, b.length - 32);
        int len = b.length - start;
        System.arraycopy(b, start, out, at + 32 - len, len);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
