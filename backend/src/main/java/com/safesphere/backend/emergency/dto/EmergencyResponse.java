package com.safesphere.backend.emergency.dto;

import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.entity.TriggerType;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * What the API returns about an emergency. endedAt is null while it is still ACTIVE; journeyId is the Safe Walk
 * it was started from (Phase 4B), or null. endedElsewhere (Phase 10A.4): it was ended from another sign-in than the one
 * that started it (that needed the account password). The session id itself is never returned.
 * startedOffline (Phase 11): the SOS began on a phone without a connection; its times are the phone's. The phone's
 * reference (clientRef) is never returned either: the phone already knows it.
 */
public record EmergencyResponse(
        Long id,
        EmergencyStatus status,
        TriggerType triggerType,
        Instant startedAt,
        Instant endedAt,
        Long journeyId,
        boolean endedElsewhere,
        boolean startedOffline
) {

    public static EmergencyResponse from(EmergencyEvent event) {
        return new EmergencyResponse(
                event.getId(),
                event.getStatus(),
                event.getTriggerType(),
                event.getStartedAt().toInstant(ZoneOffset.UTC),
                event.getEndedAt() == null ? null : event.getEndedAt().toInstant(ZoneOffset.UTC),
                event.getJourneyId(),
                event.isEndedElsewhere(),
                event.isStartedOffline()
        );
    }
}
