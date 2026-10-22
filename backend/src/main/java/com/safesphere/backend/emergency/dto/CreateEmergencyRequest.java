package com.safesphere.backend.emergency.dto;

import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.entity.TriggerType;
import java.time.Instant;

/**
 * Optional body of POST /api/emergencies. No userId: the owner comes from the JWT. Default trigger: MANUAL.
 *
 * Phase 11 (SOS without internet): [clientRef] = the phone's random UUID for this SOS; the same reference always
 * returns the same emergency (safe re-sends). [startedAt] is sent only when the SOS began before the server could be
 * reached (then it needs a [clientRef]); [outcome] (RESOLVED or CANCELLED) + [endedAt] record an SOS that also ENDED
 * while the phone was offline. The server clamps phone times; it never trusts them for anyone but the owner.
 */
public record CreateEmergencyRequest(
        TriggerType triggerType,
        String clientRef,
        Instant startedAt,
        Instant endedAt,
        EmergencyStatus outcome
) {

    /** Phase 3 shape (trigger only). */
    public CreateEmergencyRequest(TriggerType triggerType) {
        this(triggerType, null, null, null, null);
    }
}
