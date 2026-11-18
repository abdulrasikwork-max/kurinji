package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyRecord
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.session.ReminderScheduler
import com.safesphere.app.feature.safewalk.session.SafeWalkAlerts
import com.safesphere.app.feature.safewalk.session.SafeWalkController
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class FakeActiveJourneyStore(var record: ActiveJourneyRecord? = null) : ActiveJourneyStore {
    override suspend fun load(): ActiveJourneyRecord? = record
    override suspend fun save(record: ActiveJourneyRecord) {
        this.record = record
    }
    override suspend fun clear() {
        record = null
    }
}

/** Remembers which reminder notification is showing (null = none) and every one shown. */
class FakeSafeWalkAlerts : SafeWalkAlerts {
    var showing: CheckInPhase? = null
        private set
    val shown = mutableListOf<CheckInPhase>()

    override fun show(phase: CheckInPhase) {
        showing = phase
        shown += phase
    }

    override fun clear() {
        showing = null
    }
}

/** N6: the alarms that would wake the phone (null = none scheduled). */
class FakeReminderScheduler : ReminderScheduler {
    var scheduled: Triple<Long, Instant, Instant>? = null
        private set
    var cancels = 0
        private set

    override fun schedule(journeyId: Long, reminderAt: Instant, nudgeAt: Instant) {
        scheduled = Triple(journeyId, reminderAt, nudgeAt)
    }

    override fun cancel() {
        scheduled = null
        cancels++
    }
}

/** For screen tests: the state is set directly; calls are recorded. */
class FakeSafeWalkController : SafeWalkController {
    override val state = MutableStateFlow(SafeWalkState())
    var startResult: ApiResult<Journey>? = null
    val calls = mutableListOf<String>()

    /** When set, [recover] waits for it (a slow server look-up). */
    var recoverGate: CompletableDeferred<Unit>? = null

    override suspend fun start(label: String?, minutes: Int): ApiResult<Journey> {
        calls += "start:$minutes:${label ?: "-"}"
        return startResult ?: error("set startResult")
    }

    override suspend fun recover() {
        calls += "recover"
        recoverGate?.await()
    }

    override fun checkIn() {
        calls += "checkIn"
    }

    override fun complete() {
        calls += "complete"
    }

    override fun cancel() {
        calls += "cancel"
    }

    override fun onEmergencyStarted() {
        calls += "emergencyStarted"
    }

    override fun acknowledgeEnded() {
        calls += "acknowledge"
    }
}
