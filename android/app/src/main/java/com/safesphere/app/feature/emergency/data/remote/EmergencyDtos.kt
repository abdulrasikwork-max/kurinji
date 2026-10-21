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
    val endedAt: String?,
    val endedElsewhere: Boolean? = null
) {
    fun toDomain() = EmergencyEvent(
        id = id,
        status = EmergencyStatus.fromApi(status),
        trigger = EmergencyTrigger.fromApi(triggerType),
        startedAt = Instant.parse(startedAt),
        endedAt = endedAt?.let { Instant.parse(it) },
        endedElsewhere = endedElsewhere == true
    )
}

/**
 * Phase 10A.4: body of resolve / cancel. Null [password] is left out of the JSON (Gson skips nulls): the phone that
 * started the SOS needs none; another sign-in must send the account password.
 */
data class EndEmergencyRequestDto(val password: String? = null) {
    override fun toString() = "EndEmergencyRequestDto(<hidden>)"
}
