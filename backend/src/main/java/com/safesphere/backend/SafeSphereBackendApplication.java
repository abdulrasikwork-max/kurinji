package com.safesphere.backend;

import com.safesphere.backend.config.UtcTimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. {@code @ConfigurationPropertiesScan} finds JwtProperties so the {@code app.jwt.*}
 * settings are available to the rest of the application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SafeSphereBackendApplication {

    public static void main(String[] args) {
        // Before Spring and any database connection: timestamps are stored as UTC whatever PC runs the backend.
        UtcTimeZone.install();
        SpringApplication.run(SafeSphereBackendApplication.class, args);
    }
}
