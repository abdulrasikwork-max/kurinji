package com.safesphere.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Shared test clock for the Phase 7 suites (one Spring context for all classes that import it). */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    @Primary
    public MutableClock sharedMutableClock() {
        return new MutableClock();
    }
}
