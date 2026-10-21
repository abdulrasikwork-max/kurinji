package com.safesphere.backend.emergency.dto;

import com.safesphere.backend.emergency.entity.LocationSource;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One location point sent by the phone. BigDecimal (not double) so NaN/Infinity cannot be sent and
 * the range checks are exact. accuracyMeters, source and batteryPercent are optional.
 */
public record LocationPointRequest(

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

        LocationSource source,

        @Min(value = 0, message = "Battery must be between 0 and 100")
        @Max(value = 100, message = "Battery must be between 0 and 100")
        Integer batteryPercent,

        @NotNull(message = "Recorded time is required")
        Instant recordedAt
) {

    /** Coordinates are never written to logs. */
    @Override
    public String toString() {
        return "LocationPointRequest{location=<redacted>, recordedAt=" + recordedAt + "}";
    }
}
