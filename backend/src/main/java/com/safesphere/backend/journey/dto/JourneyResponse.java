package com.safesphere.backend.journey.dto;

import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.entity.SafeJourney;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * What the API returns about a walk ("On my way"). [serverTime] lets the phone convert [nextCheckInDueAt] into its own
 * clock (deadline - serverTime = time left), so a phone whose clock is wrong still counts down correctly.
 * N6-B1: no coordinates of any kind (no destination, no last location). [escalationLevel] is 0 while the walk runs and
 * 3 when an SOS ended it. Owner only, like every journey endpoint.
 */
public record JourneyResponse(
        Long id,
        JourneyStatus status,
        String destinationLabel,
        int checkInIntervalMinutes,
        int escalationLevel,
        Instant startedAt,
        Instant endedAt,
        Instant nextCheckInDueAt,
        Instant serverTime
) {

    public static JourneyResponse from(SafeJourney journey, Instant serverTime) {
        return new JourneyResponse(
                journey.getId(),
                journey.getStatus(),
                journey.getDestinationLabel(),
                journey.getCheckInIntervalMinutes(),
                journey.getEscalationLevel().code(),
                utc(journey.getStartedAt()),
                utc(journey.getEndedAt()),
                utc(journey.getNextCheckInDueAt()),
                serverTime
        );
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
