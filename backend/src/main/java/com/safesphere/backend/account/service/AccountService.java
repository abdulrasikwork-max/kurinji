package com.safesphere.backend.account.service;

import com.safesphere.backend.account.dto.AccountDtos.DeletionScheduled;
import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.security.JwtService;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 10A: the account's own security actions. Each one needs the account's current password, and wrong passwords
 * for all three together are limited to [AccountProperties.passwordFailures] per window (429), in memory, per account.
 *
 *  - change password: the new hash and a new credentials stamp; every OTHER session ends on its next request
 *    (ActiveAccountCheck); the caller gets a fresh token in the answer. Refused while an SOS or a Safe Walk is running:
 *    a second session (for example someone who knows the password) must not be able to cut a running safety session
 *    off from the server.
 *  - request deletion: never immediate. Refused while an emergency is ACTIVE or within 24 h after one ended (the evidence
 *    deletion rule: whoever forced "I'm safe" cannot force the evidence away on the spot). Then a grace period (7 days)
 *    in which everything keeps working and the phone shows a banner; repeated requests do not move the date.
 *  - cancel deletion: only with the password. Signing in does NOT cancel: a deletion the person wants must not be undone
 *    by signing in on another phone, and one they did not want (forced, or made by someone who knows the password) is
 *    visible in the banner and undone deliberately.
 *
 * Wrong passwords answer 409 with "Password is incorrect" (the evidence deletion convention), never 401: a 401 would
 * make the phone sign out.
 */
@Service
public class AccountService {

    static final String WRONG_PASSWORD = "Password is incorrect";
    static final String TOO_MANY = "Too many wrong passwords. Please wait a few minutes and try again.";

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmergencyEventRepository emergencies;
    private final SafeJourneyRepository journeys;
    private final AccountProperties properties;
    private final Clock clock;
    private final AttemptLimiter failures;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                          EmergencyEventRepository emergencies, SafeJourneyRepository journeys,
                          AccountProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emergencies = emergencies;
        this.journeys = journeys;
        this.properties = properties;
        this.clock = clock;
        this.failures = new AttemptLimiter(properties.passwordFailures(), properties.passwordFailureWindow(),
                properties.maxTrackedKeys(), clock);
    }

    @Transactional
    public AuthResponse changePassword(long userId, String currentPassword, String newPassword) {
        User user = locked(userId);
        if (emergencies.existsByUserIdAndStatus(userId, EmergencyStatus.ACTIVE)
                || journeys.existsByUserIdAndStatus(userId, JourneyStatus.ACTIVE)) {
            throw new ConflictException("You can change your password once your SOS or Safe Walk has ended.");
        }
        requirePassword(user, currentPassword);
        if (tooLong(newPassword)) {
            throw new InvalidRequestException("Password must be at most 72 bytes long");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Choose a new password that is different from the current one");
        }
        user.changePassword(passwordEncoder.encode(newPassword), now());
        users.saveAndFlush(user);
        JwtService.IssuedToken token = jwtService.issueToken(user);
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(user));
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
        requirePassword(user, password);
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
        requirePassword(user, password);
        if (user.getDeletionDueAt() != null) {
            user.cancelDeletion();
            users.saveAndFlush(user);
        }
    }

    /** Forgets finished limiter windows (called by the account deletion job). */
    public void evictExpired() {
        failures.evictExpired();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private User locked(long userId) {
        // The token was checked a moment ago (ActiveAccountCheck); a row gone since then is a signed-out caller.
        return users.findByIdForUpdate(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
    }

    /** 429 before any comparison while over the limit; a wrong password is counted, a right one resets the count. */
    private void requirePassword(User user, String password) {
        String key = String.valueOf(user.getId());
        if (failures.isBlocked(key)) throw new TooManyAttemptsException(TOO_MANY);
        if (password == null || tooLong(password) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            failures.record(key);
            throw new ConflictException(WRONG_PASSWORD);
        }
        failures.reset(key);
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String hours(java.time.Duration duration) {
        long hours = duration.toHours();
        return hours == 1 ? "1 hour" : hours + " hours";
    }
}
