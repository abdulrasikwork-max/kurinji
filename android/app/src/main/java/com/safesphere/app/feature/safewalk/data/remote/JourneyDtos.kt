package com.safesphere.app.feature.safewalk.data.remote

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import java.time.Instant

// Mirrors the JSON of /api/journeys (see docs/API.md). No user id anywhere: the server uses the token.

/** Gson leaves out null fields, so a walk without destination coordinates sends none. */
data class StartJourneyRequestDto(
    val destinationLabel: String?,
    val checkInIntervalMinutes: Int,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null
) {
    override fun toString(): String = "StartJourneyRequestDto(interval=$checkInIntervalMinutes, destination=<redacted>)"
}

/** [reason] only for a level 1 raised by a movement check (Phase 4C); null = the server's default. */
data class EscalationRequestDto(val level: Int, val reason: String? = null)

data class JourneyLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val recordedAt: String
) {
    override fun toString(): String = "JourneyLocationDto(location=<redacted>, recordedAt=$recordedAt)"

    companion object {
        fun from(fix: LocationFix) = JourneyLocationDto(fix.latitude, fix.longitude, fix.accuracyMeters, fix.recordedAt.toString())
    }
}

data class DestinationDto(val latitude: Double, val longitude: Double) {
    override fun toString(): String = "DestinationDto(<redacted>)"
}

/** The server also returns lastLocation; the app does not need it back, so it is not mapped. */
data class JourneyDto(
    val id: Long,
    val status: String,
    val destinationLabel: String?,
    val destination: DestinationDto?,
    val checkInIntervalMinutes: Int,
    val escalationLevel: Int,
    val startedAt: String,
    val endedAt: String?,
    val nextCheckInDueAt: String?,
    val serverTime: String
) {
    override fun toString(): String = "JourneyDto(id=$id, status=$status, destination=<redacted>)"

    fun toDomain() = Journey(
        id = id,
        status = JourneyStatus.fromApi(status),
        destinationLabel = destinationLabel,
        checkInIntervalMinutes = checkInIntervalMinutes,
        escalationLevel = EscalationLevel.fromCode(escalationLevel),
        startedAt = Instant.parse(startedAt),
        endedAt = endedAt?.let(Instant::parse),
        nextCheckInDueAt = nextCheckInDueAt?.let(Instant::parse),
        serverTime = Instant.parse(serverTime),
        destination = destination?.let { GeoPoint(it.latitude, it.longitude) }
    )
}
