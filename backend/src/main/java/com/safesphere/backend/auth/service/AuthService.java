package com.safesphere.backend.auth.service;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.LoginRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.PhoneChallengeResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.StartRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.VerifyRequest;
import com.safesphere.backend.auth.dto.RegisterRequest;
import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.auth.phone.PhoneVerificationService;
import com.safesphere.backend.common.exception.EmailAlreadyUsedException;
import com.safesphere.backend.common.exception.InvalidCredentialsException;
import com.safesphere.backend.common.exception.PhoneVerificationException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.SessionRefusedException;
import com.safesphere.backend.security.JwtService;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import com.safesphere.backend.user.service.AgeGate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The business rules of registering and logging in. Controllers only call this class. */
@Service
public class AuthService {

    /** BCrypt only looks at the first 72 bytes of a password. */
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * A real BCrypt hash of a throwaway string. When someone logs in with an unknown email we still
     * run one BCrypt comparison against it, so "unknown email" and "wrong password" take about the
     * same time and cannot be told apart by timing.
     */
    private final String timingHash;

    private final AuthThrottle throttle;
    private final DeviceSessionService sessions;
    private final AgeGate ageGate;
    private final PhoneVerificationService phoneVerification;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuthThrottle throttle, DeviceSessionService sessions, AgeGate ageGate,
                       PhoneVerificationService phoneVerification, PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.phoneVerification = phoneVerification;
        this.transactions = new TransactionTemplate(transactionManager);
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.sessions = sessions;
        this.ageGate = ageGate;
        this.clock = clock;
        this.timingHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /**
     * Phase 17B: creates the account (not activated, no session) and sends a code to its phone; the answer carries the
     * challenge the app needs for POST /api/auth/phone/verify. Replaces V25's e-mail link (no mail server = the account
     * could never be used). An account never activated and with no verification in progress may be replaced by a new
     * sign-up with the same e-mail; any other existing account answers 409 as before. The code is sent after the commit.
     * [clientAddress] is only used for the in-memory limits; it is never stored.
     */
    public PhoneChallengeResponse register(RegisterRequest request, String clientAddress) {
        throttle.checkRegistration(clientAddress);
        // Phase 13A (D6): 18+ only. The date of birth is checked here and goes no further.
        ageGate.requireAdult(request.dateOfBirth());
        if (exceedsBcryptLimit(request.password())) {
            throw new InvalidRequestException("Password must be at most 72 bytes long");
        }
        String phone = PhoneNumbers.normalize(request.phone());
        if (phone == null) throw PhoneVerificationException.invalidNumber();
        phoneVerification.requireStartAllowed(clientAddress);
        String email = normalizeEmail(request.email());
        String passwordHash = passwordEncoder.encode(request.password()); // slow: outside the transaction
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        PhoneVerificationService.Opened opened = transactions.execute(status -> {
            Optional<User> existing = userRepository.findByEmail(email);
            if (existing.isPresent()) {
                if (!phoneVerification.replaceableBySignUp(existing.get())) throw new EmailAlreadyUsedException();
                phoneVerification.removeUnactivated(existing.get());
            }
            User user = new User(request.name().trim(), email, passwordHash);
            user.setUnverifiedPhone(phone);
            user.confirmAge(now);
            try {
                // saveAndFlush so a race on the unique email fails right here (the whole transaction rolls back).
                user = userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                throw new EmailAlreadyUsedException();
            }
            return phoneVerification.open(user, phone);
        });
        return phoneVerification.deliver(opened);
    }

    /**
     * Phase 17B: an account with the right password but no verified phone (an interrupted sign-up, or an account from
     * before 17B) gets a verification: for its number, or for [StartRequest.phone] if given (required when the account
     * has none). Wrong passwords count exactly like failed sign-ins.
     */
    public PhoneChallengeResponse startPhoneVerification(StartRequest request, String clientAddress) {
        String phone = null;
        if (request.phone() != null && !request.phone().isBlank()) {
            phone = PhoneNumbers.normalize(request.phone());
            if (phone == null) throw PhoneVerificationException.invalidNumber();
        }
        User owner = checkPassword(request.email(), request.password(), clientAddress);
        phoneVerification.requireStartAllowed(clientAddress);
        String chosen = phone;
        PhoneVerificationService.Opened opened = transactions.execute(status -> {
            User user = userRepository.findById(owner.getId()).orElseThrow(InvalidCredentialsException::new);
            if (user.isPhoneVerified()) throw PhoneVerificationException.alreadyVerified();
            if (chosen != null) user.setUnverifiedPhone(chosen);
            return phoneVerification.open(user, chosen != null ? chosen : user.getPhoneE164());
        });
        return phoneVerification.deliver(opened);
    }

    /** Phase 17B: the right code activates the account (if new) and signs this phone in at once. */
    public AuthResponse verifyPhone(VerifyRequest request, String clientAddress) {
        User user = phoneVerification.verify(request.challenge(), request.code(), clientAddress);
        throttle.signInSucceeded(user.getEmail());
        return toResponse(user);
    }

    /**
     * Phase 7: refused with 429 (before any password check) while this account or this network address is over its
     * failure limit; unknown e-mails count like known ones, so the limit reveals nothing about which accounts exist.
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String clientAddress) {
        User user = checkPassword(request.email(), request.password(), clientAddress);
        // Phase 17B: the right password, but no verified phone yet: no session. Only the password holder learns this;
        // the app then asks for the code (POST /api/auth/phone/start). Existing sessions of older accounts keep working.
        if (!user.isPhoneVerified()) throw PhoneVerificationException.notVerified();
        AuthResponse response = toResponse(user);
        if (request.previousRefreshToken() != null) {
            // Phase 11 C1: the same phone signing in again takes over its old sign-in (and its running SOS).
            String newSession = response.refreshToken().substring(0, response.refreshToken().indexOf('.'));
            sessions.succeed(user.getId(), request.previousRefreshToken(), newSession);
        }
        return response;
    }

    /**
     * Phase 11 C1: a new access token (and a new refresh secret) for a device session, without the password. This is
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

    /**
     * The password check shared by sign-in and phone/start: one generic 401 for an unknown e-mail, a wrong password and
     * a disabled account, with the same BCrypt work in each case (timing), counted by the Phase 7 limits.
     */
    private User checkPassword(String rawEmail, String password, String clientAddress) {
        String email = normalizeEmail(rawEmail);
        throttle.checkSignIn(email, clientAddress);
        Optional<User> found = userRepository.findByEmail(email);
        String hashToCheck = found.map(User::getPasswordHash).orElse(timingHash);
        boolean passwordMatches = !exceedsBcryptLimit(password) && passwordEncoder.matches(password, hashToCheck);
        if (found.isEmpty() || !passwordMatches || !found.get().isEnabled()) {
            throttle.signInFailed(email, clientAddress);
            throw new InvalidCredentialsException();
        }
        throttle.signInSucceeded(email);
        return found.get();
    }

    /** A new sign-in: a device session, and an access token carrying its id. */
    private AuthResponse toResponse(User user) {
        DeviceSessionService.Started session = sessions.start(user);
        JwtService.IssuedToken token = jwtService.issueToken(user, session.sessionId(), session.authenticatedAt());
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(user),
                session.refreshToken());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
    }
}
