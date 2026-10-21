package com.safesphere.backend.account.service;

import com.safesphere.backend.account.dto.AccountDtos.DeletionScheduled;
import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import com.safesphere.backend.security.JwtService;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 10A: the account's own security actions. Each one needs the account's current password ([PasswordGuard]:
 * wrong passwords for all of them together are limited, 429; a wrong one answers 409, never 401).
 *
 *  - change password: the new hash and a new credentials stamp; every OTHER session ends on its next request
 *    (ActiveAccountCheck); the caller gets a fresh token for the SAME sign-in (its session id is kept). Refused while an
 *    SOS or a Safe Walk is running: a second session (for example someone who knows the password) must not be able to
 *    cut a running safety session off from the server.
 *  - renew the token (10A.4): a fresh 24 h token for the same sign-in, without the password, while the sign-in is
 *    younger than app.account.session-max-age (30 days) or ALWAYS while an SOS or a Safe Walk is running (a long
 *    emergency must never lose its server connection because a token ran out). Revocation (password change,
 *    disabling, deletion) still ends a renewed token, so renewal does not weaken it.
 *  - request deletion: never immediate. Refused while an emergency is ACTIVE or within 24 h after one ended (the evidence
 *    deletion rule: whoever forced "I'm safe" cannot force the evidence away on the spot). Then a grace period (7 days)
 *    in which everything keeps working and the phone shows a banner; repeated requests do not move the date.
 *  - cancel deletion: only with the password. Signing in does NOT cancel: a deletion the person wants must not be undone
 *    by signing in on another phone, and one they did not want (forced, or made by someone who knows the password) is
 *    visible in the banner and undone deliberately.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmergencyEventRepository emergencies;
    private final SafeJourneyRepository journeys;
    private final AccountProperties properties;
    private final Clock clock;
    private final PasswordGuard passwords;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                          EmergencyEventRepository emergencies, SafeJourneyRepository journeys,
                          AccountProperties properties, Clock clock, PasswordGuard passwords) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emergencies = emergencies;
        this.journeys = journeys;
        this.properties = properties;
        this.clock = clock;
        this.passwords = passwords;
    }

    /** [sessionId] = the caller's sign-in (kept in the new token); null for an old token (a new id is made). */
    @Transactional
    public AuthResponse changePassword(long userId, String sessionId, String currentPassword, String newPassword) {
        User user = locked(userId);
        if (safetySessionRunning(userId)) {
            throw new ConflictException("You can change your password once your SOS or Safe Walk has ended.");
        }
        passwords.require(user, currentPassword);
        if (PasswordGuard.tooLong(newPassword)) {
            throw new InvalidRequestException("Password must be at most 72 bytes long");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Choose a new password that is different from the current one");
        }
        user.changePassword(passwordEncoder.encode(newPassword), now());
        users.saveAndFlush(user);
        String session = sessionId != null ? sessionId : UUID.randomUUID().toString();
        return response(user, jwtService.issueToken(user, session, clock.instant()));
    }

    /**
     * Phase 10A.4: a fresh token for the caller's sign-in. 409 when the sign-in is older than the maximum session age
     * and no SOS or Safe Walk is running: the current token keeps working until it expires, then the person signs in.
     */
    @Transactional(readOnly = true)
    public AuthResponse renewToken(long userId, String sessionId, Instant authTime) {
        User user = users.findById(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        boolean fresh = !authTime.plus(properties.sessionMaxAge()).isBefore(clock.instant());
        if (!fresh && !safetySessionRunning(userId)) {
            throw new ConflictException("Please sign in again on this phone.");
        }
        String session = sessionId != null ? sessionId : UUID.randomUUID().toString();
        return response(user, jwtService.issueToken(user, session, authTime));
    }

    @Transactional
    public DeletionScheduled requestDeletion(long userId, String password) {
        User user = locked(userId);
        if (emergencies.existsByUserIdAndStatus(userId, EmergencyStatus.ACTIVE)) {
            throw new ConflictException("Your account cannot be deleted while an emergency is active.");
        }
        LocalDateTime now = now();
        LocalDateTime lastEnded = emergencies.lastEndedAt(userId).orElse(null);
        if (lastEnded != null && now.isBefore(lastEnded.plus(properties.deletionProtection()))) {
            throw new ConflictException("Your account can be deleted " + hours(properties.deletionProtection())
                    + " after your last emergency ended.");
        }
        passwords.require(user, password);
        if (user.getDeletionDueAt() == null) {
            user.requestDeletion(now, now.plus(properties.deletionGrace()));
            users.saveAndFlush(user);
        }
        return new DeletionScheduled(user.getDeletionRequestedAt().toInstant(ZoneOffset.UTC),
                user.getDeletionDueAt().toInstant(ZoneOffset.UTC));
    }

    /** Idempotent: with the right password, a pending request is withdrawn; without one nothing changes. */
    @Transactional
    public void cancelDeletion(long userId, String password) {
        User user = locked(userId);
        passwords.require(user, password);
        if (user.getDeletionDueAt() != null) {
            user.cancelDeletion();
            users.saveAndFlush(user);
        }
    }

    /** Forgets finished limiter windows (called by the account deletion job). */
    public void evictExpired() {
        passwords.evictExpired();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private boolean safetySessionRunning(long userId) {
        return emergencies.existsByUserIdAndStatus(userId, EmergencyStatus.ACTIVE)
                || journeys.existsByUserIdAndStatus(userId, JourneyStatus.ACTIVE);
    }

    private User locked(long userId) {
        // The token was checked a moment ago (ActiveAccountCheck); a row gone since then is a signed-out caller.
        return users.findByIdForUpdate(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
    }

    private static AuthResponse response(User user, JwtService.IssuedToken token) {
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(user));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String hours(java.time.Duration duration) {
        long hours = duration.toHours();
        return hours == 1 ? "1 hour" : hours + " hours";
    }
}
