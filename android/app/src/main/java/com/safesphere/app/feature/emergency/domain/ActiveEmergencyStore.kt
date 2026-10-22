package com.safesphere.app.feature.emergency.domain

import java.time.Instant

/**
 * The minimum needed to show an emergency again when the server cannot be reached, and (Phase 11) to record an SOS
 * that started while the phone was offline. No location, no text, no number.
 *
 * @param emergencyId   the server's id once [onServer]; before that a LOCAL id ([EmergencyIds.isLocal])
 * @param clientRef     the phone's random reference for this SOS (the server answers the same reference with the
 *                      same emergency); null for records written before Phase 11
 * @param onServer      the server has this SOS (its id is [emergencyId])
 * @param ended         the person ended it on this phone while the server could not be told yet (only in the
 *                      store's "unsent" list; the running record never has it)
 * @param dialPromptOpened "Call 112" was tapped before the server had the SOS (recorded once it has)
 */
data class ActiveEmergencyRecord(
    val userId: Long,
    val emergencyId: Long,
    val startedAt: Instant,
    val clientRef: String? = null,
    val onServer: Boolean = true,
    val ended: LocalEnd? = null,
    val dialPromptOpened: Boolean = false
) {
    /** Nothing left to tell the server about this SOS (it may still be running). */
    val inSync: Boolean get() = onServer && ended == null

    /** The SOS is still running on this phone. */
    val running: Boolean get() = ended == null

    override fun toString(): String =
        "ActiveEmergencyRecord(user=$userId, id=$emergencyId, onServer=$onServer, ended=${ended != null})"
}

/** How and when the person ended an SOS on this phone (the server is told later). */
data class LocalEnd(val resolved: Boolean, val at: Instant)

/** Phase 11: ids below zero belong to an SOS the server does not know yet; the server's ids are always positive. */
object EmergencyIds {
    fun isLocal(id: Long): Boolean = id < 0

    /** A local id derived from the reference (stable across a restart because it is stored with the record). */
    fun localIdFor(clientRef: String): Long = -1L - (java.util.UUID.fromString(clientRef).leastSignificantBits ushr 12)
}

/**
 * Remembers the emergency on the phone, so that reopening the app while offline still shows it (recovery does not
 * depend on memory or on the network alone). The server stays the source of truth: whenever it answers, its state
 * replaces this record. Since Phase 11 only [com.safesphere.app.feature.emergency.session.SosRecorder] writes it.
 */
interface ActiveEmergencyStore {
    /** The SOS running on this phone (null = none). */
    suspend fun load(): ActiveEmergencyRecord?
    suspend fun save(record: ActiveEmergencyRecord)

    /** Forgets the running SOS. The unsent endings are kept (they still have to reach the server). */
    suspend fun clear()

    /** Phase 11: SOS that ended on this phone before the server was told (oldest first). */
    suspend fun loadUnsent(): List<ActiveEmergencyRecord>
    suspend fun saveUnsent(records: List<ActiveEmergencyRecord>)

    /** Sign-out: everything above. */
    suspend fun clearAll()
}
