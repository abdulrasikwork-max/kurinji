package com.safesphere.backend.emergency.dto;

import com.safesphere.backend.emergency.entity.TriggerType;

/** Optional body of POST /api/emergencies. No userId: the owner comes from the JWT. Default trigger: MANUAL. */
public record CreateEmergencyRequest(TriggerType triggerType) {
}
