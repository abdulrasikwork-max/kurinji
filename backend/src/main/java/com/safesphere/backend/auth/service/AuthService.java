package com.safesphere.backend.auth.service;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.CodeChallengeResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.SignUpRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.SignUpTicket;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.VerifyRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.VerifyResponse;
import com.safesphere.backend.auth.phone.PhoneCodeService;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.PhoneVerificationException;
import com.safesphere.backend.common.exception.SessionRefusedException;
import com.safesphere.backend.security.JwtService;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import com.safesphere.backend.user.service.AgeGate;
import com.safesphere.backend.user.service.RealNamePolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N2: the business rules of signing in and signing up, by phone number and code only (decision N-7: no e-mail,
 * no password). Controllers only call this class.
 *
 * <ol>
 *   <li>[startSignIn]: a code is texted to the number, with the same answer whether or not it has an account.</li>
 *   <li>[verify]: the right code signs in the number's account, or (no account yet) gives a one-use sign-up ticket.</li>
 *   <li>[signUp]: ticket + real name + date of birth (18+, never stored) create the account and sign it in.</li>
 * </ol>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthThrottle throttle;
    private final DeviceSessionService sessions;
    private final AgeGate ageGate;
    private final PhoneCodeService phoneCodes;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuthService(UserRepository userRepository, JwtService jwtService, AuthThrottle throttle,
                       DeviceSessionService sessions, AgeGate ageGate, PhoneCodeService phoneCodes,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.sessions = sessions;
        this.ageGate = ageGate;
        this.phoneCodes = phoneCodes;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** POST /api/auth/phone/start. [clientAddress] is only used for the in-memory limits; it is never stored. */
    public CodeChallengeResponse startSignIn(String phone, String clientAddress) {
        return phoneCodes.startSignIn(phone, clientAddress);
    }

    /**
     * POST /api/auth/phone/verify. The number's account: a new sign-in (Phase 11 C1: with [VerifyRequest
     * #previousRefreshToken] of the same account, the old sign-in ends and its running SOS moves to the new one). No
     * account: a sign-up ticket.
     */
    public VerifyResponse verify(VerifyRequest request, String clientAddress) {
        PhoneCodeService.SignInOutcome outcome = phoneCodes.verifySignIn(request.challenge(), request.code(),
                clientAddress);
        if (outcome instanceof PhoneCodeService.SignInOutcome.NewNumber fresh) {
            return VerifyResponse.signUpNeeded(new SignUpTicket(fresh.ticket(), fresh.phoneHint(),
                    fresh.expiresInSeconds()));
        }
        User user = ((PhoneCodeService.SignInOutcome.Existing) outcome).user();
        AuthResponse response = transactions.execute(status -> {
            AuthResponse started = toResponse(user);
            if (request.previousRefreshToken() != null) {
                String newSession = started.refreshToken().substring(0, started.refreshToken().indexOf('.'));
                sessions.succeed(user.getId(), request.previousRefreshToken(), newSession);
            }
            return started;
        });
        return VerifyResponse.signedIn(response);
    }

    /**
     * POST /api/auth/sign-up: the ticket (one use), the person's real name and an adult date of birth (Phase 13A, D6:
     * checked and discarded) create the account, whose number is verified from the first moment, and sign it in.
     * Checked before the ticket is touched, so a refused name or age leaves the ticket usable for a corrected try.
     */
    public AuthResponse signUp(SignUpRequest request, String clientAddress) {
        throttle.checkRegistration(clientAddress);
        ageGate.requireAdult(request.dateOfBirth());
        String name = RealNamePolicy.clean(request.name());
        RealNamePolicy.Result nameCheck = RealNamePolicy.check(name);
        if (nameCheck != RealNamePolicy.Result.OK) throw new InvalidRequestException(RealNamePolicy.message(nameCheck));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            return transactions.execute(status -> {
                String phone = phoneCodes.consumeTicket(request.ticket());
                if (userRepository.existsByPhoneE164AndPhoneVerifiedAtIsNotNull(phone)) {
                    throw PhoneVerificationException.inUse();
                }
                User user = new User(name, phone, now);
                user.confirmAge(now);
                // saveAndFlush: a race on the same number fails right here (uk_users_verified_phone), and the whole
                // transaction rolls back, ticket included.
                user = userRepository.saveAndFlush(user);
                return toResponse(user);
            });
        } catch (DataIntegrityViolationException e) {
            throw PhoneVerificationException.inUse();
        }
    }

    /**
     * Phase 11 C1: a new access token (and a new refresh secret) for a device session, without a new code. This is
     * what keeps an SOS on a phone that was not opened for days connected to the server. A refusal is a 401 whose
     * WWW-Authenticate says why; the refusal's own changes (for example a revocation after reuse) are already saved.
     */
    public AuthResponse refresh(String refreshToken) {
        DeviceSessionService.RefreshOutcome outcome = sessions.refresh(refreshToken);
        if (outcome instanceof DeviceSessionService.RefreshOutcome.Refused refused) {
            throw new SessionRefusedException(refused.reason());
        }
        var refreshed = (DeviceSessionService.RefreshOutcome.Refreshed) outcome;
        JwtService.IssuedToken token = jwtService.issueToken(refreshed.user(), refreshed.sessionId(),
                refreshed.authenticatedAt());
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(refreshed.user()),
                refreshed.refreshToken());
    }

    /** Phase 11 C1: ends the caller's device session (idempotent, same answer whatever happened). */
    public void signOut(String refreshToken) {
        sessions.signOut(refreshToken);
    }

    /** A new sign-in: a device session, and an access token carrying its id. */
    private AuthResponse toResponse(User user) {
        DeviceSessionService.Started session = sessions.start(user);
        JwtService.IssuedToken token = jwtService.issueToken(user, session.sessionId(), session.authenticatedAt());
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(user),
                session.refreshToken());
    }
}
