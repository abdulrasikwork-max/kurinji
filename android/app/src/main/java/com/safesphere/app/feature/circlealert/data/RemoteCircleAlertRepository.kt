package com.safesphere.app.feature.circlealert.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.circlealert.data.remote.CircleAlertApi
import com.safesphere.app.feature.circlealert.data.remote.OwnerContextDto
import com.safesphere.app.feature.circlealert.data.remote.ReportBatchDto
import com.safesphere.app.feature.circlealert.data.remote.ReportDto
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleAlertRepository
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.ContextAlert
import com.safesphere.app.feature.circlealert.domain.ContextEscalation
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.domain.EmergencyContext
import java.time.Instant

class RemoteCircleAlertRepository(
    private val api: CircleAlertApi,
    private val handler: ApiCallHandler
) : CircleAlertRepository {

    override suspend fun report(target: AlertTarget, deliveries: List<CircleDelivery>): ApiResult<Unit> {
        // SENDING is a phone-only state (no result yet): it is never reported as if it were sent.
        val final = deliveries.filter { it.status != DeliveryStatus.SENDING }
        if (final.isEmpty()) return ApiResult.Success(Unit)
        val body = ReportBatchDto(final.map { it.toDto() })
        return handler.execute {
            when (target) {
                is AlertTarget.Emergency -> api.reportForEmergency(target.id, body)
                is AlertTarget.Walk -> api.reportForJourney(target.id, body)
            }
            Unit
        }
    }

    override suspend fun recordDialPrompt(emergencyId: Long): ApiResult<Unit> =
        handler.execute { api.dialPrompt(emergencyId) }

    override suspend fun ownerContext(emergencyId: Long): ApiResult<EmergencyContext> =
        handler.execute { api.ownerContext(emergencyId).toDomain() }

    private fun CircleDelivery.toDto() = ReportDto(
        clientRef = clientRef,
        contactId = contactId,
        kind = kind.name,
        channel = channel.name,
        status = status.name,
        occurredAt = at.toString()
    )

    private fun OwnerContextDto.toDomain() = EmergencyContext(
        emergencyId = emergency.id,
        status = emergency.status,
        startedAt = Instant.parse(emergency.startedAt),
        endedAt = emergency.endedAt?.let(Instant::parse),
        walkDestinationLabel = journey?.destinationLabel,
        latestLocationLink = latestLocation?.mapLink,
        latestLocationAt = latestLocation?.recordedAt?.let(Instant::parse),
        locationCount = locationCount,
        evidenceItems = evidence?.items ?: 0,
        evidenceSealed = evidence?.sealed ?: 0,
        highestLevel = highestLevel,
        escalations = escalations.orEmpty().map {
            ContextEscalation(it.level, it.reason, Instant.parse(it.at), it.source == "SAFE_WALK")
        },
        alerts = circleAlerts.orEmpty().map { alert ->
            ContextAlert(
                kind = CircleMessageKind.entries.firstOrNull { it.name == alert.kind },
                channel = AlertChannel.entries.firstOrNull { it.name == alert.channel },
                status = DeliveryStatus.entries.firstOrNull { it.name == alert.status },
                contactName = alert.contactName,
                at = Instant.parse(alert.occurredAt),
                fromWalk = alert.source == "SAFE_WALK"
            )
        }
    )
}
