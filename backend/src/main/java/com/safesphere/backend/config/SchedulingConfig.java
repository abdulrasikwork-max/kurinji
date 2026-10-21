package com.safesphere.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled methods. Phase 6: only CommunityPurgeJob (data deletion), nothing that sends messages. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
