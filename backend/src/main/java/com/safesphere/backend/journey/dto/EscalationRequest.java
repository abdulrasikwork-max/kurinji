package com.safesphere.backend.journey.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/journeys/{id}/escalations: the level the phone's policy reached.
 * [reason] is optional and only meaningful for level 1: CHECK_IN_MISSED (the default), ROUTE_DEVIATION or
 * UNEXPECTED_STOP (Phase 4C). Level 2 is always NO_RESPONSE. The server checks the pair, so they can never disagree.
 */
public record EscalationRequest(
        @NotNull(message = "Level is required") Integer level,
        @Size(max = 30, message = "Unknown escalation reason") String reason
) {
}
