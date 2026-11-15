package com.safesphere.backend.auth.phone;

import com.safesphere.backend.auth.dto.PhoneVerificationDtos.CodeChallengeResponse;
import com.safesphere.backend.auth.service.RefreshSecrets;
import com.safesphere.backend.common.exception.PhoneVerificationException;
import com.safesphere.backend.config.AuthProperties;
import com.safesphere.backend.config.JwtProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N2: proof that a person holds a phone number, the ONLY way into IRL (no e-mail, no password; replaces 17B's
 * per-account verification). The code rules of 17B are kept unchanged:
 *
 * <ul>
 *   <li>The app holds a challenge token (256 random bits); only its SHA-256 is stored, one row per number and purpose.</li>
 *   <li>A code is 6 random digits ([SecureRandom]), valid [OtpProperties#codeLifetime]; only
 *       HMAC-SHA256(server key, purpose | number | code) is stored, compared in constant time. Codes are never logged.</li>
 *   <li>Wrong codes: [OtpProperties#maxAttempts] per code (then ask for a new one), counted under a row lock, and per
 *       network address across numbers.</li>
 *   <li>Sending: a cooldown between codes, a cap per challenge, and a cap per number per 24 h (anti SMS bombing). The
 *       SMS goes out AFTER the database work committed; if the provider refuses it, the send is undone.</li>
 *   <li>Starting a sign-in answers the same whether or not the number has an account (and sends a code either way):
 *       only the person who then types the right code learns which it was.</li>
 *   <li>The right code for a number without an account gives a sign-up ticket (256 bits, SHA-256 stored, one use,
 *       [OtpProperties#ticketLifetime]) instead of a session: [AuthService#signUp] consumes it.</li>
 *   <li>Limits are in memory: no network address or number is stored for them.</li>
 * </ul>
 */
@Service
public class PhoneCodeService {

    private static final Logger log = LoggerFactory.getLogger(PhoneCodeService.class);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PhoneCodeRepository codes;
    private final UserRepository users;
    private final OtpSender sender;
    private final OtpProperties properties;
    private final TransactionTemplate transactions;
    /** Confirmation checks run in their OWN transaction: a wrong try stays counted when the caller's action fails. */
    private final TransactionTemplate ownTransactions;
    private final Clock clock;
    private final byte[] codeKey;
    private final AttemptLimiter startsPerAddress;
    private final AttemptLimiter verifyFailuresPerAddress;
    private final AttemptLimiter sendsPerNumber;

    public PhoneCodeService(PhoneCodeRepository codes, UserRepository users, OtpSender sender, OtpProperties properties,
                            JwtProperties jwt, AuthProperties auth, PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.codes = codes;
        this.users = users;
        this.sender = sender;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ownTransactions = new TransactionTemplate(transactionManager);
        this.ownTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.codeKey = codeKey(properties.secret(), jwt.secret());
        int maxKeys = auth.maxTrackedKeys();
        this.startsPerAddress = new AttemptLimiter(properties.startsPerAddress(), properties.addressWindow(), maxKeys,
                clock);
        this.verifyFailuresPerAddress = new AttemptLimiter(properties.verifyFailuresPerAddress(),
                properties.addressWindow(), maxKeys, clock);
        this.sendsPerNumber = new AttemptLimiter(properties.sendsPerNumberPerDay(), Duration.ofDays(1), maxKeys, clock);
    }

    /** A code opened inside a transaction, waiting to be delivered (see [deliver]). Never printed. */
    public record Opened(String token, String phoneE164, String code, CodeChallengeResponse answer) {
        @Override
        public String toString() {
            return "Opened[<hidden>]";
        }
    }

    /** What the right sign-in code leads to. */
    public sealed interface SignInOutcome {
        /** The number belongs to this (usable) account: start a session. */
        record Existing(User user) implements SignInOutcome {
        }

        /** No account has this number: the person may create one with [ticket] (shown once, never stored). */
        record NewNumber(String ticket, String phoneHint, long expiresInSeconds) implements SignInOutcome {
            @Override
            public String toString() {
                return "NewNumber[<hidden>]";
            }
        }
    }

    // ---- sign-in -----------------------------------------------------------------------------------------------

    /** POST /api/auth/phone/start: a code for [rawPhone], whether or not it has an account (same answer). */
    public CodeChallengeResponse startSignIn(String rawPhone, String address) {
        String phone = PhoneNumbers.normalize(rawPhone);
        if (phone == null) throw PhoneVerificationException.invalidNumber();
        requireStartAllowed(address);
        return deliver(openWithRetry(PhoneCode.Purpose.SIGN_IN, phone, null));
    }

    /**
     * POST /api/auth/phone/verify: the right code ends the code; the answer says whether the number has an account.
     * Every refusal is a [PhoneVerificationException]; wrong codes are counted (and committed) before it.
     */
    public SignInOutcome verifySignIn(String token, String rawCode, String address) {
        String addressKey = address(address);
        if (verifyFailuresPerAddress.isBlocked(addressKey)) throw PhoneVerificationException.waitFor(0);
        if (token == null || !TOKEN.matcher(token).matches()) {
            verifyFailuresPerAddress.record(addressKey);
            throw PhoneVerificationException.challengeInvalid();
        }
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        String tokenHash = RefreshSecrets.hash(token);
        Checked checked = transactions.execute(status -> checkSignIn(tokenHash, code));
        return switch (checked.result()) {
            case EXISTING -> new SignInOutcome.Existing(checked.user());
            case NEW_NUMBER -> new SignInOutcome.NewNumber(checked.ticket(), PhoneNumbers.hint(checked.phone()),
                    properties.ticketLifetime().toSeconds());
            case UNAVAILABLE -> throw PhoneVerificationException.accountUnavailable();
            default -> throw refusal(checked, addressKey);
        };
    }

    /**
     * INSIDE the sign-up transaction: the number a live, unused ticket was issued for. The ticket ends here (one use);
     * a race with a second sign-up waits on the row lock and then finds nothing.
     */
    public String consumeTicket(String ticket) {
        if (ticket == null || !TOKEN.matcher(ticket).matches()) throw PhoneVerificationException.ticketInvalid();
        PhoneCode row = codes.findByTicketHashForUpdate(RefreshSecrets.hash(ticket)).orElse(null);
        if (row == null || !row.holdsTicket() || !row.isLive(now())) throw PhoneVerificationException.ticketInvalid();
        String phone = row.getPhoneE164();
        codes.delete(row);
        codes.flush();
        return phone;
    }

    // ---- confirmation of a sensitive action (N-7) ----------------------------------------------------------------

    /** What a confirmation code check found. */
    public enum Confirmation { CONFIRMED, WRONG, LOCKED, EXPIRED, INVALID }

    /**
     * POST /api/users/me/confirmation: a fresh code to the signed-in person's own verified number [phoneE164] (the
     * caller looks it up from the account, never from the request). Same code rules as sign-in.
     */
    public CodeChallengeResponse startConfirmation(long userId, String phoneE164, String address) {
        requireStartAllowed(address);
        return deliver(openWithRetry(PhoneCode.Purpose.CONFIRM, phoneE164, userId));
    }

    /**
     * Checks a confirmation code of [userId]. Runs in its own transaction (a wrong try is counted, a right code is used
     * up, whatever the caller's action then does). A challenge of another account or of a sign-in is INVALID: it
     * reveals nothing about it. The right code ends the challenge (one use).
     */
    public Confirmation checkConfirmation(long userId, String token, String rawCode) {
        if (token == null || !TOKEN.matcher(token).matches()) return Confirmation.INVALID;
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        String tokenHash = RefreshSecrets.hash(token);
        return ownTransactions.execute(status -> {
            LocalDateTime now = now();
            PhoneCode row = codes.findByTokenHashForUpdate(tokenHash).orElse(null);
            if (row == null || row.getPurpose() != PhoneCode.Purpose.CONFIRM || row.getUserId() == null
                    || row.getUserId() != userId || !row.isLive(now)) {
                return Confirmation.INVALID;
            }
            Checked refused = checkCode(row, code, now);
            if (refused == null) {
                codes.delete(row);
                return Confirmation.CONFIRMED;
            }
            return switch (refused.result()) {
                case WRONG -> refused.triesLeft() <= 0 ? Confirmation.LOCKED : Confirmation.WRONG;
                case LOCKED -> Confirmation.LOCKED;
                default -> Confirmation.EXPIRED;
            };
        });
    }

    // ---- shared by both purposes --------------------------------------------------------------------------------

    /** Every request that can send a code counts against one per-address limit (checked before any work). */
    public void requireStartAllowed(String address) {
        if (!startsPerAddress.tryAcquire(address(address))) throw PhoneVerificationException.waitFor(0);
    }

    /** POST /api/auth/phone/resend: a new code for the same number (cooldown and caps apply). */
    public CodeChallengeResponse resend(String token, String address) {
        requireStartAllowed(address);
        String tokenHash = hashOf(token);
        Opened opened = transactions.execute(status -> {
            PhoneCode row = liveChallenge(tokenHash);
            LocalDateTime now = now();
            String code = newCode(row, now, false);
            return new Opened(token, row.getPhoneE164(), code, answer(row, token, now));
        });
        return deliver(opened);
    }

    /** POST /api/auth/phone/change: a typo in the number before any code was entered: start again for another one. */
    public CodeChallengeResponse change(String token, String rawPhone, String address) {
        String phone = PhoneNumbers.normalize(rawPhone);
        if (phone == null) throw PhoneVerificationException.invalidNumber();
        requireStartAllowed(address);
        String tokenHash = hashOf(token);
        transactions.executeWithoutResult(status -> {
            PhoneCode row = liveChallenge(tokenHash);
            if (row.getPurpose() != PhoneCode.Purpose.SIGN_IN) throw PhoneVerificationException.challengeInvalid();
            codes.delete(row);
            codes.flush();
        });
        return deliver(openWithRetry(PhoneCode.Purpose.SIGN_IN, phone, null));
    }

    /**
     * After the commit: sends the code (if one was made). If the provider refuses it, the send is undone and the answer
     * says codeSent=false, so the app can offer "send again" at once.
     */
    public CodeChallengeResponse deliver(Opened opened) {
        if (opened.code() == null) return opened.answer();
        try {
            sender.send(opened.phoneE164(), opened.code());
            return opened.answer();
        } catch (OtpSender.OtpDeliveryException e) {
            log.warn("Verification code not delivered ({})", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Verification code not delivered ({})", e.getClass().getSimpleName());
        }
        String tokenHash = RefreshSecrets.hash(opened.token());
        return transactions.execute(status -> {
            PhoneCode row = codes.findByTokenHashForUpdate(tokenHash).orElse(null);
            if (row == null) return opened.answer();
            row.undoSend();
            return answer(row, opened.token(), now());
        });
    }

    /** The clean-up job: ended codes and tickets. */
    public int cleanUp() {
        Integer removed = transactions.execute(status -> codes.deleteExpired(now()));
        startsPerAddress.evictExpired();
        verifyFailuresPerAddress.evictExpired();
        sendsPerNumber.evictExpired();
        return removed == null ? 0 : removed;
    }

    /**
     * Opens a code for [phoneE164] in its OWN transaction (also when a caller has one open: the row must be committed
     * before the SMS goes out, and a read-only caller must not swallow the change). Two simultaneous starts for one
     * number collide on uk_phone_codes_purpose_phone; the losing attempt is retried once as a whole (Phase 14: never
     * catch a unique-key error inside a transaction and carry on).
     */
    Opened openWithRetry(PhoneCode.Purpose purpose, String phoneE164, Long userId) {
        try {
            return ownTransactions.execute(status -> open(purpose, phoneE164, userId));
        } catch (DataIntegrityViolationException first) {
            try {
                return ownTransactions.execute(status -> open(purpose, phoneE164, userId));
            } catch (DataIntegrityViolationException second) {
                throw PhoneVerificationException.waitFor(0);
            }
        }
    }

    /**
     * INSIDE a transaction: the code of [purpose] for [phoneE164]. A live code for the same number keeps working (the
     * caller only gets a new token: the code already on its way can still be typed); otherwise a new row replaces any
     * earlier one (and its ticket) and a code is made.
     */
    private Opened open(PhoneCode.Purpose purpose, String phoneE164, Long userId) {
        LocalDateTime now = now();
        String token = RefreshSecrets.newSecret();
        String tokenHash = RefreshSecrets.hash(token);
        LocalDateTime expires = now.plus(properties.challengeLifetime());
        Optional<PhoneCode> existing = codes.findByPurposeAndPhoneE164(purpose, phoneE164);
        PhoneCode row;
        String code = null;
        if (existing.isPresent() && existing.get().isLive(now) && existing.get().hasLiveCode(now)
                && !existing.get().holdsTicket() && existing.get().getAttempts() < properties.maxAttempts()
                && java.util.Objects.equals(existing.get().getUserId(), userId)) {
            row = existing.get();
            row.rotateToken(tokenHash, expires);
        } else {
            existing.ifPresent(old -> {
                codes.delete(old);
                codes.flush();
            });
            row = codes.save(new PhoneCode(purpose, phoneE164, userId, tokenHash, now, expires));
            code = newCode(row, now, false);
        }
        codes.flush();
        return new Opened(token, phoneE164, code, answer(row, token, now));
    }

    // ---- inside a transaction ---------------------------------------------------------------------------------

    private enum Result { EXISTING, NEW_NUMBER, UNAVAILABLE, WRONG, LOCKED, EXPIRED, INVALID }

    private record Checked(Result result, int triesLeft, User user, String ticket, String phone) {
        static Checked of(Result result) {
            return new Checked(result, 0, null, null, null);
        }
    }

    private Checked checkSignIn(String tokenHash, String code) {
        LocalDateTime now = now();
        PhoneCode row = codes.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (row == null || row.getPurpose() != PhoneCode.Purpose.SIGN_IN || row.holdsTicket() || !row.isLive(now)) {
            return Checked.of(Result.INVALID);
        }
        Checked wrong = checkCode(row, code, now);
        if (wrong != null) return wrong;
        String phone = row.getPhoneE164();
        Optional<User> owner = users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(phone);
        if (owner.isPresent()) {
            codes.delete(row);
            if (!owner.get().isEnabled() || owner.get().isErased()) return Checked.of(Result.UNAVAILABLE);
            return new Checked(Result.EXISTING, 0, owner.get(), null, phone);
        }
        String ticket = RefreshSecrets.newSecret();
        row.issueTicket(RefreshSecrets.hash(ticket), now.plus(properties.ticketLifetime()));
        return new Checked(Result.NEW_NUMBER, 0, null, ticket, phone);
    }

    /** Null when [code] is the live code of [row]; otherwise the refusal (a wrong try is counted on the row). */
    private Checked checkCode(PhoneCode row, String code, LocalDateTime now) {
        if (row.getCodeHash() == null) return Checked.of(Result.EXPIRED);
        if (row.getAttempts() >= properties.maxAttempts()) return Checked.of(Result.LOCKED);
        if (!row.hasLiveCode(now)) return Checked.of(Result.EXPIRED);
        boolean right = CODE.matcher(code).matches()
                && MessageDigest.isEqual(codeHash(row.getPurpose(), row.getPhoneE164(), code)
                .getBytes(StandardCharsets.US_ASCII), row.getCodeHash().getBytes(StandardCharsets.US_ASCII));
        if (right) return null;
        row.wrongCode();
        return new Checked(Result.WRONG, properties.maxAttempts() - row.getAttempts(), null, null, null);
    }

    private PhoneVerificationException refusal(Checked checked, String addressKey) {
        switch (checked.result()) {
            case WRONG:
                verifyFailuresPerAddress.record(addressKey);
                return checked.triesLeft() <= 0 ? PhoneVerificationException.locked()
                        : PhoneVerificationException.wrongCode(checked.triesLeft());
            case LOCKED:
                verifyFailuresPerAddress.record(addressKey);
                return PhoneVerificationException.locked();
            case EXPIRED:
                return PhoneVerificationException.codeExpired();
            default:
                verifyFailuresPerAddress.record(addressKey);
                return PhoneVerificationException.challengeInvalid();
        }
    }

    private PhoneCode liveChallenge(String tokenHash) {
        PhoneCode row = codes.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (row == null || row.holdsTicket() || !row.isLive(now())) throw PhoneVerificationException.challengeInvalid();
        return row;
    }

    /** Makes and records a new code for [row]'s number; the caps and (unless [skipCooldown]) the cooldown apply. */
    private String newCode(PhoneCode row, LocalDateTime now, boolean skipCooldown) {
        if (row.getSends() >= properties.maxSendsPerChallenge()) throw PhoneVerificationException.waitFor(0);
        long wait = skipCooldown ? 0 : row.resendInSeconds(now, properties.resendCooldown());
        if (wait > 0) throw PhoneVerificationException.waitFor(wait);
        if (!sendsPerNumber.tryAcquire(RefreshSecrets.hash(row.getPhoneE164()))) {
            throw PhoneVerificationException.waitFor(0);
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        row.newCode(codeHash(row.getPurpose(), row.getPhoneE164(), code), now, now.plus(properties.codeLifetime()));
        return code;
    }

    private CodeChallengeResponse answer(PhoneCode row, String token, LocalDateTime now) {
        boolean live = row.hasLiveCode(now);
        long expiresIn = live ? Math.max(0, Duration.between(now, row.getCodeExpiresAt()).toSeconds()) : 0;
        long resendIn = live ? row.resendInSeconds(now, properties.resendCooldown()) : 0;
        return new CodeChallengeResponse(token, PhoneNumbers.hint(row.getPhoneE164()), live, resendIn, expiresIn);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String hashOf(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw PhoneVerificationException.challengeInvalid();
        return RefreshSecrets.hash(token);
    }

    private String codeHash(PhoneCode.Purpose purpose, String phoneE164, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeKey, "HmacSHA256"));
            byte[] digest = mac.doFinal((purpose.name() + ":" + phoneE164 + ":" + code)
                    .getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** OTP_SECRET if set; otherwise a key derived from JWT_SECRET for this one purpose (never the JWT key itself). */
    private static byte[] codeKey(String otpSecret, String jwtSecret) {
        if (otpSecret != null && !otpSecret.isBlank()) return otpSecret.getBytes(StandardCharsets.UTF_8);
        if (jwtSecret == null || jwtSecret.isBlank()) throw new IllegalStateException("JWT_SECRET is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("kurinji-otp-code-key-v1".getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String address(String address) {
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
