package com.safesphere.app.testing

import com.safesphere.app.feature.emergency.location.LocationSharingController
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.feature.emergency.session.EmergencyRebinder
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** Records what the emergency screen asks of the location sharing (and, Phase 11, every id move). */
class FakeLocationSharingController : LocationSharingController, EmergencyRebinder {

    override val state = MutableStateFlow(LocationSharingState())

    val started = mutableListOf<Long>()
    val rebinds = mutableListOf<Pair<Long, Long>>()
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

    override fun rebindEmergency(localId: Long, serverId: Long) {
        rebinds += localId to serverId
        if (state.value.emergencyId == localId) state.value = state.value.copy(emergencyId = serverId)
    }
}
