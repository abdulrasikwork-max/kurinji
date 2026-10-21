package com.safesphere.app.testing

import com.safesphere.app.core.location.DestinationLookup
import com.safesphere.app.core.location.DestinationResolver
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyRecord
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.session.SafeWalkAlerts
import com.safesphere.app.feature.safewalk.session.SafeWalkController
import com.safesphere.app.feature.safewalk.session.SafeWalkState
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

/** Remembers which check-in notification is showing (null = none), and the battery warning. */
class FakeSafeWalkAlerts : SafeWalkAlerts {
    var showing: CheckInPhase? = null
        private set
    var showingConcern: ConcernKind? = null
        private set
    val shown = mutableListOf<CheckInPhase>()
    var batteryWarningShowing = false
        private set
    var batteryWarnings = 0
        private set

    override fun show(status: CheckInStatus) {
        showing = status.phase
        showingConcern = status.concern
        shown += status.phase
    }

    override fun clear() {
        showing = null
        showingConcern = null
    }

    override fun showBatteryCritical() {
        batteryWarningShowing = true
        batteryWarnings++
    }

    override fun clearBatteryCritical() {
        batteryWarningShowing = false
    }
}

/** Answers every lookup with [result] and remembers what was asked (and with which nearby point). */
class FakeDestinationResolver(var result: DestinationLookup = DestinationLookup.NotFound) : DestinationResolver {
    val queries = mutableListOf<Pair<String, GeoPoint?>>()

    override suspend fun resolve(query: String, near: GeoPoint?): DestinationLookup {
        queries += query to near
        return result
    }
}

/** For screen tests: the state is set directly; calls are recorded. */
class FakeSafeWalkController : SafeWalkController {
    override val state = MutableStateFlow(SafeWalkState())
    var startResult: ApiResult<Journey>? = null
    val calls = mutableListOf<String>()

    /** When set, [recover] waits for it (a slow server look-up). */
    var recoverGate: CompletableDeferred<Unit>? = null

    override suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int, movementChecks: Boolean): ApiResult<Journey> {
        calls += "start:$checkInIntervalMinutes:$movementChecks"
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

    override fun refreshLocationAccess() {
        calls += "refresh"
    }

    override fun setMovementChecks(enabled: Boolean) {
        calls += "movementChecks:$enabled"
    }

    override fun onEmergencyStarted() {
        calls += "emergencyStarted"
    }

    override fun acknowledgeEnded() {
        calls += "acknowledge"
    }
}
