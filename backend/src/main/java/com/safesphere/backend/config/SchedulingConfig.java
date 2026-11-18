package com.safesphere.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled methods (deletions, upkeep). Phase N6: AlertDispatcher sends the Safety Circle texts a person chose;
 * Phase N7: PushDispatcher sends push pointers (docs/PHASE_N7.md). These are the TWO scheduled tasks that send
 * anything off the server.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
