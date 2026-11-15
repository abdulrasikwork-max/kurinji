package com.safesphere.backend.auth.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.AuthProperties;
import com.safesphere.backend.security.AttemptLimiter;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Sign-up abuse limit (Phase 7): new accounts per network ADDRESS, which slows account farming.
 *
 * Phase N2: the password sign-in limits (per e-mail, per address) are gone with the password. Guessing a code is limited
 * by PhoneCodeService instead (5 tries per code, a cap of codes per number per day, wrong codes per address).
 *
 * Network addresses are the address the servlet container reports, which is the TCP peer address by default;
 * X-Forwarded-For is NOT trusted by default (any client can forge it). Only when forward-headers-strategy=native is set (the
 * production profile behind nginx / a Cloudflare Tunnel, FORWARD_HEADERS_STRATEGY) does Tomcat replace it with the header,
 * and then only for a request that comes from a trusted proxy on this machine (RealClientAddress*Test). Nothing is
 * written to the database or the log.
 */
@Component
public class AuthThrottle {

    static final String TOO_MANY_SIGN_UPS = "Too many new accounts from this network. Please try again later.";

    private final AttemptLimiter registrations;

    public AuthThrottle(AuthProperties properties, Clock clock) {
        this.registrations = new AttemptLimiter(properties.registrationsPerAddress(), properties.registrationWindow(),
                properties.maxTrackedKeys(), clock);
    }

    /** Counts one sign-up attempt from [address]; refuses (429) when the address is over its limit. */
    public void checkRegistration(String address) {
        if (!registrations.tryAcquire(address == null || address.isBlank() ? "unknown" : address)) {
            throw new TooManyAttemptsException(TOO_MANY_SIGN_UPS);
        }
    }

    /** Drops finished windows (called by the periodic clean-up). */
    public void evictExpired() {
        registrations.evictExpired();
    }
}
