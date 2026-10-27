package com.safesphere.backend.auth.phone;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 17B: deletes ended verifications, V25 links left from before, and accounts never activated within
 * app.otp.unverified-retention (7 days). Deletion only: nothing scheduled ever sends a message.
 */
@Component
public class PhoneVerificationCleanupJob {

    private final PhoneVerificationService verification;

    public PhoneVerificationCleanupJob(PhoneVerificationService verification) {
        this.verification = verification;
    }

    @Scheduled(fixedDelayString = "${app.otp.cleanup-interval:PT1H}",
            initialDelayString = "${app.otp.cleanup-interval:PT1H}")
    public void run() {
        verification.cleanUp();
    }
}
