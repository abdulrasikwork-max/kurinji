package com.safesphere.backend.emergency.dto;

import com.safesphere.backend.emergency.entity.EmergencyLocation;
import com.safesphere.backend.emergency.entity.LocationSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

/** One stored location point, as returned to the emergency's owner. */
public record LocationResponse(
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal accuracyMeters,
        LocationSource source,
        Integer batteryPercent,
        Instant recordedAt,
        Instant receivedAt
) {

    public static LocationResponse from(EmergencyLocation location) {
        return new LocationResponse(
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracyMeters(),
                location.getSource(),
                location.getBatteryPercent(),
                location.getRecordedAt().toInstant(ZoneOffset.UTC),
                location.getReceivedAt().toInstant(ZoneOffset.UTC)
        );
    }

    /** Coordinates are never written to logs. */
    @Override
    public String toString() {
        return "LocationResponse{location=<redacted>, recordedAt=" + recordedAt + "}";
    }
}
