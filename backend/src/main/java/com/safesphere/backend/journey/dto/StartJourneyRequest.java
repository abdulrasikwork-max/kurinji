package com.safesphere.backend.journey.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of POST /api/journeys. No user id: the owner comes from the JWT. The label is optional free text.
 * The destination coordinates (Phase 4C) are optional too, but must be sent together.
 */
public record StartJourneyRequest(

        @Size(max = 100, message = "Destination must be at most 100 characters")
        String destinationLabel,

        @NotNull(message = "Check-in interval is required")
        @Min(value = 2, message = "Check-in interval must be between 2 and 60 minutes")
        @Max(value = 60, message = "Check-in interval must be between 2 and 60 minutes")
        Integer checkInIntervalMinutes,

        @DecimalMin(value = "-90", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90", message = "Latitude must be between -90 and 90")
        BigDecimal destinationLatitude,

        @DecimalMin(value = "-180", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180", message = "Longitude must be between -180 and 180")
        BigDecimal destinationLongitude
) {

    /** Coordinates are never written to logs. */
    @Override
    public String toString() {
        return "StartJourneyRequest{interval=" + checkInIntervalMinutes + ", destination=<redacted>}";
    }
}
