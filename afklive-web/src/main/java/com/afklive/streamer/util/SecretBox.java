package com.afklive.streamer.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets at rest (stream keys, phone ingest keys) with AES-256-GCM. Stored values look
 * like {@code enc:v1:<base64(iv | ciphertext+tag)>}; anything without that prefix is a value saved
 * before encryption was turned on and is returned as is (it gets encrypted the next time it's saved).
 * <p>
 * The key comes from {@code APP_ENCRYPTION_KEY} (32 random bytes, base64). Without it, values are
 * stored as plain text and an error is logged: set it in production.
 */
@Slf4j
public final class SecretBox {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKeySpec key;
    private static volatile boolean warned;

    private SecretBox() {
    }

    /** Sets the key (base64, 32 bytes). Blank: no encryption. */
    public static void configure(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("APP_ENCRYPTION_KEY must be 32 bytes, base64-encoded (openssl rand -base64 32)");
        }
        key = new SecretKeySpec(raw, "AES");
    }

    public static boolean enabled() {
        return key != null;
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.startsWith(PREFIX)) {
            return plain;
        }
        SecretKeySpec k = key;
        if (k == null) {
            if (!warned) {
                warned = true;
                log.error("APP_ENCRYPTION_KEY is not set: stream keys are being stored unencrypted");
            }
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        SecretKeySpec k = key;
        if (k == null) {
            throw new IllegalStateException("An encrypted secret can't be read: APP_ENCRYPTION_KEY is not set");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("A stored secret couldn't be decrypted (wrong APP_ENCRYPTION_KEY?)", e);
        }
    }
}
