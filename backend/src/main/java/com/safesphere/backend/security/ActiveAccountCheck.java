package com.safesphere.backend.security;

import com.safesphere.backend.auth.repository.DeviceSessionRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Phase 10A.2: the per-request account check. Runs inside the JWT decoder, after the signature, expiry and issuer
 * checks, on EVERY authenticated request (one primary-key lookup). A token is refused (401) when:
 *
 *   - its account no longer exists or was erased            -> error_description "account_deleted"
 *   - its account is disabled                               -> "account_unavailable"
 *   - its credentials stamp ("cca") is not the current one  -> "credentials_changed" (the password was changed)
 *   - its sign-in ("sid") is a device session that ended   -> "session_revoked" (Phase 11 C1: signed out on that
 *     phone, or a refresh secret was reused). A "sid" without a session row (a sign-in from before V19) is accepted.
 *
 * Before Phase 10A a disabled or deleted account's token kept working for up to 24 h on every endpoint except
 * GET /api/users/me, and nothing could end a stolen session. The stamp is compared for EXACT equality (not "issued
 * after"), so a token issued in the same second as a password change, or by a server whose clock was off, is never
 * accepted by mistake. Only the account's own token holder ever learns the reason (the phone uses it to decide whether
 * to remove the account's data); it reveals nothing about any other account.
 */
public class ActiveAccountCheck implements OAuth2TokenValidator<Jwt> {

    public static final String STAMP_CLAIM = "cca";
    public static final String ACCOUNT_DELETED = "account_deleted";
    public static final String ACCOUNT_UNAVAILABLE = "account_unavailable";
    public static final String CREDENTIALS_CHANGED = "credentials_changed";
    public static final String SESSION_REVOKED = "session_revoked";

    private final UserRepository users;
    private final DeviceSessionRepository sessions;

    public ActiveAccountCheck(UserRepository users, DeviceSessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        long userId;
        try {
            userId = Long.parseLong(token.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            return fail(ACCOUNT_UNAVAILABLE);
        }
        Optional<User> found = users.findById(userId);
        if (found.isEmpty() || found.get().isErased()) return fail(ACCOUNT_DELETED);
        User user = found.get();
        if (!user.isEnabled()) return fail(ACCOUNT_UNAVAILABLE);
        Object claim = token.getClaims().get(STAMP_CLAIM);
        Long stamp;
        if (claim == null) {
            stamp = null;
        } else if (claim instanceof Number number) {
            stamp = number.longValue();
        } else {
            return fail(CREDENTIALS_CHANGED);
        }
        if (!Objects.equals(stamp, user.credentialsStamp())) return fail(CREDENTIALS_CHANGED);
        String sessionId = CurrentUser.sessionId(token);
        if (sessionId != null && sessions.existsBySessionIdAndRevokedAtIsNotNull(sessionId)) return fail(SESSION_REVOKED);
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String reason) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", reason, null));
    }
}
