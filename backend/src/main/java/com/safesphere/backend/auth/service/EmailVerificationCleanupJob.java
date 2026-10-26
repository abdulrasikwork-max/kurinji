package com.safesphere.backend.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V25: deletes expired verification tokens and accounts never verified within app.email-verification.unverified-retention
 * (7 days). Deletion only: nothing scheduled ever sends a message.
 */
@Component
public class EmailVerificationCleanupJob {

    private final EmailVerificationService verification;

    public EmailVerificationCleanupJob(EmailVerificationService verification) {
        this.verification = verification;
    }

    @Scheduled(fixedDelayString = "${app.email-verification.cleanup-interval:PT1H}",
            initialDelayString = "${app.email-verification.cleanup-interval:PT1H}")
    public void run() {
        verification.cleanUp();
        verification.evictExpiredLimits();
    }
}
