package com.safesphere.app.feature.circlealert.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/** The server side of Phase 4D: it records, it never sends. Production: RemoteCircleAlertRepository. */
interface CircleAlertRepository {
    /** Reports attempts for one target (1–20). Re-sending is harmless; a status only moves forward. */
    suspend fun report(target: AlertTarget, deliveries: List<CircleDelivery>): ApiResult<Unit>

    /** Level 4: the person tapped "Call 112" during this emergency. */
    suspend fun recordDialPrompt(emergencyId: Long): ApiResult<Unit>

    /** The Emergency Context Packet, OWNER projection. */
    suspend fun ownerContext(emergencyId: Long): ApiResult<EmergencyContext>
}

/** What the emergency details screen shows (OWNER projection, mapped). */
data class EmergencyContext(
    val emergencyId: Long,
    val status: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val walkDestinationLabel: String?,
    val latestLocationLink: String?,
    val latestLocationAt: Instant?,
    val locationCount: Long,
    val highestLevel: Int,
    val escalations: List<ContextEscalation>,
    val alerts: List<ContextAlert>,
    /** Phase 5: how many evidence items the vault holds for this emergency (sealed = verified by the server). */
    val evidenceItems: Long = 0,
    val evidenceSealed: Long = 0
) {
    override fun toString(): String = "EmergencyContext(id=$emergencyId, status=$status, location=<redacted>)"
}

data class ContextEscalation(val level: Int, val reason: String, val at: Instant, val fromWalk: Boolean)

data class ContextAlert(
    val kind: CircleMessageKind?,
    val channel: AlertChannel?,
    val status: DeliveryStatus?,
    val contactName: String?,
    val at: Instant,
    val fromWalk: Boolean
)
