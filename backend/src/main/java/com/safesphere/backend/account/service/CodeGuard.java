package com.safesphere.backend.account.service;

import com.safesphere.backend.auth.dto.PhoneVerificationDtos.CodeChallengeResponse;
import com.safesphere.backend.auth.phone.PhoneCodeService;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Phase N2 (decision N-7; replaces Phase 10A's PasswordGuard with the same rules): the one check of "confirm with a
 * fresh code" for sensitive account actions (deletion request and cancel, signing out the other phones, ending an SOS
 * from another sign-in). The person asks for a code ([start]), which is texted to the account's own verified number;
 * the action then carries the challenge and the code.
 *
 * Wrong codes for all of these actions together are limited to [AccountProperties#codeFailures] per window per account
 * (429), in memory, on top of the 5 tries per code. A wrong code answers 409, never 401 (a 401 would make the phone sign
 * out). Honest limit (viva): the code goes to the same SIM as sign-in, so it protects against a stolen session or an
 * unlocked phone left signed in elsewhere, not against someone who holds the SIM.
 */
@Component
public class CodeGuard {

    static final String WRONG_CODE = "That code is not right, or it has ended. Ask for a new code.";
    static final String TOO_MANY = "Too many wrong codes. Please wait a few minutes and try again.";

    private final PhoneCodeService codes;
    private final AttemptLimiter failures;

    public CodeGuard(PhoneCodeService codes, AccountProperties properties, Clock clock) {
        this.codes = codes;
        this.failures = new AttemptLimiter(properties.codeFailures(), properties.codeFailureWindow(),
                properties.maxTrackedKeys(), clock);
    }

    /** Texts a fresh confirmation code to [user]'s own verified number. */
    public CodeChallengeResponse start(User user, String address) {
        return codes.startConfirmation(user.getId(), user.getPhoneE164(), address);
    }

    /**
     * 429 before any check while over the limit; a wrong, ended or foreign code is counted (409), a right one is used up
     * and resets the count.
     */
    public void require(User user, String challenge, String code) {
        String key = String.valueOf(user.getId());
        if (failures.isBlocked(key)) throw new TooManyAttemptsException(TOO_MANY);
        PhoneCodeService.Confirmation result = codes.checkConfirmation(user.getId(), challenge, code);
        if (result != PhoneCodeService.Confirmation.CONFIRMED) {
            failures.record(key);
            throw new ConflictException(WRONG_CODE);
        }
        failures.reset(key);
    }

    public void evictExpired() {
        failures.evictExpired();
    }
}
