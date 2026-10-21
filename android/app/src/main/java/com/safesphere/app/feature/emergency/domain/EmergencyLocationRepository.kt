package com.safesphere.app.feature.emergency.domain

import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.network.ApiResult

/** A fix plus the battery level at that moment, ready to be stored with an emergency. */
data class EmergencyLocationPoint(val fix: LocationFix, val batteryPercent: Int?) {
    override fun toString(): String = "EmergencyLocationPoint(fix=$fix, batteryPercent=$batteryPercent)"
}

/** [stored] can be smaller than [received]: points the server already had are skipped (safe re-send). */
data class LocationUploadResult(val received: Int, val stored: Int)

/**
 * Saves location points to the person's OWN emergency record on the server. This does not send the
 * location to anyone (Safety Circle messaging is Phase 4D).
 */
interface EmergencyLocationRepository {
    /** 1..[MAX_BATCH_SIZE] points; all-or-nothing on the server. */
    suspend fun upload(emergencyId: Long, points: List<EmergencyLocationPoint>): ApiResult<LocationUploadResult>

    companion object {
        const val MAX_BATCH_SIZE = 50
    }
}
