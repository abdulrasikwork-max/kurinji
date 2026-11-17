package com.safesphere.backend.journey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/journeys ("On my way", N6 D22). No user id: the owner comes from the JWT. The label is optional
 * free text the person typed ("Home", "Library"). N6-B1: no destination coordinates any more (the walk no longer looks
 * up or checks a destination); old clients that still send them are ignored by Jackson, never stored.
 */
public record StartJourneyRequest(

        @Size(max = 100, message = "Destination must be at most 100 characters")
        String destinationLabel,

        @NotNull(message = "Check-in interval is required")
        @Min(value = 2, message = "Check-in interval must be between 2 and 60 minutes")
        @Max(value = 60, message = "Check-in interval must be between 2 and 60 minutes")
        Integer checkInIntervalMinutes
) {

    /** The label is the person's own words: never written to logs. */
    @Override
    public String toString() {
        return "StartJourneyRequest{interval=" + checkInIntervalMinutes + ", label=<redacted>}";
    }
}
