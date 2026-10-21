package com.safesphere.app.feature.emergency.data.remote

import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import java.time.Instant

// Mirrors the JSON of /api/emergencies (see docs/API.md). No userId: the server uses the token.

data class CreateEmergencyRequestDto(val triggerType: String)

data class EmergencyEventDto(
    val id: Long,
    val status: String,
    val triggerType: String,
    val startedAt: String,
    val endedAt: String?
) {
    fun toDomain() = EmergencyEvent(
        id = id,
        status = EmergencyStatus.fromApi(status),
        trigger = EmergencyTrigger.fromApi(triggerType),
        startedAt = Instant.parse(startedAt),
        endedAt = endedAt?.let { Instant.parse(it) }
    )
}
