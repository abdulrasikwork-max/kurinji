package com.safesphere.backend.auth.phone;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Phase 17B: picks the one [OtpSender] from app.otp.provider and says at start-up which one runs. */
@Configuration
public class OtpSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(OtpSenderConfig.class);

    @Bean
    public OtpSender otpSender(OtpProperties properties, ObjectMapper objectMapper, Clock clock) {
        OtpSender sender = switch (properties.providerKind()) {
            case FAST2SMS -> new Fast2SmsOtpSender(properties.fast2smsUrl(), properties.fast2smsApiKey(), objectMapper);
            case DEV_FILE -> new DevFileOtpSender(devFile(properties), clock);
        };
        if (properties.providerKind() == OtpProperties.Provider.DEV_FILE) {
            log.warn("Verification codes: {}. Set OTP_PROVIDER=FAST2SMS and FAST2SMS_API_KEY to send real SMS.",
                    sender.describe());
        } else {
            log.info("Verification codes go out through {}", sender.describe());
        }
        return sender;
    }

    private static Path devFile(OtpProperties properties) {
        if (!properties.devFile().isBlank()) return Path.of(properties.devFile().trim());
        return Path.of(System.getProperty("java.io.tmpdir"), "kurinji-dev-otp.txt");
    }
}
