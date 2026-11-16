package com.safesphere.backend.account.service;

import com.safesphere.backend.account.dto.AccountDtos.DeletionScheduled;
import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.CodeChallengeResponse;
import com.safesphere.backend.auth.service.DeviceSessionService;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.NameLockedException;
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
import com.safesphere.backend.user.service.RealNamePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 10A, reworked in N2: the account's own actions. The sensitive ones need a fresh code texted to the account's own
 * number ([CodeGuard], decision N-7; it replaced the password: wrong codes for all of them together are limited, 429; a
 * wrong one answers 409, never 401). Every refusal is checked BEFORE the code, so a refused action does not use it up.
 *
 *  - sign out the other phones (N-26, what a password change did before N2): a new credentials stamp; every OTHER
 *    session ends on its next request (ActiveAccountCheck); the caller gets a fresh token for the SAME sign-in. Refused
 *    while an SOS or a Safe Walk is running: a second session must not be able to cut a running safety session off
 *    from the server.
 *  - change the real name (N-24): no code (it changes nothing about access); locked after the college ID check in N3.
 *  - renew the token (10A.4): a fresh 24 h token for the same sign-in, without a new code, while the sign-in is younger
 *    than app.account.session-max-age (30 days) or ALWAYS while an SOS or a Safe Walk is running (a long emergency must
 *    never lose its server connection because a token ran out). Revocation still ends a renewed token.
 *  - request deletion: never immediate. Refused while an emergency is ACTIVE or within 24 h after one ended (whoever
 *    forced "I'm safe" cannot force the account away on the spot). Then a grace period (7 days) in which everything
 *    keeps working and the phone shows a banner; repeated requests do not move the date.
 *  - cancel deletion: only with a code. Signing in does NOT cancel: a deletion the person wants must not be undone by
 *    signing in on another phone, and one they did not want is visible in the banner and undone deliberately.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final JwtService jwtService;
    private final EmergencyEventRepository emergencies;
    private final SafeJourneyRepository journeys;
    private final AccountProperties properties;
    private final Clock clock;
    private final CodeGuard codes;
    private final DeviceSessionService sessions;

    public AccountService(UserRepository users, JwtService jwtService, EmergencyEventRepository emergencies,
                          SafeJourneyRepository journeys, AccountProperties properties, Clock clock, CodeGuard codes,
                          DeviceSessionService sessions) {
        this.sessions = sessions;
        this.users = users;
        this.jwtService = jwtService;
        this.emergencies = emergencies;
        this.journeys = journeys;
        this.properties = properties;
        this.clock = clock;
        this.codes = codes;
    }

    /** N2: texts a fresh confirmation code to the caller's own verified number (POST /api/users/me/confirmation). */
    public CodeChallengeResponse startConfirmation(long userId, String address) {
        User user = users.findById(userId)
                .filter(u -> u.isEnabled() && !u.isErased() && u.isPhoneVerified())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        return codes.start(user, address);
    }

    /**
     * N2 (N-26): every other sign-in of the account ends; the caller keeps its sign-in with a fresh token. [sessionId] =
     * the caller's sign-in (kept in the new token); null for an old token (a new id is made).
     */
    @Transactional
    public AuthResponse signOutOtherPhones(long userId, String sessionId, String challenge, String code) {
        User user = locked(userId);
        if (safetySessionRunning(userId)) {
            throw new ConflictException("You can sign out your other phones once your SOS or Safe Walk has ended.");
        }
        codes.require(user, challenge, code);
        user.signOutEverywhereElse(now());
        users.saveAndFlush(user);
        String session = sessionId != null ? sessionId : UUID.randomUUID().toString();
        // Phase 11 C1: every other device session ends too (their refresh secrets stop working, not only their tokens).
        sessions.othersSignedOut(userId, sessionId);
        String refreshToken = sessions.adoptIfMissing(userId, session, clock.instant());
        return response(user, jwtService.issueToken(user, session, clock.instant()), refreshToken);
    }

    /** N2 (N-24): the person corrects their real name. Answers the account as GET /api/users/me does. */
    @Transactional
    public UserResponse changeName(long userId, String rawName) {
        User user = locked(userId);
        if (user.isNameLocked()) throw new NameLockedException(); // Phase N3 (N-24): checked against the college ID
        String name = RealNamePolicy.clean(rawName);
        RealNamePolicy.Result check = RealNamePolicy.check(name);
        if (check != RealNamePolicy.Result.OK) throw new InvalidRequestException(RealNamePolicy.message(check));
        user.rename(name);
        return UserResponse.from(users.saveAndFlush(user));
    }

    /**
     * Phase 10A.4: a fresh token for the caller's sign-in. 409 when the sign-in is older than the maximum session age
     * and no SOS or Safe Walk is running: the current token keeps working until it expires, then the person signs in.
     */
    @Transactional
    public AuthResponse renewToken(long userId, String sessionId, Instant authTime) {
        User user = users.findById(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        boolean fresh = !authTime.plus(properties.sessionMaxAge()).isBefore(clock.instant());
        if (!fresh && !safetySessionRunning(userId)) {
            throw new ConflictException("Please sign in again on this phone.");
        }
        String session = sessionId != null ? sessionId : UUID.randomUUID().toString();
        // Phase 11 C1: a sign-in from before device sessions gets one now (once), so it survives the token's expiry.
        String refreshToken = sessions.adoptIfMissing(userId, sessionId, authTime);
        return response(user, jwtService.issueToken(user, session, authTime), refreshToken);
    }

    @Transactional
    public DeletionScheduled requestDeletion(long userId, String challenge, String code) {
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
        codes.require(user, challenge, code);
        if (user.getDeletionDueAt() == null) {
            user.requestDeletion(now, now.plus(properties.deletionGrace()));
            users.saveAndFlush(user);
        }
        return new DeletionScheduled(user.getDeletionRequestedAt().toInstant(ZoneOffset.UTC),
                user.getDeletionDueAt().toInstant(ZoneOffset.UTC));
    }

    /** Idempotent: with the right code, a pending request is withdrawn; without one nothing changes. */
    @Transactional
    public void cancelDeletion(long userId, String challenge, String code) {
        User user = locked(userId);
        codes.require(user, challenge, code);
        if (user.getDeletionDueAt() != null) {
            user.cancelDeletion();
            users.saveAndFlush(user);
        }
    }

    /** Forgets finished limiter windows (called by the account deletion job). */
    public void evictExpired() {
        codes.evictExpired();
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

    private static AuthResponse response(User user, JwtService.IssuedToken token, String refreshToken) {
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(user), refreshToken);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String hours(java.time.Duration duration) {
        long hours = duration.toHours();
        return hours == 1 ? "1 hour" : hours + " hours";
    }
}
