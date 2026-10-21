package com.safesphere.backend.journey.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/** Body of PUT /api/journeys/{id}/location: replaces the journey's last known location. */
public record JourneyLocationRequest(

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90", message = "Latitude must be between -90 and 90")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180", message = "Longitude must be between -180 and 180")
        BigDecimal longitude,

        @DecimalMin(value = "0", message = "Accuracy must be between 0 and 100000 metres")
        @DecimalMax(value = "100000", message = "Accuracy must be between 0 and 100000 metres")
        BigDecimal accuracyMeters,

        @NotNull(message = "Recorded time is required")
        Instant recordedAt
) {

    /** Coordinates are never written to logs. */
    @Override
    public String toString() {
        return "JourneyLocationRequest{location=<redacted>, recordedAt=" + recordedAt + "}";
    }
}
