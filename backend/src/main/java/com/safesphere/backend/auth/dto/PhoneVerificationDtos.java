package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Phase N2: bodies and answers of the phone sign-in endpoints (docs/API.md "Phase N2"). */
public final class PhoneVerificationDtos {

    private PhoneVerificationDtos() {
    }

    /**
     * Answer of POST /api/auth/phone/start, /phone/resend and /phone/change (and, since N2, of a request for a
     * confirmation code): no session. [challenge] is a credential for this code only (the app keeps it in memory);
     * [phoneHint] shows the last 4 digits. [codeSent] = false when the SMS provider did not take the code.
     */
    public record CodeChallengeResponse(String challenge, String phoneHint, boolean codeSent, long resendInSeconds,
                                        long codeExpiresInSeconds) {
        @Override
        public String toString() {
            return "CodeChallengeResponse[challenge=<hidden>]";
        }
    }

    /** POST /api/auth/phone/start: the only thing anyone types to sign in or to sign up. */
    public record StartRequest(
            @NotBlank(message = "Enter your mobile number") @Size(max = 30) String phone) {
        @Override
        public String toString() {
            return "StartRequest[<hidden>]";
        }
    }

    /**
     * POST /api/auth/phone/verify. Phase 11 C1: [previousRefreshToken] (optional) = the refresh token this phone still
     * holds for the SAME account (after "sign in to sync"); with the right code it ends that old sign-in and hands its
     * running SOS to the new one. A token of another account is ignored.
     */
    public record VerifyRequest(
            @NotBlank @Size(max = 64) String challenge,
            @NotBlank(message = "Enter the code") @Size(max = 10) String code,
            @Size(max = 200, message = "Refresh token is invalid") String previousRefreshToken) {
        public VerifyRequest(String challenge, String code) {
            this(challenge, code, null);
        }

        @Override
        public String toString() {
            return "VerifyRequest[<hidden>]";
        }
    }

    /**
     * Answer of POST /api/auth/phone/verify: [status] SIGNED_IN with [session], or SIGN_UP_NEEDED with [signUp] (the
     * number has no account yet: send the ticket with a name and a date of birth to POST /api/auth/sign-up).
     */
    public record VerifyResponse(String status, AuthResponse session, SignUpTicket signUp) {
        public static final String SIGNED_IN = "SIGNED_IN";
        public static final String SIGN_UP_NEEDED = "SIGN_UP_NEEDED";

        public static VerifyResponse signedIn(AuthResponse session) {
            return new VerifyResponse(SIGNED_IN, session, null);
        }

        public static VerifyResponse signUpNeeded(SignUpTicket ticket) {
            return new VerifyResponse(SIGN_UP_NEEDED, null, ticket);
        }

        @Override
        public String toString() {
            return "VerifyResponse[status=" + status + ", <hidden>]";
        }
    }

    /** A one-use proof that the person holds [phoneHint]'s number; it works for [expiresInSeconds]. */
    public record SignUpTicket(String ticket, String phoneHint, long expiresInSeconds) {
        @Override
        public String toString() {
            return "SignUpTicket[<hidden>]";
        }
    }

    /**
     * POST /api/auth/sign-up. [name] = the person's real name (checked by RealNamePolicy); [dateOfBirth] is checked for
     * 18+ and then discarded (D6): never stored, never logged. Format yyyy-MM-dd.
     */
    public record SignUpRequest(
            @NotBlank @Size(max = 64) String ticket,
            @NotBlank(message = "Enter your name") @Size(max = 200, message = "Your name is too long") String name,
            @NotNull(message = "Date of birth is required") LocalDate dateOfBirth) {
        @Override
        public String toString() {
            return "SignUpRequest[<hidden>]";
        }
    }

    public record ResendRequest(@NotBlank @Size(max = 64) String challenge) {
        @Override
        public String toString() {
            return "ResendRequest[<hidden>]";
        }
    }

    public record ChangeRequest(
            @NotBlank @Size(max = 64) String challenge,
            @NotBlank(message = "Enter your mobile number") @Size(max = 30) String phone) {
        @Override
        public String toString() {
            return "ChangeRequest[<hidden>]";
        }
    }
}
