package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.GeoPoint
import java.time.Instant

/** Matches the backend. ACTIVE ends as COMPLETED ("I've arrived"), CANCELLED, or EMERGENCY (SOS during the walk). */
enum class JourneyStatus {
    ACTIVE, COMPLETED, CANCELLED, EMERGENCY;

    companion object {
        fun fromApi(value: String): JourneyStatus = entries.firstOrNull { it.name == value } ?: ACTIVE
    }
}

/**
 * A Safe Walk as the server sees it. [nextCheckInDueAt] and [serverTime] are both SERVER clock: the phone
 * turns them into its own deadline with [com.safesphere.app.feature.safewalk.domain.CheckInPolicy.localDeadline].
 * [destination] (Phase 4C) is the place found for [destinationLabel], used by the Safety Bubble; null if none.
 */
data class Journey(
    val id: Long,
    val status: JourneyStatus,
    val destinationLabel: String?,
    val checkInIntervalMinutes: Int,
    val escalationLevel: EscalationLevel,
    val startedAt: Instant,
    val endedAt: Instant?,
    val nextCheckInDueAt: Instant?,
    val serverTime: Instant,
    val destination: GeoPoint? = null
)
