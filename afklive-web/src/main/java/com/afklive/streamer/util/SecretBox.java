package com.afklive.streamer.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encrypts secrets at rest (stream keys, phone ingest keys, run specs that contain them) with
 * AES-256-GCM. Stored values look like {@code enc:v2:<key id>:<base64(iv | ciphertext+tag)>}.
 * <ul>
 *   <li>The current key ({@code APP_ENCRYPTION_KEY}) encrypts; older keys ({@code APP_ENCRYPTION_OLD_KEYS})
 *       still decrypt, so a key can be rotated: add the old one to the old keys, set the new one, and
 *       {@code SecretBackfill} re-encrypts everything with the new key.</li>
 *   <li>Values without the prefix were saved before encryption; they read as is and are encrypted
 *       by the backfill at startup.</li>
 * </ul>
 */
public final class SecretBox {

    static final String PREFIX = "enc:v2:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile String currentId;
    private static volatile Map<String, SecretKeySpec> keys = Map.of();

    private SecretBox() {
    }

    /** Sets the current key and any older ones (base64, 32 bytes each). The current key is required. */
    public static void configure(String currentBase64, List<String> oldBase64) {
        if (currentBase64 == null || currentBase64.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY is not set. Stream keys must be encrypted at rest: "
                    + "generate one with `openssl rand -base64 32` and set it (keep it safe; it can't be recovered).");
        }
        Map<String, SecretKeySpec> ring = new LinkedHashMap<>();
        SecretKeySpec current = spec(currentBase64);
        String id = idOf(current);
        ring.put(id, current);
        if (oldBase64 != null) {
            for (String old : oldBase64) {
                if (old != null && !old.isBlank()) {
                    SecretKeySpec k = spec(old);
                    ring.putIfAbsent(idOf(k), k);
                }
            }
        }
        keys = Map.copyOf(ring);
        currentId = id;
    }

    private static SecretKeySpec spec(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("Encryption keys must be 32 bytes, base64-encoded (openssl rand -base64 32)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** A short public id for a key (so a value says which key encrypted it). */
    private static String idOf(SecretKeySpec k) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(k.getEncoded()), 0, 4);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean enabled() {
        return currentId != null;
    }

    /** True if the stored value is encrypted with the current key (nothing to backfill). */
    public static boolean isCurrent(String stored) {
        return stored != null && stored.startsWith(PREFIX + currentId + ":");
    }

    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        if (plain.startsWith(PREFIX)) {
            return plain; // already encrypted (never double-encrypt)
        }
        String id = currentId;
        if (id == null) {
            throw new IllegalStateException("Encryption isn't configured (APP_ENCRYPTION_KEY)");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(id), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + id + ":" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored; // saved before encryption; the backfill encrypts it
        }
        int colon = stored.indexOf(':', PREFIX.length());
        if (colon < 0) {
            throw new IllegalStateException("A stored secret is malformed");
        }
        String id = stored.substring(PREFIX.length(), colon);
        SecretKeySpec k = keys.get(id);
        if (k == null) {
            throw new IllegalStateException("A stored secret was encrypted with a key that isn't configured (key id " + id
                    + "). Add that key to APP_ENCRYPTION_OLD_KEYS.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(colon + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("A stored secret couldn't be decrypted", e);
        }
    }
}
