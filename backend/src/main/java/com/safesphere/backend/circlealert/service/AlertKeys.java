package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.config.AlertProperties;
import com.safesphere.backend.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * N6: the one-way key of a phone number ("+91XXXXXXXXXX" → 64 hex characters, HMAC-SHA256). The stop list and the
 * per-number daily limit compare keys, so the database never holds a readable recipient number. The key is ALERT_SECRET;
 * in development, when it is empty, a key derived from JWT_SECRET for this one purpose (the prod profile refuses that:
 * rotating the token key must never silently empty the stop list).
 */
@Component
public class AlertKeys {

    private final byte[] key;

    public AlertKeys(AlertProperties properties, JwtProperties jwt) {
        this.key = properties.secret().isEmpty()
                ? hmac(jwt.secret().getBytes(StandardCharsets.UTF_8), "irl-alert-numbers-v1")
                : properties.secret().getBytes(StandardCharsets.UTF_8);
    }

    public String of(String phoneE164) {
        return HexFormat.of().formatHex(hmac(key, phoneE164));
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
