package com.safesphere.backend.auth.phone;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 17B, N2: deletes ended codes and sign-up tickets. Deletion only: nothing scheduled ever sends a message.
 */
@Component
public class PhoneVerificationCleanupJob {

    private final PhoneCodeService verification;

    public PhoneVerificationCleanupJob(PhoneCodeService verification) {
        this.verification = verification;
    }

    @Scheduled(fixedDelayString = "${app.otp.cleanup-interval:PT1H}",
            initialDelayString = "${app.otp.cleanup-interval:PT1H}")
    public void run() {
        verification.cleanUp();
    }
}
