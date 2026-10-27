package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Phase 17B: bodies and answers of the phone verification endpoints (docs/API.md "Phase 17B"). */
public final class PhoneVerificationDtos {

    private PhoneVerificationDtos() {
    }

    /**
     * Answer of POST /api/auth/register, /phone/start, /phone/resend and /phone/change: no session yet. [challenge] is
     * a credential for this verification only (the app keeps it in memory); [phoneHint] shows the last 4 digits.
     * [needsPhone] = the account has no number yet (enter one with /phone/change). [codeSent] = false when the SMS
     * provider did not take the code (ask again at once).
     */
    public record PhoneChallengeResponse(String status, String challenge, String phoneHint, boolean needsPhone,
                                         boolean codeSent, long resendInSeconds, long codeExpiresInSeconds) {

        public static final String STATUS = "PHONE_VERIFICATION_REQUIRED";

        @Override
        public String toString() {
            return "PhoneChallengeResponse[challenge=<hidden>]";
        }
    }

    /** POST /api/auth/phone/start: an interrupted sign-up, or an account from before 17B, signing in. */
    public record StartRequest(
            @NotBlank(message = "Email is required") @Size(max = 255) String email,
            @NotBlank(message = "Password is required") @Size(max = 200) String password,
            @Size(max = 30) String phone) {

        @Override
        public String toString() {
            return "StartRequest[email=" + email + ", password=<hidden>]";
        }
    }

    public record VerifyRequest(
            @NotBlank @Size(max = 64) String challenge,
            @NotBlank(message = "Enter the code") @Size(max = 10) String code) {

        @Override
        public String toString() {
            return "VerifyRequest[<hidden>]";
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
