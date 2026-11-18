package com.safesphere.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled methods (deletions, upkeep). Phase N6: AlertDispatcher is the ONE scheduled task that sends
 * messages: the Safety Circle texts a person chose, after their own SOS (circlealert, docs/PHASE_N6.md §3).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
