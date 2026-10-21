package com.safesphere.backend.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.evidence.* settings (Phase 5). Every value has a safe default.
 *
 * @param storageDir           folder for the encrypted evidence files (outside the database; not served statically)
 * @param maxItemBytes         largest single item (ciphertext)
 * @param chunkBytes           largest upload request; the phone sends the file in pieces of this size
 * @param userQuotaBytes       total the vault keeps per person (deleted items do not count)
 * @param maxItemsPerEmergency most items one emergency may hold (a 1-hour recording is 120 segments)
 * @param clockTolerance       how far the phone's capture times may lie outside the emergency
 * @param vaultPublicKey       base64 X.509 (DER or PEM body) RSA public key of the offline vault recovery key; empty =
 *                             no escrow (items are then readable only on the phone that recorded them)
 * @param deleteProtection     evidence cannot be deleted until this long after its emergency ended (a person forced to
 *                             press "I'm safe" cannot then be forced to destroy the evidence on the spot)
 * @param deleteMaxFailures    wrong deletion passwords allowed within [deleteFailureWindow] before deletion is locked
 * @param deleteFailureWindow  the window in which wrong deletion passwords are counted (and the lock lasts)
 */
@ConfigurationProperties(prefix = "app.evidence")
public record EvidenceProperties(Path storageDir, Long maxItemBytes, Integer chunkBytes, Long userQuotaBytes,
                                 Integer maxItemsPerEmergency, Duration clockTolerance, String vaultPublicKey,
                                 Duration deleteProtection, Integer deleteMaxFailures, Duration deleteFailureWindow) {

    public EvidenceProperties {
        if (storageDir == null) storageDir = Path.of("evidence-store");
        if (maxItemBytes == null) maxItemBytes = 200L * 1024 * 1024;
        if (chunkBytes == null) chunkBytes = 1024 * 1024;
        if (userQuotaBytes == null) userQuotaBytes = 2L * 1024 * 1024 * 1024;
        if (maxItemsPerEmergency == null) maxItemsPerEmergency = 500;
        if (clockTolerance == null) clockTolerance = Duration.ofMinutes(5);
        if (vaultPublicKey == null) vaultPublicKey = "";
        if (deleteProtection == null) deleteProtection = Duration.ofHours(24);
        if (deleteMaxFailures == null) deleteMaxFailures = 5;
        if (deleteFailureWindow == null) deleteFailureWindow = Duration.ofMinutes(15);
        if (maxItemBytes < 1 || chunkBytes < 1024 || userQuotaBytes < 1 || maxItemsPerEmergency < 1
                || clockTolerance.isNegative() || deleteProtection.isNegative() || deleteMaxFailures < 1
                || deleteFailureWindow.isNegative() || deleteFailureWindow.isZero()) {
            throw new IllegalStateException("app.evidence.* values must be positive (chunk-bytes at least 1024)");
        }
    }
}
