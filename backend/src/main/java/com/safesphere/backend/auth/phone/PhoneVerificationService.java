package com.safesphere.backend.auth.phone;

import com.safesphere.backend.auth.dto.PhoneVerificationDtos.PhoneChallengeResponse;
import com.safesphere.backend.auth.repository.EmailVerificationTokenRepository;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 17B: proof that a person holds the phone number of their account (replaces V25 e-mail links).
 *
 * <ul>
 *   <li>The app holds a challenge token (256 random bits); only its SHA-256 is stored, one challenge per account.</li>
 *   <li>A code is 6 random digits ([SecureRandom]), valid [OtpProperties#codeLifetime]; only
 *       HMAC-SHA256(server key, account id | code) is stored, compared in constant time. Codes are never logged.</li>
 *   <li>Wrong codes: [OtpProperties#maxAttempts] per code (then ask for a new one), counted under a row lock so two
 *       simultaneous tries are both counted, and per network address across challenges.</li>
 *   <li>Sending: a cooldown between codes, a cap per challenge, and a cap per number per 24 h across all challenges and
 *       accounts (anti SMS bombing). The SMS goes out AFTER the database work committed; if the provider refuses it,
 *       the send is undone (it neither counts nor makes anyone wait).</li>
 *   <li>One verified account per number (checked here and by MySQL's unique key on users.verified_phone).</li>
 *   <li>Limits are in memory (like the Phase 7 sign-in limits): no IP address or number is stored for them.</li>
 * </ul>
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CLEANUP_BATCH = 100;

    private final PhoneChallengeRepository challenges;
    private final UserRepository users;
    private final EmailVerificationTokenRepository oldLinks;
    private final OtpSender sender;
    private final OtpProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final byte[] codeKey;
    private final AttemptLimiter startsPerAddress;
    private final AttemptLimiter verifyFailuresPerAddress;
    private final AttemptLimiter sendsPerNumber;

    public PhoneVerificationService(PhoneChallengeRepository challenges, UserRepository users,
                                    EmailVerificationTokenRepository oldLinks, OtpSender sender, OtpProperties properties,
                                    JwtProperties jwt, AuthProperties auth, PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.challenges = challenges;
        this.users = users;
        this.oldLinks = oldLinks;
        this.sender = sender;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.codeKey = codeKey(properties.secret(), jwt.secret());
        int maxKeys = auth.maxTrackedKeys();
        this.startsPerAddress = new AttemptLimiter(properties.startsPerAddress(), properties.addressWindow(), maxKeys,
                clock);
        this.verifyFailuresPerAddress = new AttemptLimiter(properties.verifyFailuresPerAddress(),
                properties.addressWindow(), maxKeys, clock);
        this.sendsPerNumber = new AttemptLimiter(properties.sendsPerNumberPerDay(), Duration.ofDays(1), maxKeys, clock);
    }

    /** A verification opened inside a transaction, waiting to be delivered (see [deliver]). Never printed. */
    public record Opened(long userId, String token, String phoneE164, String code, PhoneChallengeResponse answer) {
        @Override
        public String toString() {
            return "Opened[userId=" + userId + ", <hidden>]";
        }
    }

    /** Sign-up, start, resend and change count against one per-address limit (checked before any work). */
    public void requireStartAllowed(String address) {
        if (!startsPerAddress.tryAcquire(address(address))) throw PhoneVerificationException.waitFor(0);
    }

    /**
     * INSIDE the caller's transaction: the verification of [user] for [phoneE164] (null = the person must enter a
     * number first). A live verification for the same number keeps its code (the app only gets a new token);
     * otherwise a new one replaces any earlier one and a code is made. Deliver it with [deliver] after the commit.
     */
    public Opened open(User user, String phoneE164) {
        LocalDateTime now = now();
        String token = RefreshSecrets.newSecret();
        String tokenHash = RefreshSecrets.hash(token);
        LocalDateTime expires = now.plus(properties.challengeLifetime());
        Optional<PhoneChallenge> existing = challenges.findById(user.getId());
        PhoneChallenge challenge;
        String code = null;
        if (existing.isPresent() && existing.get().isLive(now) && phoneE164 != null
                && Objects.equals(existing.get().getPhoneE164(), phoneE164) && existing.get().hasLiveCode(now)
                && existing.get().getAttempts() < properties.maxAttempts()) {
            challenge = existing.get();
            challenge.rotateToken(tokenHash, expires);
        } else {
            existing.ifPresent(old -> {
                challenges.delete(old);
                challenges.flush();
            });
            challenge = challenges.save(new PhoneChallenge(user.getId(), tokenHash, phoneE164, now, expires));
            if (phoneE164 != null) code = newCode(challenge, now, false);
        }
        challenges.flush();
        return new Opened(user.getId(), token, phoneE164, code, answer(challenge, token, now));
    }

    /**
     * After the commit: sends the code (if one was made). If the provider refuses it, the send is undone and the answer
     * says codeSent=false, so the app can offer "send again" at once.
     */
    public PhoneChallengeResponse deliver(Opened opened) {
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
            PhoneChallenge challenge = challenges.findById(opened.userId())
                    .filter(c -> c.getTokenHash().equals(tokenHash)).orElse(null);
            if (challenge == null) return opened.answer();
            challenge.undoSend();
            return answer(challenge, opened.token(), now());
        });
    }

    /** POST /api/auth/phone/resend: a new code for the same number (cooldown and caps apply). */
    public PhoneChallengeResponse resend(String token, String address) {
        requireStartAllowed(address);
        String tokenHash = hashOf(token);
        Opened opened = transactions.execute(status -> {
            PhoneChallenge challenge = liveChallenge(tokenHash);
            if (challenge.getPhoneE164() == null) throw PhoneVerificationException.numberRequired();
            LocalDateTime now = now();
            String code = newCode(challenge, now, false);
            return new Opened(challenge.getUserId(), token, challenge.getPhoneE164(), code,
                    answer(challenge, token, now));
        });
        return deliver(opened);
    }

    /** POST /api/auth/phone/change: another number before verification (no cooldown; the caps still apply). */
    public PhoneChallengeResponse change(String token, String rawPhone, String address) {
        requireStartAllowed(address);
        String tokenHash = hashOf(token);
        String phone = PhoneNumbers.normalize(rawPhone);
        if (phone == null) throw PhoneVerificationException.invalidNumber();
        Opened opened = transactions.execute(status -> {
            PhoneChallenge challenge = liveChallenge(tokenHash);
            User user = users.findById(challenge.getUserId()).filter(this::usable)
                    .orElseThrow(PhoneVerificationException::challengeInvalid);
            if (user.isPhoneVerified()) throw PhoneVerificationException.alreadyVerified();
            LocalDateTime now = now();
            challenge.changeNumber(phone);
            user.setUnverifiedPhone(phone);
            String code = newCode(challenge, now, true);
            return new Opened(challenge.getUserId(), token, phone, code, answer(challenge, token, now));
        });
        return deliver(opened);
    }

    /**
     * POST /api/auth/phone/verify: the right code verifies the number and returns the account (the caller then starts a
     * session). Every refusal is a [PhoneVerificationException]; wrong codes are counted (and committed) before it.
     */
    public User verify(String token, String rawCode, String address) {
        String addressKey = address(address);
        if (verifyFailuresPerAddress.isBlocked(addressKey)) throw PhoneVerificationException.waitFor(0);
        if (token == null || !TOKEN.matcher(token).matches()) {
            verifyFailuresPerAddress.record(addressKey);
            throw PhoneVerificationException.challengeInvalid();
        }
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        String tokenHash = RefreshSecrets.hash(token);
        Checked checked;
        try {
            checked = transactions.execute(status -> check(tokenHash, code));
        } catch (DataIntegrityViolationException e) {
            // Another account verified the same number at the same moment (uk_users_verified_phone).
            throw PhoneVerificationException.inUse();
        }
        switch (checked.result()) {
            case VERIFIED:
                return checked.user();
            case WRONG:
                verifyFailuresPerAddress.record(addressKey);
                if (checked.triesLeft() <= 0) throw PhoneVerificationException.locked();
                throw PhoneVerificationException.wrongCode(checked.triesLeft());
            case LOCKED:
                verifyFailuresPerAddress.record(addressKey);
                throw PhoneVerificationException.locked();
            case EXPIRED:
                throw PhoneVerificationException.codeExpired();
            case NEEDS_PHONE:
                throw PhoneVerificationException.numberRequired();
            case IN_USE:
                throw PhoneVerificationException.inUse();
            default:
                verifyFailuresPerAddress.record(addressKey);
                throw PhoneVerificationException.challengeInvalid();
        }
    }

    /**
     * A sign-up with an e-mail that already has an account may replace it only if that account was never activated and
     * has no verification in progress (nobody can hold someone else's address for longer than one challenge).
     */
    public boolean replaceableBySignUp(User existing) {
        if (existing.isActivated() || existing.isErased()) return false;
        Optional<PhoneChallenge> live = challenges.findById(existing.getId());
        return live.isEmpty() || !live.get().isLive(now());
    }

    /** Removes an account that was never activated (and what belongs to it). Inside the caller's transaction. */
    public void removeUnactivated(User existing) {
        challenges.findById(existing.getId()).ifPresent(challenges::delete);
        oldLinks.findById(existing.getId()).ifPresent(oldLinks::delete);
        users.delete(existing);
        users.flush();
    }

    /** The clean-up job: ended verifications, V25 links left from before, and accounts never activated in time. */
    public int cleanUp() {
        LocalDateTime now = now();
        transactions.executeWithoutResult(status -> {
            challenges.deleteExpired(now);
            oldLinks.deleteExpired(now);
        });
        List<Long> stale = users.findIdsUnverifiedCreatedBefore(now.minus(properties.unverifiedRetention()),
                PageRequest.of(0, CLEANUP_BATCH));
        int removed = 0;
        for (Long id : stale) {
            try {
                transactions.executeWithoutResult(status -> users.findById(id).ifPresent(this::removeUnactivated));
                removed++;
            } catch (DataAccessException e) {
                log.warn("Unactivated account not removed ({})", e.getClass().getSimpleName());
            }
        }
        if (removed > 0) log.info("Phone verification clean-up: {} account(s) never activated removed", removed);
        startsPerAddress.evictExpired();
        verifyFailuresPerAddress.evictExpired();
        sendsPerNumber.evictExpired();
        return removed;
    }

    // ---- inside a transaction ---------------------------------------------------------------------------------

    private enum Result { VERIFIED, WRONG, LOCKED, EXPIRED, NEEDS_PHONE, IN_USE, INVALID }

    private record Checked(Result result, int triesLeft, User user) {
        static Checked of(Result result) {
            return new Checked(result, 0, null);
        }
    }

    private Checked check(String tokenHash, String code) {
        LocalDateTime now = now();
        PhoneChallenge challenge = challenges.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (challenge == null || !challenge.isLive(now)) return Checked.of(Result.INVALID);
        if (challenge.getPhoneE164() == null) return Checked.of(Result.NEEDS_PHONE);
        if (challenge.getCodeHash() == null) return Checked.of(Result.EXPIRED);
        if (challenge.getAttempts() >= properties.maxAttempts()) return Checked.of(Result.LOCKED);
        if (!challenge.hasLiveCode(now)) return Checked.of(Result.EXPIRED);
        boolean right = CODE.matcher(code).matches()
                && MessageDigest.isEqual(codeHash(challenge.getUserId(), code).getBytes(StandardCharsets.US_ASCII),
                challenge.getCodeHash().getBytes(StandardCharsets.US_ASCII));
        if (!right) {
            challenge.wrongCode();
            return new Checked(Result.WRONG, properties.maxAttempts() - challenge.getAttempts(), null);
        }
        User user = users.findById(challenge.getUserId()).filter(this::usable).orElse(null);
        if (user == null) return Checked.of(Result.INVALID);
        if (users.existsByPhoneE164AndPhoneVerifiedAtIsNotNullAndIdNot(challenge.getPhoneE164(), user.getId())) {
            return Checked.of(Result.IN_USE);
        }
        user.markPhoneVerified(challenge.getPhoneE164(), now);
        challenges.delete(challenge);
        users.flush(); // a race on the same number fails here (unique key), outside any caught block
        return new Checked(Result.VERIFIED, 0, user);
    }

    private PhoneChallenge liveChallenge(String tokenHash) {
        PhoneChallenge challenge = challenges.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (challenge == null || !challenge.isLive(now())) throw PhoneVerificationException.challengeInvalid();
        return challenge;
    }

    /** Makes and records a new code for [challenge]'s number; the caps and (unless [skipCooldown]) the cooldown apply. */
    private String newCode(PhoneChallenge challenge, LocalDateTime now, boolean skipCooldown) {
        if (challenge.getSends() >= properties.maxSendsPerChallenge()) throw PhoneVerificationException.waitFor(0);
        long wait = skipCooldown ? 0 : challenge.resendInSeconds(now, properties.resendCooldown());
        if (wait > 0) throw PhoneVerificationException.waitFor(wait);
        if (!sendsPerNumber.tryAcquire(RefreshSecrets.hash(challenge.getPhoneE164()))) {
            throw PhoneVerificationException.waitFor(0);
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        challenge.newCode(codeHash(challenge.getUserId(), code), now, now.plus(properties.codeLifetime()));
        return code;
    }

    private PhoneChallengeResponse answer(PhoneChallenge challenge, String token, LocalDateTime now) {
        boolean live = challenge.hasLiveCode(now);
        long expiresIn = live ? Math.max(0, Duration.between(now, challenge.getCodeExpiresAt()).toSeconds()) : 0;
        long resendIn = live ? challenge.resendInSeconds(now, properties.resendCooldown()) : 0;
        return new PhoneChallengeResponse(PhoneChallengeResponse.STATUS, token, PhoneNumbers.hint(challenge.getPhoneE164()),
                challenge.getPhoneE164() == null, live, resendIn, expiresIn);
    }

    private boolean usable(User user) {
        return user.isEnabled() && !user.isErased();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String hashOf(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw PhoneVerificationException.challengeInvalid();
        return RefreshSecrets.hash(token);
    }

    private String codeHash(long userId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeKey, "HmacSHA256"));
            byte[] digest = mac.doFinal((userId + ":" + code).getBytes(StandardCharsets.US_ASCII));
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
