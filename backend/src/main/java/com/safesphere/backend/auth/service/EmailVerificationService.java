package com.safesphere.backend.auth.service;

import com.safesphere.backend.auth.entity.EmailVerificationToken;
import com.safesphere.backend.auth.mail.VerificationMailer;
import com.safesphere.backend.auth.repository.EmailVerificationTokenRepository;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.common.exception.VerificationLinkInvalidException;
import com.safesphere.backend.config.AuthProperties;
import com.safesphere.backend.config.EmailVerificationProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V25: proof that a person controls the e-mail address of their account (docs/EMAIL_VERIFICATION.md).
 *
 * <ul>
 *   <li>A token is 256 random bits (base64url, 43 characters), shown only in the e-mail link. Only its SHA-256 is
 *       stored, one live token per account: a new e-mail replaces it, so the previous link stops working.</li>
 *   <li>Verification consumes the token with one conditional DELETE (1 row for exactly one caller), then marks the
 *       e-mail verified in the same transaction: a link works once, and two simultaneous clicks cannot both count.</li>
 *   <li>Every link that cannot verify answers the same 400; failures are limited per network address.</li>
 *   <li>Resend needs the e-mail AND the password, always answers 202, and sends only for a real, unverified account
 *       whose password matched. It is limited per e-mail (counted for unknown addresses too, so the limit says nothing
 *       about which accounts exist) and per network address. Sending is asynchronous, so the answer time does not
 *       tell whether a message was sent.</li>
 *   <li>Tokens, links and addresses are never logged. Limits are in memory (like the Phase 7 sign-in limits).</li>
 * </ul>
 * Separate from refresh secrets and from any future password-reset token: they only share the random/hash utilities.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    private static final int CLEANUP_BATCH = 100;
    static final String TOO_MANY_RESENDS = "Too many emails asked for. Please wait a while and try again.";
    static final String TOO_MANY_LINK_ATTEMPTS = "Too many attempts. Please wait a few minutes and try again.";

    private final EmailVerificationTokenRepository tokens;
    private final UserRepository users;
    private final VerificationMailer mailer;
    private final Executor mailExecutor;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final EmailVerificationProperties properties;
    private final Clock clock;
    private final AttemptLimiter resendsPerAccount;
    private final AttemptLimiter resendsPerAddress;
    private final AttemptLimiter verifyFailures;
    private final String timingHash;

    public EmailVerificationService(EmailVerificationTokenRepository tokens, UserRepository users,
                                    VerificationMailer mailer,
                                    @Qualifier("verificationMailExecutor") Executor mailExecutor,
                                    PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager,
                                    EmailVerificationProperties properties, AuthProperties auth, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.mailer = mailer;
        this.mailExecutor = mailExecutor;
        this.passwordEncoder = passwordEncoder;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
        int maxKeys = auth.maxTrackedKeys();
        this.resendsPerAccount = new AttemptLimiter(properties.resendsPerAccount(), properties.resendWindow(), maxKeys,
                clock);
        this.resendsPerAddress = new AttemptLimiter(properties.resendsPerAddress(), properties.resendWindow(), maxKeys,
                clock);
        this.verifyFailures = new AttemptLimiter(properties.verifyFailuresPerAddress(), properties.verifyWindow(),
                maxKeys, clock);
        this.timingHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /**
     * Inside the caller's transaction (registration): a new token for [user], replacing any earlier one, and the e-mail
     * queued to go out once that transaction has committed (never for a sign-up that was rolled back).
     */
    public void issueAndSendAfterCommit(User user) {
        String token = issue(user.getId());
        String email = user.getEmail();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(email, token);
                }
            });
        } else {
            send(email, token);
        }
    }

    /**
     * Phase-independent rule for a sign-up with an e-mail that already has an account: an UNVERIFIED account with no
     * live link (its last e-mail expired) may be replaced, so nobody can hold someone else's address for longer than
     * one link lifetime by signing up with it. Verified accounts and tombstones are never replaced.
     */
    public boolean replaceableBySignUp(User existing) {
        if (existing.isEmailVerified() || existing.isErased()) return false;
        Optional<EmailVerificationToken> live = tokens.findById(existing.getId());
        return live.isEmpty() || !live.get().isLive(now());
    }

    /** Removes an unverified account (and its token) before the same address signs up again. No other rows can exist. */
    public void removeUnverified(User existing) {
        tokens.findById(existing.getId()).ifPresent(tokens::delete);
        users.delete(existing);
        users.flush();
    }

    /** POST /api/auth/verify-email: consumes the token and marks the e-mail verified, or the one generic 400. */
    @Transactional
    public void verify(String token, String address) {
        String key = address(address);
        if (verifyFailures.isBlocked(key)) throw new TooManyAttemptsException(TOO_MANY_LINK_ATTEMPTS);
        if (token == null || !TOKEN.matcher(token).matches()) throw failed(key);
        String hash = RefreshSecrets.hash(token);
        LocalDateTime now = now();
        EmailVerificationToken row = tokens.findByTokenHash(hash).orElse(null);
        if (row == null || !row.isLive(now)) throw failed(key);
        long userId = row.getUserId();
        if (tokens.consume(userId, hash, now) != 1) throw failed(key);
        User user = users.findById(userId).orElse(null);
        if (user == null || user.isErased()) throw failed(key);
        user.markEmailVerified(now);
    }

    /** POST /api/auth/verification/resend: always the same answer; see the class comment. */
    public void resend(String rawEmail, String password, String address) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!resendsPerAddress.tryAcquire(address(address))) throw new TooManyAttemptsException(TOO_MANY_RESENDS);
        if (!resendsPerAccount.tryAcquire(RefreshSecrets.hash(email))) {
            throw new TooManyAttemptsException(TOO_MANY_RESENDS);
        }
        Optional<User> found = users.findByEmail(email);
        boolean passwordOk = password != null
                && password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_PASSWORD_BYTES
                && passwordEncoder.matches(password, found.map(User::getPasswordHash).orElse(timingHash));
        if (found.isEmpty() || !passwordOk) return;
        User user = found.get();
        if (user.isEmailVerified() || user.isErased() || !user.isEnabled()) return;
        String token = transactions.execute(status -> issue(user.getId()));
        send(user.getEmail(), token);
    }

    /** The clean-up job: expired tokens, and accounts never verified within unverified-retention. Deletion only. */
    public int cleanUp() {
        LocalDateTime now = now();
        Integer expired = transactions.execute(status -> tokens.deleteExpired(now));
        List<Long> stale = users.findIdsUnverifiedCreatedBefore(now.minus(properties.unverifiedRetention()),
                PageRequest.of(0, CLEANUP_BATCH));
        int removed = 0;
        for (Long id : stale) {
            try {
                transactions.executeWithoutResult(status -> users.findById(id).ifPresent(this::removeUnverified));
                removed++;
            } catch (DataAccessException e) {
                log.warn("Unverified account not removed ({})", e.getClass().getSimpleName());
            }
        }
        if (removed > 0 || (expired != null && expired > 0)) {
            log.info("E-mail verification clean-up: {} expired link(s), {} unverified account(s) removed",
                    expired == null ? 0 : expired, removed);
        }
        return removed;
    }

    /** Drops finished limiter windows (memory bound). */
    public void evictExpiredLimits() {
        resendsPerAccount.evictExpired();
        resendsPerAddress.evictExpired();
        verifyFailures.evictExpired();
    }

    private String issue(long userId) {
        String token = RefreshSecrets.newSecret();
        String hash = RefreshSecrets.hash(token);
        LocalDateTime now = now();
        LocalDateTime expires = now.plus(properties.tokenLifetime());
        EmailVerificationToken row = tokens.findById(userId).orElse(null);
        if (row == null) {
            tokens.save(new EmailVerificationToken(userId, hash, now, expires));
        } else {
            row.replace(hash, now, expires);
        }
        tokens.flush();
        return token;
    }

    private void send(String email, String token) {
        String link = properties.linkBaseUrl() + "/verify-email#t=" + token;
        try {
            mailExecutor.execute(() -> mailer.sendVerification(email, link, properties.tokenLifetime()));
        } catch (RuntimeException e) {
            log.warn("Verification e-mail not queued ({})", e.getClass().getSimpleName());
        }
    }

    private VerificationLinkInvalidException failed(String addressKey) {
        verifyFailures.record(addressKey);
        return new VerificationLinkInvalidException();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String address(String address) {
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
