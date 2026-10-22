package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.account.* settings (Phase 10A: password change and account deletion). Every value has a safe default.
 *
 * @param passwordFailures      wrong passwords per account per [passwordFailureWindow] for password change, deletion
 *                              request and deletion cancel together, before further attempts are refused (429)
 * @param passwordFailureWindow the window for that limit
 * @param deletionGrace         how long a deletion request waits before anything is deleted (cancellable)
 * @param deletionProtection    no deletion request within this time after an emergency ended (and the deletion itself
 *                              waits at least this long after any emergency): the same 24 h as evidence deletion
 * @param deletionBatch         most accounts the deletion job handles per run
 * @param maxTrackedKeys        most accounts the in-memory limiter remembers at once (memory bound)
 * @param sessionMaxAge         Phase 10A.4: how long after the password was typed a sign-in may still renew its token
 *                              without an SOS or Safe Walk running (during one, renewal is always allowed)
 * @param refreshIdleTimeout    Phase 11 C1: a device session ends when its refresh secret was not used for this long
 *                              (sliding: every refresh moves it). Long on purpose: an SOS after weeks unopened must
 *                              still reach the server without the password
 * @param refreshReuseGrace     Phase 11 C1: the secret just replaced is still accepted (and rotated again) for this
 *                              long, for an answer lost on a poor connection; shown later, it ends the session (reuse)
 * @param sessionRetention      Phase 11 C1: ended device sessions are deleted this long after they ended (kept a while
 *                              so the access tokens of a revoked session are refused by name)
 */
@ConfigurationProperties(prefix = "app.account")
public record AccountProperties(Integer passwordFailures, Duration passwordFailureWindow, Duration deletionGrace,
                                Duration deletionProtection, Integer deletionBatch, Integer maxTrackedKeys,
                                Duration sessionMaxAge, Duration refreshIdleTimeout, Duration refreshReuseGrace,
                                Duration sessionRetention) {

    public AccountProperties {
        if (refreshIdleTimeout == null) refreshIdleTimeout = Duration.ofDays(90);
        if (refreshReuseGrace == null) refreshReuseGrace = Duration.ofSeconds(60);
        if (sessionRetention == null) sessionRetention = Duration.ofDays(30);
        if (refreshIdleTimeout.compareTo(Duration.ofDays(1)) < 0
                || refreshReuseGrace.isNegative() || refreshReuseGrace.compareTo(Duration.ofMinutes(10)) > 0
                || sessionRetention.compareTo(Duration.ofDays(2)) < 0) {
            throw new IllegalStateException("app.account refresh/session values are invalid");
        }
        if (sessionMaxAge == null) sessionMaxAge = Duration.ofDays(30);
        if (sessionMaxAge.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalStateException("app.account.session-max-age must be at least 1 day");
        }
        if (passwordFailures == null) passwordFailures = 5;
        if (passwordFailureWindow == null) passwordFailureWindow = Duration.ofMinutes(15);
        if (deletionGrace == null) deletionGrace = Duration.ofDays(7);
        if (deletionProtection == null) deletionProtection = Duration.ofHours(24);
        if (deletionBatch == null) deletionBatch = 20;
        if (maxTrackedKeys == null) maxTrackedKeys = 100_000;
        if (passwordFailures < 1 || deletionBatch < 1 || maxTrackedKeys < 1
                || passwordFailureWindow.isNegative() || passwordFailureWindow.isZero()
                || deletionGrace.compareTo(Duration.ofDays(1)) < 0 || deletionProtection.isNegative()) {
            throw new IllegalStateException("app.account.* values are invalid");
        }
    }
}
