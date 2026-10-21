package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.network.ApiResult

/** The person's own Safe Walks on the server. Nothing here contacts anyone. */
interface JourneyRepository {
    /** [destination]: coordinates found for the label (Phase 4C), or null. */
    suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int, destination: GeoPoint? = null): ApiResult<Journey>
    suspend fun getHistory(): ApiResult<List<Journey>>
    suspend fun checkIn(id: Long): ApiResult<Journey>
    suspend fun complete(id: Long): ApiResult<Journey>
    suspend fun cancel(id: Long): ApiResult<Journey>
    /**
     * Reports the level the phone's policy reached (1 or 2). Re-sending a level already reached is harmless.
     * [concern] explains a level 1 raised before the deadline (Phase 4C); null means "check-in missed".
     */
    suspend fun escalate(id: Long, level: EscalationLevel, concern: ConcernKind? = null): ApiResult<Journey>
    /** Replaces the walk's last known location on the server (no track is kept). */
    suspend fun updateLocation(id: Long, fix: LocationFix): ApiResult<Unit>
}
