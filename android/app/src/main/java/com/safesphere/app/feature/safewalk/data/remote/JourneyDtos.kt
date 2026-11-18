package com.safesphere.app.feature.safewalk.data.remote

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import java.time.Instant

// Mirrors the JSON of /api/journeys (docs/API.md, "Phase N6"). No user id and no location anywhere.

data class StartJourneyRequestDto(val destinationLabel: String?, val checkInIntervalMinutes: Int) {
    /** The label is the person's own words: never in a log line. */
    override fun toString(): String = "StartJourneyRequestDto(interval=$checkInIntervalMinutes, label=<redacted>)"
}

data class JourneyDto(
    val id: Long,
    val status: String,
    val destinationLabel: String?,
    val checkInIntervalMinutes: Int,
    val escalationLevel: Int,
    val startedAt: String,
    val endedAt: String?,
    val nextCheckInDueAt: String?,
    val serverTime: String
) {
    override fun toString(): String = "JourneyDto(id=$id, status=$status, label=<redacted>)"

    fun toDomain() = Journey(
        id = id,
        status = JourneyStatus.fromApi(status),
        destinationLabel = destinationLabel,
        checkInIntervalMinutes = checkInIntervalMinutes,
        escalationLevel = EscalationLevel.fromCode(escalationLevel),
        startedAt = Instant.parse(startedAt),
        endedAt = endedAt?.let(Instant::parse),
        nextCheckInDueAt = nextCheckInDueAt?.let(Instant::parse),
        serverTime = Instant.parse(serverTime)
    )
}
