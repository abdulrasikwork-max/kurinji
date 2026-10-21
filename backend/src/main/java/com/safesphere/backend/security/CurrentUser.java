package com.safesphere.backend.security;

import com.safesphere.backend.common.exception.UnauthorizedException;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The ONLY way controllers learn who is calling: from the verified JWT ("sub" claim), never from
 * the request body, URL or headers the client controls.
 */
public final class CurrentUser {

    /** Phase 10A.4: the id of this sign-in (one phone's session), kept across renewals. */
    public static final String SESSION_CLAIM = "sid";
    /** Phase 10A.4: when the password was last typed for this sign-in (epoch seconds). */
    public static final String AUTH_TIME_CLAIM = "auth_time";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("Invalid token subject");
        }
    }

    /** The sign-in's session id, or null for a token issued before Phase 10A.4 (they expire within 24 h). */
    public static String sessionId(Jwt jwt) {
        Object value = jwt.getClaims().get(SESSION_CLAIM);
        return value instanceof String s && UUID_PATTERN.matcher(s).matches() ? s : null;
    }

    /** When the password was last typed for this sign-in; tokens without the claim count from their issue time. */
    public static Instant authTime(Jwt jwt) {
        Object value = jwt.getClaims().get(AUTH_TIME_CLAIM);
        if (value instanceof Number n) return Instant.ofEpochSecond(n.longValue());
        return jwt.getIssuedAt() != null ? jwt.getIssuedAt() : Instant.EPOCH;
    }
}
