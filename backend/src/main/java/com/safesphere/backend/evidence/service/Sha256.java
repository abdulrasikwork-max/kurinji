package com.safesphere.backend.evidence.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Lower-case hex everywhere (the database CHECKs require it). */
public final class Sha256 {

    public static final String ZERO = "0".repeat(64);

    private Sha256() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static String ofText(String text) {
        return hex(newDigest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String ofBytes(byte[] bytes) {
        return hex(newDigest().digest(bytes));
    }
}
