package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 17B, reworked in N2: every refusal of the phone code flow, each with a stable code the app reacts to
 * (ApiError.code) and words safe to show. None of them says whether a number has an account, except PHONE_IN_USE and
 * ACCOUNT_UNAVAILABLE, which are only answered to someone who has just proved they hold that phone (the right code).
 */
public class PhoneVerificationException extends ApiException {

    public static final String PHONE_INVALID = "PHONE_INVALID";
    public static final String CHALLENGE_INVALID = "CHALLENGE_INVALID";
    public static final String TICKET_INVALID = "TICKET_INVALID";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String OTP_LOCKED = "OTP_LOCKED";
    public static final String OTP_WAIT = "OTP_WAIT";
    public static final String PHONE_IN_USE = "PHONE_IN_USE";
    public static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";

    private PhoneVerificationException(HttpStatus status, String message, String code) {
        super(status, message, code);
    }

    public static PhoneVerificationException invalidNumber() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST, "Enter a 10-digit Indian mobile number",
                PHONE_INVALID);
    }

    public static PhoneVerificationException challengeInvalid() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST,
                "This code request has ended. Enter your number again to get a new code.", CHALLENGE_INVALID);
    }

    /** N2: the sign-up ticket was used, ran out or never existed. */
    public static PhoneVerificationException ticketInvalid() {
        return new PhoneVerificationException(HttpStatus.BAD_REQUEST,
                "This sign-up has ended. Enter your number again to get a new code.", TICKET_INVALID);
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

    /** Two sign-ups for one number at the same moment: the second one loses (one account per number). */
    public static PhoneVerificationException inUse() {
        return new PhoneVerificationException(HttpStatus.CONFLICT,
                "This number already has an IRL account. Enter your number again to sign in.", PHONE_IN_USE);
    }

    /** N2: the right code, but the account of this number was switched off (by an administrator). */
    public static PhoneVerificationException accountUnavailable() {
        return new PhoneVerificationException(HttpStatus.FORBIDDEN,
                "This account can't be used. Contact the IRL team if you think this is a mistake.", ACCOUNT_UNAVAILABLE);
    }
}
