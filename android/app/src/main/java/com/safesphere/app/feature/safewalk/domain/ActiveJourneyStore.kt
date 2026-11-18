package com.safesphere.app.feature.safewalk.domain

import java.time.Instant

/**
 * What the phone remembers about the active walk so it survives a restart (even offline) and so the reminder alarm
 * can find it after the process died. Ids and times only: no label, no location (N6, D22).
 *
 * @param localDueAt the reminder time in PHONE time (already converted from the server's)
 * @param pendingEnd the person ended the walk but the server has not confirmed it yet
 */
data class ActiveJourneyRecord(
    val userId: Long,
    val journeyId: Long,
    val checkInIntervalMinutes: Int,
    val startedAt: Instant,
    val localDueAt: Instant,
    val pendingEnd: JourneyStatus? = null
)

interface ActiveJourneyStore {
    suspend fun load(): ActiveJourneyRecord?
    suspend fun save(record: ActiveJourneyRecord)
    suspend fun clear()
}
