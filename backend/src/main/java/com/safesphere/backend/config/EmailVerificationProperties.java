package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.email-verification.* settings (V25, docs/EMAIL_VERIFICATION.md). Every value has a safe default.
 *
 * @param linkBaseUrl           where the verification page is served, without a trailing slash (env PUBLIC_BASE_URL);
 *                              the e-mail links to [linkBaseUrl]/verify-email#t=TOKEN. HTTPS in any real deployment.
 * @param tokenLifetime         how long a link works (60 min: e-mail can be slow; one live token, single use)
 * @param resendsPerAccount     verification e-mails one account may ask for per [resendWindow]
 * @param resendsPerAddress     resend requests from one network address per [resendWindow] (spam brake)
 * @param resendWindow          the window of both resend limits
 * @param verifyFailuresPerAddress failed verification attempts from one network address per [verifyWindow]
 * @param verifyWindow          the window of the verification-attempt limit
 * @param unverifiedRetention   an account still unverified this long after sign-up is removed by the clean-up
 * @param cleanupInterval       how often the clean-up runs
 */
@ConfigurationProperties(prefix = "app.email-verification")
public record EmailVerificationProperties(String linkBaseUrl, Duration tokenLifetime, Integer resendsPerAccount,
                                          Integer resendsPerAddress, Duration resendWindow,
                                          Integer verifyFailuresPerAddress, Duration verifyWindow,
                                          Duration unverifiedRetention, Duration cleanupInterval) {

    public EmailVerificationProperties {
        if (linkBaseUrl == null || linkBaseUrl.isBlank()) linkBaseUrl = "http://localhost:8080";
        linkBaseUrl = linkBaseUrl.trim();
        while (linkBaseUrl.endsWith("/")) linkBaseUrl = linkBaseUrl.substring(0, linkBaseUrl.length() - 1);
        if (tokenLifetime == null) tokenLifetime = Duration.ofMinutes(60);
        if (resendsPerAccount == null) resendsPerAccount = 3;
        if (resendsPerAddress == null) resendsPerAddress = 20;
        if (resendWindow == null) resendWindow = Duration.ofHours(1);
        if (verifyFailuresPerAddress == null) verifyFailuresPerAddress = 30;
        if (verifyWindow == null) verifyWindow = Duration.ofMinutes(15);
        if (unverifiedRetention == null) unverifiedRetention = Duration.ofDays(7);
        if (cleanupInterval == null) cleanupInterval = Duration.ofHours(1);
        if (!linkBaseUrl.startsWith("http://") && !linkBaseUrl.startsWith("https://")) {
            throw new IllegalStateException("app.email-verification.link-base-url must be an http(s) URL");
        }
        if (tokenLifetime.isNegative() || tokenLifetime.isZero() || resendsPerAccount < 1 || resendsPerAddress < 1
                || resendWindow.isNegative() || resendWindow.isZero() || verifyFailuresPerAddress < 1
                || verifyWindow.isNegative() || verifyWindow.isZero()
                || unverifiedRetention.compareTo(tokenLifetime) <= 0 || cleanupInterval.isNegative()
                || cleanupInterval.isZero()) {
            throw new IllegalStateException("app.email-verification.* values are invalid");
        }
    }
}
