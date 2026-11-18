package com.safesphere.backend.circlealert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.auth.phone.OtpProperties;
import com.safesphere.backend.common.sms.DevFileSmsGateway;
import com.safesphere.backend.common.sms.Fast2SmsClient;
import com.safesphere.backend.common.sms.SmsGateway;
import com.safesphere.backend.config.AlertProperties;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * N6: picks the one [SmsGateway] for Safety Circle alerts from app.alerts.provider and says at start-up which one runs.
 * FAST2SMS uses the shared client with the key and URL of app.otp.* (one provider account).
 */
@Configuration
public class AlertGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertGatewayConfig.class);

    @Bean
    public SmsGateway alertSmsGateway(AlertProperties alerts, OtpProperties otp, ObjectMapper objectMapper, Clock clock) {
        SmsGateway gateway = switch (alerts.providerKind()) {
            case FAST2SMS -> new Fast2SmsClient(otp.fast2smsUrl(), otp.fast2smsApiKey(), objectMapper);
            case DEV_FILE -> new DevFileSmsGateway(devFile(alerts), clock);
        };
        if (alerts.providerKind() == AlertProperties.Provider.DEV_FILE) {
            log.warn("Safety Circle alerts: {}. Set ALERT_PROVIDER=FAST2SMS to send real SMS.", gateway.describe());
        } else {
            log.info("Safety Circle alerts go out through {}", gateway.describe());
        }
        return gateway;
    }

    private static Path devFile(AlertProperties properties) {
        if (!properties.devFile().isBlank()) return Path.of(properties.devFile().trim());
        return Path.of(System.getProperty("java.io.tmpdir"), "irl-dev-alerts.txt");
    }
}
