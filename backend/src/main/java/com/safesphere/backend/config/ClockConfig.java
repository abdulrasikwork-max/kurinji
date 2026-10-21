package com.safesphere.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The server's clock as a bean, so time-based rules (location time windows, upload grace) use the
 * server's time, never the phone's, and tests can replace it to move time forward.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
