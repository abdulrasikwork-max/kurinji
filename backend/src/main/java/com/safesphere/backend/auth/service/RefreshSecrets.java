package com.safesphere.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Phase 11 C1: the refresh token format and its hashing, in one place.
 *
 * A refresh token is "<session id>.<secret>": the session id (a random UUID, also the access tokens' "sid") finds the
 * row; the secret is 32 random bytes (256 bits) in base64url without padding. Only SHA-256(secret) is stored. A fast
 * hash is right here (unlike passwords): the secret is random, so there is nothing to guess. Hashes are compared in
 * constant time ([matches]) so the answer time says nothing about how much of a guess was right.
 */
public final class RefreshSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    private static final Pattern TOKEN = Pattern.compile(
            "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{43})$");

    private RefreshSecrets() {
    }

    /** A session id and secret read from a refresh token. */
    public record Parsed(String sessionId, String secret) {
        @Override
        public String toString() {
            return "Parsed[redacted]";
        }
    }

    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String token(String sessionId, String secret) {
        return sessionId + "." + secret;
    }

    /** Null for anything that is not exactly a refresh token of this server's shape. */
    public static Parsed parse(String token) {
        if (token == null) return null;
        var matcher = TOKEN.matcher(token);
        return matcher.matches() ? new Parsed(matcher.group(1), matcher.group(2)) : null;
    }

    /** Lower-case hex SHA-256 of the secret (what V19 stores). */
    public static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Constant-time comparison of a secret with a stored hash (false for a missing hash). */
    public static boolean matches(String secret, String storedHash) {
        if (storedHash == null) return false;
        byte[] presented = hash(secret).getBytes(StandardCharsets.US_ASCII);
        byte[] stored = storedHash.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(presented, stored);
    }
}
