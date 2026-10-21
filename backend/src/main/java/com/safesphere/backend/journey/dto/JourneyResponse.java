package com.safesphere.backend.journey.dto;

import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.entity.SafeJourney;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * What the API returns about a journey. [serverTime] lets the phone convert [nextCheckInDueAt] into its own
 * clock (deadline - serverTime = time left), so a phone whose clock is wrong still counts down correctly.
 * [lastLocation] is null until the phone sends one. [destination] (Phase 4C) is null when no destination
 * coordinates were sent, and after a walk ended normally. Owner only, like every journey endpoint.
 */
public record JourneyResponse(
        Long id,
        JourneyStatus status,
        String destinationLabel,
        Destination destination,
        int checkInIntervalMinutes,
        int escalationLevel,
        Instant startedAt,
        Instant endedAt,
        Instant nextCheckInDueAt,
        LastLocation lastLocation,
        Instant serverTime
) {

    public record LastLocation(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracyMeters, Instant recordedAt) {
        @Override
        public String toString() {
            return "LastLocation{location=<redacted>, recordedAt=" + recordedAt + "}";
        }
    }

    public record Destination(BigDecimal latitude, BigDecimal longitude) {
        @Override
        public String toString() {
            return "Destination{location=<redacted>}";
        }
    }

    public static JourneyResponse from(SafeJourney journey, Instant serverTime) {
        LastLocation location = journey.getLastLocationAt() == null ? null : new LastLocation(
                journey.getLastLatitude(), journey.getLastLongitude(), journey.getLastAccuracyMeters(),
                utc(journey.getLastLocationAt()));
        Destination destination = journey.hasDestination()
                ? new Destination(journey.getDestinationLatitude(), journey.getDestinationLongitude())
                : null;
        return new JourneyResponse(
                journey.getId(),
                journey.getStatus(),
                journey.getDestinationLabel(),
                destination,
                journey.getCheckInIntervalMinutes(),
                journey.getEscalationLevel().code(),
                utc(journey.getStartedAt()),
                utc(journey.getEndedAt()),
                utc(journey.getNextCheckInDueAt()),
                location,
                serverTime
        );
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
