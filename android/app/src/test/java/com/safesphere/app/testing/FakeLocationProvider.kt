package com.safesphere.app.testing

import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationSource
import com.safesphere.app.core.location.LocationUpdate
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart

/** Location provider driven by the test: set [access]/[locationEnabled], then [emit] updates. */
class FakeLocationProvider(
    var access: LocationAccess = LocationAccess.PRECISE,
    var locationEnabled: Boolean = true
) : LocationProvider {

    private val updates = MutableSharedFlow<LocationUpdate>(extraBufferCapacity = 64)

    /** How many times someone started collecting updates. */
    var subscriptions = 0
        private set

    override fun access(): LocationAccess = access

    override fun isLocationEnabled(): Boolean = locationEnabled

    /** The interval of the most recent request. */
    var lastIntervalMillis: Long? = null
        private set

    override fun updates(intervalMillis: Long): Flow<LocationUpdate> = updates.onStart {
        subscriptions++
        lastIntervalMillis = intervalMillis
    }

    fun emit(update: LocationUpdate) {
        check(updates.tryEmit(update)) { "update buffer full" }
    }

    fun emitFix(fix: LocationFix) = emit(LocationUpdate.Fix(fix))

    companion object {
        fun fix(
            latitude: Double = 12.9716,
            longitude: Double = 77.5946,
            accuracy: Float? = 8f,
            at: Instant = Instant.parse("2026-10-15T10:00:10Z")
        ) = LocationFix(latitude, longitude, accuracy, LocationSource.FUSED, at)
    }
}
