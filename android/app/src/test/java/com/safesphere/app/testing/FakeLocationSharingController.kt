package com.safesphere.app.testing

import com.safesphere.app.feature.emergency.location.LocationSharingController
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** Records what the emergency screen asks of the location sharing. */
class FakeLocationSharingController : LocationSharingController {

    override val state = MutableStateFlow(LocationSharingState())

    val started = mutableListOf<Long>()
    var stopCalls = 0
        private set
    var refreshCalls = 0
        private set

    override fun start(emergencyId: Long, startedAt: Instant) {
        started += emergencyId
        state.value = LocationSharingState(emergencyId = emergencyId, status = LocationSharingStatus.SEARCHING)
    }

    override fun stop() {
        stopCalls++
        state.value = LocationSharingState()
    }

    override fun refresh() {
        refreshCalls++
    }
}
