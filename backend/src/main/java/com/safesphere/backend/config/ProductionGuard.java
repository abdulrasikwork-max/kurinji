package com.safesphere.backend.config;

import com.safesphere.backend.auth.phone.OtpProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Phase 17J: with the {@code prod} profile active the backend REFUSES TO START when a setting is still a development
 * convenience. Without the profile nothing changes (development and tests are untouched).
 *
 * <p>What is refused, and why:
 * <ul>
 *   <li>{@code OTP_PROVIDER} other than FAST2SMS (or no API key): DEV_FILE writes sign-up codes to a file on the server
 *       and sends nothing, so nobody could verify a phone, and anyone who can read the file could.</li>
 *   <li>an empty {@code OTP_SECRET}: the code-hash key would then be derived from {@code JWT_SECRET}; one leak would
 *       open both.</li>
 *   <li>a database URL without TLS ({@code useSSL=false}, or no {@code sslMode=REQUIRED|VERIFY_CA|VERIFY_IDENTITY}):
 *       passwords and personal data would cross the network in clear text.</li>
 *   <li>N6: ALERT_PROVIDER other than FAST2SMS, or an ALERT_SECRET that is empty or equal to another secret.</li>
 *   <li>a relative photo folder: it would follow whatever directory the process was started from, so a
 *       restart elsewhere would silently "lose" every file.</li>
 * </ul>
 * Things that are only worth a warning (the operator may do it on purpose) are logged, not refused.
 */
@Component
@Profile("prod")
public class ProductionGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionGuard.class);

    public ProductionGuard(OtpProperties otp, SocialProperties social, CommunityProperties community, JwtProperties jwt,
                           AlertProperties alerts,
                           @Value("${spring.datasource.url:}") String databaseUrl,
                           @Value("${app.moderation.ai.key:}") String moderationKey) {
        List<String> problems = problems(otp.providerKind().name(), otp.fast2smsApiKey(), otp.secret(), databaseUrl,
                social.mediaDir());
        problems.addAll(communityProblems(community.secret(), jwt.secret(), community.photoDir()));
        problems.addAll(alertProblems(alerts.providerKind().name(), alerts.secret(), jwt.secret(), otp.secret()));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with the prod profile: " + String.join("; ", problems));
        }
        if (moderationKey == null || moderationKey.isBlank()) {
            log.warn("Production: MODERATION_SERVICE_KEY is empty, so posts are published without the automatic check.");
        }
        log.info("Production guard passed");
    }

    /**
     * Phase N3: the community secret must be its own (never derived from, or equal to, JWT_SECRET: rotating the token key
     * must not break bans and roll-number uniqueness) and the ID-photo folder absolute (like the media folder).
     */
    public static List<String> communityProblems(String communitySecret, String jwtSecret, Path photoDir) {
        List<String> out = new ArrayList<>();
        if (communitySecret == null || communitySecret.isBlank()) {
            out.add("COMMUNITY_SECRET must be set (32+ random characters, backed up apart from the database)");
        } else if (communitySecret.equals(jwtSecret)) {
            out.add("COMMUNITY_SECRET must differ from JWT_SECRET");
        }
        if (photoDir == null || !photoDir.isAbsolute()) {
            out.add("COMMUNITY_PHOTO_DIR must be an absolute path");
        }
        return out;
    }

    /**
     * Phase N6: Safety Circle texts must really go out (DEV_FILE only writes them to a file), and the number key must be
     * its own: derived from JWT_SECRET, rotating the token key would silently empty the stop list.
     */
    public static List<String> alertProblems(String alertProvider, String alertSecret, String jwtSecret, String otpSecret) {
        List<String> out = new ArrayList<>();
        if (!"FAST2SMS".equalsIgnoreCase(alertProvider)) {
            out.add("ALERT_PROVIDER must be FAST2SMS (DEV_FILE sends no Safety Circle text and writes them to a file)");
        }
        if (alertSecret == null || alertSecret.isBlank()) {
            out.add("ALERT_SECRET must be set (32+ random characters, backed up apart from the database)");
        } else if (alertSecret.equals(jwtSecret) || alertSecret.equals(otpSecret)) {
            out.add("ALERT_SECRET must differ from JWT_SECRET and OTP_SECRET");
        }
        return out;
    }

    /** What is wrong, in words for the operator; empty = fine. Pure, so it is tested without starting Spring. */
    public static List<String> problems(String otpProvider, String fast2smsApiKey, String otpSecret, String databaseUrl,
                                        Path mediaDir) {
        List<String> out = new ArrayList<>();
        if (!"FAST2SMS".equalsIgnoreCase(otpProvider)) {
            out.add("OTP_PROVIDER must be FAST2SMS (DEV_FILE sends no SMS and writes codes to a file)");
        } else if (fast2smsApiKey == null || fast2smsApiKey.isBlank()) {
            out.add("FAST2SMS_API_KEY is empty");
        }
        if (otpSecret == null || otpSecret.isBlank()) {
            out.add("OTP_SECRET must be set (32+ random characters) instead of being derived from JWT_SECRET");
        }
        String url = databaseUrl == null ? "" : databaseUrl.toLowerCase(Locale.ROOT);
        if (url.contains("usessl=false") || url.contains("sslmode=disabled") || url.contains("sslmode=preferred")
                || !(url.contains("sslmode=required") || url.contains("sslmode=verify_ca")
                || url.contains("sslmode=verify_identity"))) {
            out.add("DB_URL must use TLS: add sslMode=REQUIRED (or VERIFY_CA / VERIFY_IDENTITY) and remove useSSL=false");
        }
        if (mediaDir == null || !mediaDir.isAbsolute()) {
            out.add("SOCIAL_MEDIA_DIR must be an absolute path");
        }
        return out;
    }
}
