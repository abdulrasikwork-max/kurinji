package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.sms.SmsAvailability
import com.safesphere.app.core.sms.SmsResult
import com.safesphere.app.core.sms.SmsSender
import com.safesphere.app.feature.circlealert.domain.AlertTarget
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Records every message "sent"; [accept] = false makes send() fail at once; results are emitted by the test. */
class FakeSmsSender(var availability: SmsAvailability = SmsAvailability.AVAILABLE) : SmsSender {
    data class Sent(val requestId: String, val phoneNumber: String, val text: String)

    val sent = mutableListOf<Sent>()
    var accept = true
    override val results = MutableSharedFlow<SmsResult>(extraBufferCapacity = 64)

    override fun availability(): SmsAvailability = availability

    override fun send(requestId: String, phoneNumber: String, text: String): Boolean {
        if (!accept || availability != SmsAvailability.AVAILABLE) return false
        sent += Sent(requestId, phoneNumber, text)
        return true
    }

    fun emit(result: SmsResult) = check(results.tryEmit(result))
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

    override fun setAutoSms(enabled: Boolean) {
        calls += "auto:$enabled"
        state.value = state.value.copy(autoSms = enabled)
    }

    override fun refreshAvailability() {
        calls += "refresh"
    }

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
