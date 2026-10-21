package com.safesphere.app.feature.safewalk.domain

import java.time.Instant

/**
 * What the phone remembers about the active walk so it survives a restart, even offline. No location and no
 * destination (the destination's coordinates come back from the server).
 *
 * @param localDueAt     the next check-in deadline in PHONE time (already converted from the server's)
 * @param pendingEnd     the person ended the walk but the server has not confirmed it yet
 * @param movementChecks the person's choice for stop / route checks (Phase 4C)
 * @param concern        an open "Are you OK?" prompt raised by a movement check (kind + time only, Phase 4C)
 */
data class ActiveJourneyRecord(
    val userId: Long,
    val journeyId: Long,
    val checkInIntervalMinutes: Int,
    val startedAt: Instant,
    val localDueAt: Instant,
    val pendingEnd: JourneyStatus? = null,
    val movementChecks: Boolean = true,
    val concern: Concern? = null
)

interface ActiveJourneyStore {
    suspend fun load(): ActiveJourneyRecord?
    suspend fun save(record: ActiveJourneyRecord)
    suspend fun clear()
}
