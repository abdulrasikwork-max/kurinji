package com.safesphere.backend.push.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.config.PushProperties;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * N7: picks the one [PushSender] from app.push.provider and says at start-up which one runs. NONE still gets a sender
 * (the dev file one) but PushService queues nothing, so nothing is ever sent.
 */
@Configuration
public class PushSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(PushSenderConfig.class);

    @Bean
    public PushSender pushSender(PushProperties properties, ObjectMapper objectMapper, Clock clock) {
        PushSender sender = switch (properties.providerKind()) {
            case FCM -> {
                if (properties.credentialsFile().isBlank()) {
                    throw new IllegalStateException("PUSH_PROVIDER=FCM needs FCM_CREDENTIALS_FILE");
                }
                yield new FcmClient(Path.of(properties.credentialsFile().trim()), objectMapper, clock);
            }
            case DEV_FILE, NONE -> new DevFilePushSender(devFile(properties), clock);
        };
        switch (properties.providerKind()) {
            case FCM -> log.info("Push notifications go out through {}", sender.describe());
            case DEV_FILE -> log.warn("Push notifications: {}. Set PUSH_PROVIDER=FCM to send real pushes.", sender.describe());
            case NONE -> log.warn("Push notifications are OFF (PUSH_PROVIDER=NONE): nothing is queued or sent.");
        }
        return sender;
    }

    private static Path devFile(PushProperties properties) {
        if (!properties.devFile().isBlank()) return Path.of(properties.devFile().trim());
        return Path.of(System.getProperty("java.io.tmpdir"), "irl-dev-pushes.txt");
    }
}
