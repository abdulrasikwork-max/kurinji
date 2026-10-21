package com.safesphere.app.feature.emergency.domain

import java.time.Instant

/** The minimum needed to show an active emergency again when the server cannot be reached. No location. */
data class ActiveEmergencyRecord(val userId: Long, val emergencyId: Long, val startedAt: Instant)

/**
 * Remembers the ACTIVE emergency on the phone, so that reopening the app while offline still shows it
 * (recovery does not depend on memory or on the network alone). The server stays the source of truth:
 * whenever it answers, its state replaces this record.
 */
interface ActiveEmergencyStore {
    suspend fun load(): ActiveEmergencyRecord?
    suspend fun save(record: ActiveEmergencyRecord)
    suspend fun clear()
}
