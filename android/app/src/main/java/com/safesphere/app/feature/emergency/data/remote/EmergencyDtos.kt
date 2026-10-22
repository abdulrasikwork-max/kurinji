package com.safesphere.app.feature.emergency.data.remote

import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import java.time.Instant

// Mirrors the JSON of /api/emergencies (see docs/API.md). No userId: the server uses the token.

/** Phase 11 adds the phone's reference and, for an SOS that began / ended offline, its times (nulls are left out). */
data class CreateEmergencyRequestDto(
    val triggerType: String,
    val clientRef: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val outcome: String? = null
)

data class EmergencyEventDto(
    val id: Long,
    val status: String,
    val triggerType: String,
    val startedAt: String,
    val endedAt: String?,
    val endedElsewhere: Boolean? = null,
    val startedOffline: Boolean? = null
) {
    fun toDomain() = EmergencyEvent(
        id = id,
        status = EmergencyStatus.fromApi(status),
        trigger = EmergencyTrigger.fromApi(triggerType),
        startedAt = Instant.parse(startedAt),
        endedAt = endedAt?.let { Instant.parse(it) },
        endedElsewhere = endedElsewhere == true,
        startedOffline = startedOffline == true
    )
}

/**
 * Phase 10A.4: body of resolve / cancel. Null [password] is left out of the JSON (Gson skips nulls): the phone that
 * started the SOS needs none; another sign-in must send the account password. Phase 11: [endedAt] when it was ended
 * on this phone while offline.
 */
data class EndEmergencyRequestDto(val password: String? = null, val endedAt: String? = null) {
    override fun toString() = "EndEmergencyRequestDto(<hidden>)"
}
