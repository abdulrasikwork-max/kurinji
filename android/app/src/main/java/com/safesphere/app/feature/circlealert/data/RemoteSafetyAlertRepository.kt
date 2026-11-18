package com.safesphere.app.feature.circlealert.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.circlealert.data.remote.AlertChoiceDto
import com.safesphere.app.feature.circlealert.data.remote.AlertSettingsDto
import com.safesphere.app.feature.circlealert.data.remote.EmergencyAlertsDto
import com.safesphere.app.feature.circlealert.data.remote.SafetyAlertApi
import com.safesphere.app.feature.circlealert.domain.AlertChoice
import com.safesphere.app.feature.circlealert.domain.AlertSettings
import com.safesphere.app.feature.circlealert.domain.ContactAlertPlan
import com.safesphere.app.feature.circlealert.domain.EmergencyAlerts
import com.safesphere.app.feature.circlealert.domain.SafetyAlertRepository
import com.safesphere.app.feature.circlealert.domain.ServerAlert
import com.safesphere.app.feature.circlealert.domain.ServerAlertKind
import com.safesphere.app.feature.circlealert.domain.ServerAlertStatus
import com.safesphere.app.feature.circlealert.domain.SkipCause
import com.safesphere.app.feature.circlealert.domain.TestAlert
import java.time.Instant
import java.time.format.DateTimeParseException

/** N6: maps the server's answers fail-closed (unknown choice = UNSET, unknown status = "not confirmed"). */
class RemoteSafetyAlertRepository(
    private val api: SafetyAlertApi,
    private val handler: ApiCallHandler
) : SafetyAlertRepository {

    override suspend fun settings(): ApiResult<AlertSettings> = handler.execute { api.settings().toDomain() }

    override suspend fun choose(on: Boolean): ApiResult<AlertSettings> =
        handler.execute { api.choose(AlertChoiceDto(on)).toDomain() }

    override suspend fun test(): ApiResult<AlertSettings> = handler.execute { api.test().toDomain() }

    override suspend fun forEmergency(emergencyId: Long): ApiResult<EmergencyAlerts> =
        handler.execute { api.forEmergency(emergencyId).toDomain() }

    companion object {
        fun AlertSettingsDto.toDomain() = AlertSettings(
            choice = choiceOf(choice),
            preview = preview?.takeIf { it.isNotBlank() },
            contacts = contacts.orEmpty().mapNotNull { c ->
                val id = c.contactId ?: return@mapNotNull null
                // Fail-closed: "automatic" only when the server said so in so many words.
                ContactAlertPlan(id, c.name.orEmpty(), c.automatic == true,
                    if (c.automatic == true) null else SkipCause.of(c.reason) ?: SkipCause.OTHER)
            },
            lastTest = lastTest?.let { TestAlert(ServerAlertStatus.of(it.status), instantOf(it.at)) },
            testAvailable = testAvailable == true
        )

        fun EmergencyAlertsDto.toDomain() = EmergencyAlerts(
            choice = choiceOf(choice),
            alerts = alerts.orEmpty().map {
                ServerAlert(it.contactId, it.contactName, ServerAlertKind.of(it.kind), ServerAlertStatus.of(it.status),
                    SkipCause.of(it.reason), instantOf(it.at), it.late == true)
            }
        )

        fun choiceOf(raw: String?): AlertChoice = when (raw) {
            "ON" -> AlertChoice.ON
            "OFF" -> AlertChoice.OFF
            else -> AlertChoice.UNSET
        }

        private fun instantOf(raw: String?): Instant? = try {
            raw?.let(Instant::parse)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
