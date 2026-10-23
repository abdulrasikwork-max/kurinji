package com.safesphere.backend.social.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Phase 13A: one-time invite codes. 128 random bits written in Crockford's base 32 (26 characters, no I, L, O or U, so
 * the code survives being read aloud or retyped), shown to the inviter once. Only the SHA-256 (lower-case hex) of the
 * normalised code is stored: a fast hash is enough for 128 random bits, and a stolen table gives no usable codes.
 */
public final class InviteCodes {

    public static final int LENGTH = 26;
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    public static String newCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    /**
     * Upper case, spaces and hyphens removed, and the look-alikes Crockford allows mapped (O to 0, I and L to 1).
     * Returns null when the result is not a possible code (wrong length or other characters).
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(LENGTH);
        for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == ' ' || c == '-') continue;
            char mapped = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };
            if (indexOf(mapped) < 0) return null;
            out.append(mapped);
        }
        return out.length() == LENGTH ? out.toString() : null;
    }

    public static String hash(String normalizedCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedCode.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** 16 bytes = 128 bits → 26 characters of 5 bits (the last one carries 3). */
    static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder(LENGTH);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        return out.toString();
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) if (ALPHABET[i] == c) return i;
        return -1;
    }
}
