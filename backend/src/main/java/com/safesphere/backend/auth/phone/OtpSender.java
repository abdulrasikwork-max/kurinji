package com.safesphere.backend.auth.phone;

/**
 * Phase 17B: delivers a verification code to a phone. Provider-neutral: the server makes and checks every code; a sender
 * only delivers it, so changing the SMS provider never touches the verification rules. Implementations must never log
 * the code or the number.
 */
public interface OtpSender {

    /** Sends [code] to [phoneE164] ("+91XXXXXXXXXX"). Throws [OtpDeliveryException] when the provider refused it. */
    void send(String phoneE164, String code);

    /** For the start-up log only (never a secret). */
    String describe();

    /** The provider did not accept the message. [reason] is safe to log (a status code, never the code or number). */
    class OtpDeliveryException extends RuntimeException {
        public OtpDeliveryException(String reason) {
            super(reason);
        }
    }
}
