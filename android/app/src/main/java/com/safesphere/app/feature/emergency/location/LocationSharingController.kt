package com.safesphere.app.feature.emergency.location

import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** What the emergency screen needs from the location sharing. Production: [EmergencyLocationSharing]. */
interface LocationSharingController {

    val state: StateFlow<LocationSharingState>

    /** Starts collecting and saving locations for this emergency. Calling it again for the same one is harmless. */
    fun start(emergencyId: Long, startedAt: Instant)

    /** The emergency ended: stops collecting, makes one last attempt to save what is still pending. */
    fun stop()

    /** Re-checks permission and the location setting (after a permission dialog or a trip to Settings). */
    fun refresh()
}
