package com.safesphere.backend.auth.mail;

import java.time.Duration;

/**
 * V25: sends the "confirm your e-mail" message. The only way a verification link leaves the server. Implementations
 * never log the link or the token, and report failure by returning false (the account simply stays unverified).
 */
public interface VerificationMailer {

    /** True when the message was handed to the mail server. */
    boolean sendVerification(String toEmail, String link, Duration lifetime);

    /** False when no mail server is configured (nothing can be sent). */
    boolean configured();
}
