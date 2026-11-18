package com.safesphere.backend.common.sms;

/**
 * Phase N6 (D20): sends one plain text SMS to one Indian mobile number. Provider-neutral: the caller decides WHAT is
 * sent and to whom (the alert rules and texts live in tested code); a gateway only delivers. Implementations never log
 * the number, the text or a key.
 */
public interface SmsGateway {

    /**
     * Sends [text] to [phoneE164] ("+91XXXXXXXXXX"). Returns the provider's reference for this message (may be null when
     * the provider gives none). Throws [SmsRefusedException] when the provider did not accept it.
     */
    String send(String phoneE164, String text);

    /** For the start-up log only (never a secret). */
    String describe();

    /** The provider did not accept the message. [reason] is safe to log (a status code, never the number or text). */
    class SmsRefusedException extends RuntimeException {
        public SmsRefusedException(String reason) {
            super(reason);
        }
    }
}
