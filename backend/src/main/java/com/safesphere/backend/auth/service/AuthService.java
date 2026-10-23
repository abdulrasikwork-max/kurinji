package com.safesphere.backend.auth.service;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.LoginRequest;
import com.safesphere.backend.auth.dto.RegisterRequest;
import com.safesphere.backend.common.exception.EmailAlreadyUsedException;
import com.safesphere.backend.common.exception.InvalidCredentialsException;
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
import org.springframework.transaction.annotation.Transactional;

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
    private final Clock clock;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuthThrottle throttle, DeviceSessionService sessions, AgeGate ageGate, Clock clock) {
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
     * Creates the account, then signs the new user in by returning a token straight away.
     * (Design choice: one fewer screen and request than "register, then log in".)
     * [clientAddress] is only used for the in-memory sign-up limit (Phase 7); it is never stored.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request, String clientAddress) {
        throttle.checkRegistration(clientAddress);
        // Phase 13A (D6): 18+ only. The date of birth is checked here and goes no further.
        ageGate.requireAdult(request.dateOfBirth());
        if (exceedsBcryptLimit(request.password())) {
            throw new InvalidRequestException("Password must be at most 72 bytes long");
        }
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }

        User user = new User(
                request.name().trim(),
                email,
                passwordEncoder.encode(request.password())
        );
        user.confirmAge(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        try {
            // saveAndFlush so a race on the unique email fails right here, not later at commit.
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyUsedException();
        }
        return toResponse(user);
    }

    /**
     * Phase 7: refused with 429 (before any password check) while this account or this network address is over its
     * failure limit; unknown e-mails count like known ones, so the limit reveals nothing about which accounts exist.
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String clientAddress) {
        String email = normalizeEmail(request.email());
        throttle.checkSignIn(email, clientAddress);
        Optional<User> found = userRepository.findByEmail(email);

        String hashToCheck = found.map(User::getPasswordHash).orElse(timingHash);
        boolean passwordMatches = !exceedsBcryptLimit(request.password())
                && passwordEncoder.matches(request.password(), hashToCheck);

        // One generic failure for: unknown email, wrong password, disabled account.
        if (found.isEmpty() || !passwordMatches || !found.get().isEnabled()) {
            throttle.signInFailed(email, clientAddress);
            throw new InvalidCredentialsException();
        }
        throttle.signInSucceeded(email);
        AuthResponse response = toResponse(found.get());
        if (request.previousRefreshToken() != null) {
            // Phase 11 C1: the same phone signing in again takes over its old sign-in (and its running SOS).
            String newSession = response.refreshToken().substring(0, response.refreshToken().indexOf('.'));
            sessions.succeed(found.get().getId(), request.previousRefreshToken(), newSession);
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
