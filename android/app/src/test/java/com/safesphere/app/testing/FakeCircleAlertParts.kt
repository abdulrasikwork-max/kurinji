package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.circlealert.domain.AlertChoice
import com.safesphere.app.feature.circlealert.domain.AlertSettings
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.EmergencyAlerts
import com.safesphere.app.feature.circlealert.domain.SafetyAlertRepository
import com.safesphere.app.feature.circlealert.domain.ServerAlertStatus
import com.safesphere.app.feature.circlealert.domain.TestAlert
import com.safesphere.app.feature.circlealert.domain.CircleAlertRepository
import com.safesphere.app.feature.circlealert.domain.CircleAlertSnapshot
import com.safesphere.app.feature.circlealert.domain.CircleAlertStore
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.domain.EmergencyContext
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertState
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** N6: a tiny "server" for the automatic-text settings: the choice, a test once a day, an SOS's alerts. */
class FakeSafetyAlertRepository : SafetyAlertRepository {
    val calls = mutableListOf<String>()
    val emergencyCalls = mutableListOf<Long>()
    var offline = false
    var choice = AlertChoice.UNSET
    var lastTest: TestAlert? = null
    var emergency = EmergencyAlerts(AlertChoice.UNSET, emptyList())

    private fun settingsNow() = AlertSettings(
        choice = choice,
        preview = "IRL SOS: Asha pressed SOS at 9:41 PM. Location: maps.google.com/?q=(where you are) Call 9000000000 now.",
        contacts = emptyList(),
        lastTest = lastTest,
        testAvailable = lastTest == null
    )

    override suspend fun settings(): ApiResult<AlertSettings> {
        calls += "settings"
        return if (offline) ApiResult.Failure(ApiError.NO_CONNECTION) else ApiResult.Success(settingsNow())
    }

    override suspend fun choose(on: Boolean): ApiResult<AlertSettings> {
        calls += "choose:$on"
        if (offline) return ApiResult.Failure(ApiError.NO_CONNECTION)
        choice = if (on) AlertChoice.ON else AlertChoice.OFF
        return ApiResult.Success(settingsNow())
    }

    override suspend fun test(): ApiResult<AlertSettings> {
        calls += "test"
        if (offline) return ApiResult.Failure(ApiError.NO_CONNECTION)
        if (lastTest != null) return ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "once a day")
        lastTest = TestAlert(ServerAlertStatus.QUEUED, Instant.parse("2026-11-18T10:00:00Z"))
        return ApiResult.Success(settingsNow())
    }

    override suspend fun forEmergency(emergencyId: Long): ApiResult<EmergencyAlerts> {
        emergencyCalls += emergencyId
        return if (offline) ApiResult.Failure(ApiError.NO_CONNECTION) else ApiResult.Success(emergency)
    }
}

class FakeCircleAlertStore(var snapshot: CircleAlertSnapshot? = null) : CircleAlertStore {
    var saves = 0
    override suspend fun load(): CircleAlertSnapshot? = snapshot
    override suspend fun save(snapshot: CircleAlertSnapshot) {
        this.snapshot = snapshot
        saves++
    }
    override suspend fun clear() {
        snapshot = null
    }
}

/** A tiny "server" log: stores reports by reference (status only moves forward), like the backend. */
class FakeCircleAlertRepository : CircleAlertRepository {
    val reports = linkedMapOf<String, CircleDelivery>()
    val calls = mutableListOf<String>()
    var offline = false
    /** Phase 11 C1: the server needs a sign-in ("sign in to sync"). */
    var unauthorized = false
    var context: ApiResult<EmergencyContext> = ApiResult.Failure(ApiError.NOT_FOUND)

    override suspend fun report(target: AlertTarget, deliveries: List<CircleDelivery>): ApiResult<Unit> {
        calls += "report:${deliveries.size}"
        if (offline) return ApiResult.Failure(ApiError.NO_CONNECTION)
        if (unauthorized) return ApiResult.Failure(ApiError.UNAUTHORIZED)
        deliveries.forEach { reports[it.clientRef] = it }
        return ApiResult.Success(Unit)
    }

    override suspend fun recordDialPrompt(emergencyId: Long): ApiResult<Unit> {
        calls += "dial:$emergencyId"
        return if (offline) ApiResult.Failure(ApiError.NO_CONNECTION) else ApiResult.Success(Unit)
    }

    override suspend fun ownerContext(emergencyId: Long): ApiResult<EmergencyContext> {
        calls += "context:$emergencyId"
        return context
    }
}

/** For ViewModel tests: state set directly, calls recorded. */
class FakeCircleAlertController : CircleAlertController {
    override val state = MutableStateFlow(CircleAlertState(loaded = true))
    val calls = mutableListOf<String>()

    override fun updateRecipients(contacts: List<SafetyContact>) {
        calls += "recipients:${contacts.size}"
    }

    override fun onEmergencyActive(emergencyId: Long, startedAt: Instant) {
        calls += "active:$emergencyId"
    }

    override fun onEmergencyEnded(emergencyId: Long, resolved: Boolean) {
        calls += "ended:$emergencyId:$resolved"
    }

    override fun composerText(target: AlertTarget, kind: CircleMessageKind): String = "text:${kind.name}"

    override fun onComposerOpened(target: AlertTarget, kind: CircleMessageKind, recipient: CircleRecipient) {
        calls += "composer:${recipient.contactId}"
    }

    override fun onDialPromptOpened(emergencyId: Long) {
        calls += "dial:$emergencyId"
    }
}
