package com.safesphere.backend.auth.service;

import com.safesphere.backend.auth.entity.DeviceSession;
import com.safesphere.backend.auth.entity.SessionRevocation;
import com.safesphere.backend.auth.repository.DeviceSessionRepository;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.security.ActiveAccountCheck;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 11 C1: device sessions and their rotating refresh secrets (docs/PHASE11.md §5).
 *
 *  - [start]: a sign-in (login, register) gets a session; its id becomes the access tokens' "sid".
 *  - [refresh]: a valid secret is replaced by a new one (rotation) and the caller gets a new access token. Also works
 *    when the access token expired days ago: that is its purpose (an SOS must not need the password after idle time).
 *  - The secret just replaced, shown again within [AccountProperties#refreshReuseGrace], is a lost answer on a poor
 *    connection and is rotated once more; shown later, it is REUSE (someone else may hold a copy): the session ends.
 *  - Refused: revoked or idle-expired sessions, deleted or disabled accounts, unknown tokens. Refusals never throw
 *    inside the transaction, so a revocation they cause is committed.
 *  - Password change ends every OTHER session of the account; sign-out ends the caller's.
 */
@Service
public class DeviceSessionService {

    /** The reasons a refresh is refused, sent as the RFC 6750 error_description (the phone decides what to keep). */
    public static final String SESSION_REVOKED = ActiveAccountCheck.SESSION_REVOKED;

    public record Started(String sessionId, String refreshToken, Instant authenticatedAt) {
        @Override
        public String toString() {
            return "Started[redacted]";
        }
    }

    public sealed interface RefreshOutcome {
        record Refreshed(User user, String sessionId, Instant authenticatedAt, String refreshToken) implements RefreshOutcome {
            @Override
            public String toString() {
                return "Refreshed[redacted]";
            }
        }

        /** [reason]: session_revoked, account_deleted or account_unavailable. */
        record Refused(String reason) implements RefreshOutcome {
        }
    }

    private final DeviceSessionRepository sessions;
    private final UserRepository users;
    private final EmergencyEventRepository emergencies;
    private final AccountProperties properties;
    private final Clock clock;

    public DeviceSessionService(DeviceSessionRepository sessions, UserRepository users,
                                EmergencyEventRepository emergencies, AccountProperties properties, Clock clock) {
        this.sessions = sessions;
        this.emergencies = emergencies;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** A new sign-in for [user]: the password was just typed. Runs in the caller's transaction. */
    @Transactional
    public Started start(User user) {
        LocalDateTime now = now();
        return create(user.getId(), UUID.randomUUID().toString(), now, now);
    }

    @Transactional
    public RefreshOutcome refresh(String refreshToken) {
        RefreshSecrets.Parsed parsed = RefreshSecrets.parse(refreshToken);
        if (parsed == null) return new RefreshOutcome.Refused(SESSION_REVOKED);
        DeviceSession session = sessions.findBySessionIdForUpdate(parsed.sessionId()).orElse(null);
        if (session == null || session.isRevoked()) return new RefreshOutcome.Refused(SESSION_REVOKED);
        LocalDateTime now = now();

        boolean current = RefreshSecrets.matches(parsed.secret(), session.getSecretHash());
        boolean previous = !current && RefreshSecrets.matches(parsed.secret(), session.getPreviousSecretHash());
        if (!current && !previous) {
            // A guess or a secret older than the previous one: refused, the session itself is not touched (a stranger
            // who only knows a session id cannot end someone's session).
            return new RefreshOutcome.Refused(SESSION_REVOKED);
        }
        if (previous && !now.isBefore(session.getRotatedAt().plus(properties.refreshReuseGrace()))) {
            session.revoke(SessionRevocation.REUSE_DETECTED, now);
            return new RefreshOutcome.Refused(SESSION_REVOKED);
        }
        if (session.isExpired(now)) {
            session.revoke(SessionRevocation.EXPIRED, now);
            return new RefreshOutcome.Refused(SESSION_REVOKED);
        }
        User user = users.findById(session.getUserId()).orElse(null);
        if (user == null || user.isErased()) return new RefreshOutcome.Refused(ActiveAccountCheck.ACCOUNT_DELETED);
        if (!user.isEnabled()) {
            session.revoke(SessionRevocation.ACCOUNT_DISABLED, now);
            return new RefreshOutcome.Refused(ActiveAccountCheck.ACCOUNT_UNAVAILABLE);
        }
        // V25: an unverified account never gets a session; refuse a refresh anyway (defence in depth).
        if (!user.isEmailVerified()) return new RefreshOutcome.Refused(ActiveAccountCheck.ACCOUNT_UNAVAILABLE);
        String secret = RefreshSecrets.newSecret();
        session.rotate(RefreshSecrets.hash(secret), now, now.plus(properties.refreshIdleTimeout()));
        return new RefreshOutcome.Refreshed(user, session.getSessionId(),
                session.getAuthenticatedAt().toInstant(ZoneOffset.UTC), RefreshSecrets.token(session.getSessionId(), secret));
    }

    /**
     * Ends the caller's session when [refreshToken] is its current (or just replaced) secret. Idempotent and silent:
     * the answer is the same whether or not anything was ended, so it reveals nothing.
     */
    @Transactional
    public void signOut(String refreshToken) {
        RefreshSecrets.Parsed parsed = RefreshSecrets.parse(refreshToken);
        if (parsed == null) return;
        sessions.findBySessionIdForUpdate(parsed.sessionId()).ifPresent(session -> {
            if (RefreshSecrets.matches(parsed.secret(), session.getSecretHash())
                    || RefreshSecrets.matches(parsed.secret(), session.getPreviousSecretHash())) {
                session.revoke(SessionRevocation.SIGNED_OUT, now());
            }
        });
    }

    /**
     * The same phone signed in again with the password (after "sign in to sync") and showed the refresh token of its
     * old sign-in: that old session ends, and an SOS it started (still ACTIVE) moves to [newSessionId], so "I'm safe"
     * from this phone needs no password. Nothing happens unless the token is the current or just-replaced secret of a
     * session of the SAME account (a revoked one too: the password was typed just now).
     */
    @Transactional
    public void succeed(long userId, String previousRefreshToken, String newSessionId) {
        RefreshSecrets.Parsed parsed = RefreshSecrets.parse(previousRefreshToken);
        if (parsed == null || parsed.sessionId().equals(newSessionId)) return;
        DeviceSession old = sessions.findBySessionIdForUpdate(parsed.sessionId()).orElse(null);
        if (old == null || old.getUserId() != userId) return;
        if (!RefreshSecrets.matches(parsed.secret(), old.getSecretHash())
                && !RefreshSecrets.matches(parsed.secret(), old.getPreviousSecretHash())) {
            return;
        }
        old.revoke(SessionRevocation.SIGNED_OUT, now());
        sessions.flush();
        emergencies.moveStartedSession(userId, old.getSessionId(), newSessionId);
    }

    /**
     * A sign-in from before device sessions existed (its token has a "sid" but no row): it gets a session for that same
     * id, so the phone can keep working after the token expires. Null when the session already exists (the phone keeps
     * its secret) or when the token has no session id (it must sign in again).
     */
    @Transactional
    public String adoptIfMissing(long userId, String sessionId, Instant authenticatedAt) {
        if (sessionId == null || sessions.findBySessionId(sessionId).isPresent()) return null;
        LocalDateTime auth = LocalDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC);
        return create(userId, sessionId, auth, now()).refreshToken();
    }

    /** Password change: every other session of [userId] ends; the caller's own one counts as freshly signed in. */
    @Transactional
    public void passwordChanged(long userId, String callerSessionId) {
        LocalDateTime now = now();
        sessions.revokeAllExcept(userId, callerSessionId, SessionRevocation.PASSWORD_CHANGED, now);
        if (callerSessionId != null) {
            sessions.findBySessionIdForUpdate(callerSessionId)
                    .filter(s -> s.getUserId() == userId && !s.isRevoked())
                    .ifPresent(s -> s.reauthenticated(now));
        }
    }

    /** Clean-up (account deletion job): sessions ended longer ago than the retention. Returns how many rows went. */
    @Transactional
    public int purge() {
        return sessions.deleteEndedBefore(now().minus(properties.sessionRetention()));
    }

    private Started create(long userId, String sessionId, LocalDateTime authenticatedAt, LocalDateTime now) {
        String secret = RefreshSecrets.newSecret();
        DeviceSession session = new DeviceSession(sessionId, userId, RefreshSecrets.hash(secret), authenticatedAt, now,
                now.plus(properties.refreshIdleTimeout()));
        sessions.saveAndFlush(session);
        return new Started(sessionId, RefreshSecrets.token(sessionId, secret), authenticatedAt.toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
