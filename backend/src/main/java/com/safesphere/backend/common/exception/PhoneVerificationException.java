package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 17B: every refusal of the phone verification flow, each with a stable code the app reacts to (ApiError.code)
 * and words safe to show. None of them says whether another account exists, except PHONE_IN_USE, which is only answered
 * to someone who has just proved they hold that phone (they entered the right code).
 */
public class PhoneVerificationException extends ApiException {

    public static final String PHONE_NOT_VERIFIED = "PHONE_NOT_VERIFIED";
    public static final String PHONE_INVALID = "PHONE_INVALID";
    public static final String PHONE_REQUIRED = "PHONE_REQUIRED";
    public static final String CHALLENGE_INVALID = "CHALLENGE_INVALID";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String OTP_LOCKED = "OTP_LOCKED";
    public static final String OTP_WAIT = "OTP_WAIT";
    public static final String PHONE_IN_USE = "PHONE_IN_USE";
    public static final String PHONE_ALREADY_VERIFIED = "PHONE_ALREADY_VERIFIED";

    private PhoneVerificationException(HttpStatus status, String message, String code) {
        super(status, message, code);
    }

    /** The right password, but this account has no verified phone yet: no session (403, like V25's e-mail). */
    public static PhoneVerificationException notVerified() {
        return new PhoneVerificationException(HttpStatus.FORBIDDEN, "Verify your phone number to continue",
                PHONE_NOT_VERIFIED);
    }

    public static PhoneVerificationException invalidNumber() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST, "Enter a 10-digit Indian mobile number",
                PHONE_INVALID);
    }

    public static PhoneVerificationException numberRequired() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST, "Add your mobile number first", PHONE_REQUIRED);
    }

    public static PhoneVerificationException challengeInvalid() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST,
                "This code request has ended. Sign in again to get a new code.", CHALLENGE_INVALID);
    }

    public static PhoneVerificationException wrongCode(int triesLeft) {
        String message = triesLeft == 1 ? "That code is not right. 1 try left."
                : "That code is not right. " + triesLeft + " tries left.";
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST, message, OTP_INVALID);
    }

    public static PhoneVerificationException codeExpired() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST, "This code has expired. Ask for a new one.",
                OTP_EXPIRED);
    }

    public static PhoneVerificationException locked() {
        return new PhoneVerificationException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many wrong codes. Ask for a new code.", OTP_LOCKED);
    }

    public static PhoneVerificationException waitFor(long seconds) {
        String message = seconds <= 0 ? "Too many codes asked for. Please try again later."
                : "You can ask for a new code in " + seconds + " seconds.";
        return new PhoneVerificationException(HttpStatus.TOO_MANY_REQUESTS, message, OTP_WAIT);
    }

    public static PhoneVerificationException inUse() {
        return new PhoneVerificationException(HttpStatus.CONFLICT,
                "This number is already used by another Kurinji account. Use another number, or sign in to that account.",
                PHONE_IN_USE);
    }

    public static PhoneVerificationException alreadyVerified() {
        return new PhoneVerificationException(HttpStatus.CONFLICT, "Your phone number is already verified. Sign in.",
                PHONE_ALREADY_VERIFIED);
    }
}
