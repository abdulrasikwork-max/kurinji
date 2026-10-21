package com.safesphere.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled methods. Phase 6: CommunityPurgeJob (data deletion); Phase 10A: AccountDeletionJob (deletes
 * accounts whose grace period is over). Both only delete; nothing scheduled ever sends a message.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
