package com.safesphere.backend.auth.phone;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.otp.* settings (Phase 17B). Every value has a safe default; secrets only from the environment.
 *
 * @param provider               DEV_FILE (default: codes written to [devFile] on this computer, nothing sent) or FAST2SMS
 * @param secret                 HMAC key for stored codes (OTP_SECRET, 32+ characters); empty = derived from JWT_SECRET
 * @param devFile                where DEV_FILE writes codes; empty = kurinji-dev-otp.txt in the temp folder
 * @param fast2smsApiKey         FAST2SMS_API_KEY (required for FAST2SMS)
 * @param fast2smsUrl            the Fast2SMS endpoint (a test may point it at a local stub)
 * @param codeLifetime           how long one code works
 * @param challengeLifetime      how long the app's challenge token works (then: sign in again for a new one)
 * @param maxAttempts            wrong codes before the code is dead (ask for a new one)
 * @param resendCooldown         wait between two codes for one challenge
 * @param maxSendsPerChallenge   codes one challenge may send
 * @param sendsPerNumberPerDay   codes one number may receive per 24 h (all challenges; anti SMS bombing)
 * @param startsPerAddress       sign-up / start / resend / change requests per network address per [addressWindow]
 * @param verifyFailuresPerAddress wrong codes per network address per [addressWindow] (anti brute force)
 * @param addressWindow          the window of both address limits
 * @param unverifiedRetention    an account never activated this long after sign-up is removed by the clean-up
 * @param cleanupInterval        how often the clean-up runs
 */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(String provider, String secret, String devFile, String fast2smsApiKey, String fast2smsUrl,
                            Duration codeLifetime, Duration challengeLifetime, Integer maxAttempts,
                            Duration resendCooldown, Integer maxSendsPerChallenge, Integer sendsPerNumberPerDay,
                            Integer startsPerAddress, Integer verifyFailuresPerAddress, Duration addressWindow,
                            Duration unverifiedRetention, Duration cleanupInterval) {

    public enum Provider { DEV_FILE, FAST2SMS }

    public OtpProperties {
        if (provider == null || provider.isBlank()) provider = Provider.DEV_FILE.name();
        if (secret == null) secret = "";
        if (devFile == null) devFile = "";
        if (fast2smsApiKey == null) fast2smsApiKey = "";
        if (fast2smsUrl == null || fast2smsUrl.isBlank()) fast2smsUrl = "https://www.fast2sms.com/dev/bulkV2";
        if (codeLifetime == null) codeLifetime = Duration.ofMinutes(5);
        if (challengeLifetime == null) challengeLifetime = Duration.ofMinutes(30);
        if (maxAttempts == null) maxAttempts = 5;
        if (resendCooldown == null) resendCooldown = Duration.ofSeconds(60);
        if (maxSendsPerChallenge == null) maxSendsPerChallenge = 5;
        if (sendsPerNumberPerDay == null) sendsPerNumberPerDay = 8;
        if (startsPerAddress == null) startsPerAddress = 30;
        if (verifyFailuresPerAddress == null) verifyFailuresPerAddress = 50;
        if (addressWindow == null) addressWindow = Duration.ofHours(1);
        if (unverifiedRetention == null) unverifiedRetention = Duration.ofDays(7);
        if (cleanupInterval == null) cleanupInterval = Duration.ofHours(1);
        try {
            Provider.valueOf(provider.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.otp.provider must be DEV_FILE or FAST2SMS");
        }
        if (!secret.isEmpty() && secret.length() < 32) {
            throw new IllegalStateException("OTP_SECRET must be at least 32 characters (or empty to derive it)");
        }
        if (notPositive(codeLifetime) || notPositive(challengeLifetime) || notPositive(resendCooldown) || notPositive(addressWindow)
                || notPositive(unverifiedRetention) || notPositive(cleanupInterval) || maxAttempts < 1 || maxAttempts > 20
                || maxSendsPerChallenge < 1 || maxSendsPerChallenge > 20 || sendsPerNumberPerDay < 1
                || startsPerAddress < 1 || verifyFailuresPerAddress < 1
                || challengeLifetime.compareTo(codeLifetime) < 0
                || unverifiedRetention.compareTo(challengeLifetime) <= 0) {
            throw new IllegalStateException("app.otp.* values are invalid");
        }
    }

    public Provider providerKind() {
        return Provider.valueOf(provider.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean notPositive(Duration d) {
        return d.isNegative() || d.isZero();
    }

    /** Never prints the secret or the API key. */
    @Override
    public String toString() {
        return "OtpProperties[provider=" + provider + "]";
    }
}
