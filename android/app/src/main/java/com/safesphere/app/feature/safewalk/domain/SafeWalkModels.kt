package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.escalation.EscalationLevel
import java.time.Instant

/** Matches the backend. ACTIVE ends as COMPLETED ("I'm there"), CANCELLED, or EMERGENCY (SOS during the walk). */
enum class JourneyStatus {
    ACTIVE, COMPLETED, CANCELLED, EMERGENCY;

    companion object {
        fun fromApi(value: String): JourneyStatus = entries.firstOrNull { it.name == value } ?: ACTIVE
    }
}

/**
 * An "On my way" walk as the server sees it (N6, D22; code name Safe Walk). [nextCheckInDueAt] and [serverTime] are both
 * SERVER clock: the phone turns them into its own reminder time with [CheckInPolicy.localDeadline]. No location of any
 * kind: [destinationLabel] is only the person's own words ("Home", "Library").
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
    val serverTime: Instant
)
