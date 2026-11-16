package com.safesphere.backend.community.service;

import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.config.JwtProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase N3: every secret operation of the community, from ONE secret (COMMUNITY_SECRET) split into four sub-keys by
 * HMAC-SHA256 with fixed labels, so no sub-key reveals another:
 *   roll key   roll_key  = HMAC(roll key,  community code + ":" + normalised roll number)
 *   phone key  phone_key = HMAC(phone key, E.164 number)            (bans only)
 *   code key   code_hash = HMAC(code key,  purpose + ":" + code)     (in-person and re-link codes)
 *   photo key  AES-256-GCM for ID photos waiting for an admin
 *
 * Why not JWT_SECRET: rotating the token secret (for example after a leak) must not break every ban and the
 * one-account-per-roll-number rule. Without COMMUNITY_SECRET (development only) a key derived from JWT_SECRET for this
 * one purpose is used and a warning is logged; ProductionGuard refuses the prod profile without it.
 *
 * Honest limit (viva): roll numbers are short and structured, so someone holding BOTH the database and this secret could
 * recover them by trying every number; either one alone cannot. The secret is backed up apart from the database (N8).
 */
@Component
public class CommunityKeys {

    private static final Logger log = LoggerFactory.getLogger(CommunityKeys.class);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte VERSION = 1;

    private final byte[] rollKey;
    private final byte[] phoneKey;
    private final byte[] codeKey;
    private final byte[] photoKey;
    private final SecureRandom random = new SecureRandom();

    public CommunityKeys(CommunityProperties properties, JwtProperties jwt) {
        byte[] root;
        if (properties.hasOwnSecret()) {
            root = properties.secret().getBytes(StandardCharsets.UTF_8);
        } else {
            if (jwt.secret() == null || jwt.secret().isBlank()) throw new IllegalStateException("JWT_SECRET is required");
            log.warn("COMMUNITY_SECRET is not set: using a development key derived from JWT_SECRET. "
                    + "Set COMMUNITY_SECRET before any real roll number is stored.");
            root = hmac(jwt.secret().getBytes(StandardCharsets.UTF_8), "irl-community-dev-root-v1");
        }
        this.rollKey = hmac(root, "irl-roll-key-v1");
        this.phoneKey = hmac(root, "irl-phone-key-v1");
        this.codeKey = hmac(root, "irl-code-key-v1");
        this.photoKey = hmac(root, "irl-id-photo-key-v1");
    }

    /** The keyed form of an already normalised roll number of [communityCode]. */
    public String rollKey(String communityCode, String normalisedRoll) {
        return hex(hmac(rollKey, communityCode + ":" + normalisedRoll));
    }

    /** The keyed form of an E.164 number (bans). */
    public String phoneKey(String phoneE164) {
        return hex(hmac(phoneKey, phoneE164));
    }

    /** The keyed form of a short code ([purpose] keeps in-person and re-link codes apart). */
    public String codeHash(String purpose, String normalisedCode) {
        return hex(hmac(codeKey, purpose + ":" + normalisedCode));
    }

    /** AES-256-GCM: version byte, 12-byte random nonce, ciphertext + tag. [name] (the file name) is bound as AAD. */
    public byte[] encrypt(byte[] plain, String name) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(photoKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(VERSION).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /** The reverse of [encrypt]; a changed file, a wrong name or another key fails (never returns wrong bytes). */
    public byte[] decrypt(byte[] stored, String name) {
        if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8 || stored[0] != VERSION) {
            throw new IllegalStateException("Not an ID photo file of this version");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(photoKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(name.getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The ID photo could not be decrypted");
        }
    }

    private static byte[] hmac(byte[] key, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
