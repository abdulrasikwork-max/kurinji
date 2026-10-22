package com.safesphere.app.feature.emergency

import com.safesphere.app.R
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.emergency.location.EmergencyLocationSharing
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.testing.FakeBatteryMonitor
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEmergencyLocationRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.FakeLocationProvider.Companion.fix
import com.safesphere.app.testing.FakeSessionStorage
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4A: the shared holder that collects, queues and uploads emergency locations. Virtual time only. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyLocationSharingTest {

    private val startedAt = Instant.parse("2026-10-15T10:00:00Z")
    private val provider = FakeLocationProvider()
    private val repository = FakeEmergencyLocationRepository()
    private val battery = FakeBatteryMonitor(percent = 42)
    private val connectivity = FakeConnectivityMonitor(online = true)
    private val session = FakeSessionStorage(AuthSession("token", AuthUser(7, "Asha", "asha@example.com")))

    private fun TestScope.sharing() = EmergencyLocationSharing(
        scope = backgroundScope,
        locationProvider = provider,
        repository = repository,
        batteryMonitor = battery,
        connectivityMonitor = connectivity,
        session = session.session,
        now = { startedAt.plusMillis(testScheduler.currentTime) }
    )

    private fun TestScope.started(): EmergencyLocationSharing {
        val sharing = sharing()
        runCurrent()
        sharing.start(EMERGENCY_ID, startedAt)
        runCurrent()
        return sharing
    }

    @Test
    fun `is OFF and asks for no notification before any emergency`() = runTest {
        val sharing = sharing()
        runCurrent()

        assertEquals(LocationSharingStatus.OFF, sharing.state.value.status)
        assertFalse(sharing.state.value.isTracking)
        assertNull(sharing.notice.value)
    }

    @Test
    fun `a fix is saved to the emergency with the battery level`() = runTest {
        val sharing = started()

        provider.emitFix(fix())
        runCurrent()

        val (emergencyId, points) = repository.uploads.single()
        assertEquals(EMERGENCY_ID, emergencyId)
        assertEquals(42, points.single().batteryPercent)
        val state = sharing.state.value
        assertEquals(LocationSharingStatus.AVAILABLE, state.status)
        assertEquals(0, state.pendingCount)
        assertNotNull(state.lastSavedAt)
        assertTrue(state.isTracking)
        assertEquals(R.string.tracking_notification_saving, sharing.notice.value?.text)
    }

    @Test
    fun `low or unknown battery level is reported as-is and never blocks saving`() = runTest {
        battery.percent = 3
        started()
        provider.emitFix(fix(at = startedAt.plusSeconds(10)))
        runCurrent()
        battery.percent = null
        provider.emitFix(fix(at = startedAt.plusSeconds(80)))
        runCurrent()

        assertEquals(listOf(3, null), repository.uploadedPoints.map { it.batteryPercent })
    }

    @Test
    fun `location permission denied - nothing is collected, uploaded or tracked`() = runTest {
        provider.access = LocationAccess.NONE
        val sharing = started()

        assertEquals(LocationSharingStatus.PERMISSION_NEEDED, sharing.state.value.status)
        assertEquals(0, provider.subscriptions)
        assertTrue(repository.uploads.isEmpty())
        assertFalse(sharing.state.value.isTracking)
        assertNull(sharing.notice.value) // so the foreground service is never started without permission
    }

    @Test
    fun `granting the permission later and refreshing starts collecting`() = runTest {
        provider.access = LocationAccess.NONE
        val sharing = started()

        provider.access = LocationAccess.APPROXIMATE
        sharing.refresh()
        assertEquals(LocationSharingStatus.SEARCHING, sharing.state.value.status) // synchronous, before the service starts
        runCurrent()
        provider.emitFix(fix(accuracy = 900f))
        runCurrent()

        assertEquals(1, provider.subscriptions)
        assertTrue(sharing.state.value.isApproximate)
        assertEquals(1, repository.uploads.size)
    }

    @Test
    fun `location switched off on the phone is reported, and refresh notices it was turned back on`() = runTest {
        val sharing = started()
        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED))
        runCurrent()

        assertEquals(LocationSharingStatus.LOCATION_DISABLED, sharing.state.value.status)
        assertTrue(sharing.state.value.isTracking) // the request keeps running and resumes by itself
        assertEquals(R.string.tracking_notification_location_off, sharing.notice.value?.text)

        provider.locationEnabled = true
        sharing.refresh()
        assertEquals(LocationSharingStatus.SEARCHING, sharing.state.value.status)
    }

    @Test
    fun `location temporarily unavailable keeps trying, and missing Play services is reported`() = runTest {
        val sharing = started()

        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX))
        runCurrent()
        assertEquals(LocationSharingStatus.UNAVAILABLE, sharing.state.value.status)
        assertTrue(sharing.state.value.isTracking)

        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.SERVICES_UNAVAILABLE))
        runCurrent()
        assertEquals(LocationSharingStatus.SERVICES_UNAVAILABLE, sharing.state.value.status)
        assertFalse(sharing.state.value.isTracking)
    }

    @Test
    fun `network unavailable - points are kept and uploaded together when the connection returns`() = runTest {
        connectivity.isOnline.value = false
        repository.defaultResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val sharing = started()

        provider.emitFix(fix(at = startedAt.plusSeconds(10)))
        runCurrent()
        provider.emitFix(fix(at = startedAt.plusSeconds(75)))
        runCurrent()
        assertEquals(2, sharing.state.value.pendingCount)
        assertFalse(sharing.state.value.isOnline)
        assertEquals(R.string.tracking_notification_offline, sharing.notice.value?.text)

        repository.defaultResult = null
        connectivity.isOnline.value = true
        runCurrent()

        assertEquals(0, sharing.state.value.pendingCount)
        assertEquals(2, repository.uploads.last().second.size) // one batch
        assertEquals(2, repository.uploadedPoints.size)
    }

    @Test
    fun `server problems are retried with backoff`() = runTest {
        repository.nextResults += ApiResult.Failure(ApiError.SERVER)
        repository.nextResults += ApiResult.Failure(ApiError.SERVER)
        val sharing = started()

        provider.emitFix(fix())
        runCurrent()
        assertEquals(1, repository.uploads.size)

        advanceTimeBy(EmergencyLocationSharing.INITIAL_RETRY_MILLIS + 1)
        runCurrent()
        assertEquals(2, repository.uploads.size)

        advanceTimeBy(EmergencyLocationSharing.INITIAL_RETRY_MILLIS * 2 + 1) // doubled
        runCurrent()
        assertEquals(3, repository.uploads.size)
        assertEquals(0, sharing.state.value.pendingCount)
    }

    @Test
    fun `points the server rejects as invalid are dropped and counted, not retried forever`() = runTest {
        repository.nextResults += ApiResult.Failure(ApiError.VALIDATION)
        val sharing = started()

        provider.emitFix(fix())
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(1, repository.uploads.size)
        assertEquals(1, sharing.state.value.rejectedCount)
        assertEquals(0, sharing.state.value.pendingCount)
    }

    @Test
    fun `when the server stops accepting points, uploads stop and its message is kept`() = runTest {
        repository.nextResults += ApiResult.Failure(ApiError.CONFLICT, "Location limit reached for this emergency")
        val sharing = started()

        provider.emitFix(fix(at = startedAt.plusSeconds(10)))
        runCurrent()
        provider.emitFix(fix(at = startedAt.plusSeconds(90)))
        runCurrent()

        assertEquals(1, repository.uploads.size)
        assertTrue(sharing.state.value.uploadsStopped)
        assertEquals("Location limit reached for this emergency", sharing.state.value.uploadsStoppedMessage)
    }

    @Test
    fun `C1 - sign in to sync keeps collecting, keeps the points, and sends them once a new token arrives`() = runTest {
        repository.nextResults += ApiResult.Failure(ApiError.UNAUTHORIZED) // the refresh was refused
        val sharing = started()
        provider.emitFix(fix())
        runCurrent()
        provider.emitFix(fix(at = startedAt.plusSeconds(90)))
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(1, repository.uploads.size) // asked once, then waited for a sign-in (no request per point)
        assertFalse(sharing.state.value.signedOut)
        assertTrue(sharing.state.value.isTracking)
        assertEquals(2, sharing.state.value.pendingCount)

        // Signing in again (or a refresh) saves a new token: everything waiting goes out.
        session.save(AuthSession("new-token", AuthUser(7, "Asha", "asha@example.com")))
        runCurrent()
        assertEquals(2, repository.uploads.size)
        assertEquals(2, repository.uploads.last().second.size)
        assertEquals(0, sharing.state.value.pendingCount)
    }

    @Test
    fun `C1 - another account signing in on this phone stops the previous account's sharing`() = runTest {
        val sharing = started()
        session.save(AuthSession("other", AuthUser(8, "Bina", "bina@example.com")))
        runCurrent()
        assertTrue(sharing.state.value.signedOut)
        assertFalse(sharing.state.value.isTracking)
    }

    @Test
    fun `a real sign-out stops sharing at once`() = runTest {
        val sharing = started()

        session.clear()
        runCurrent()
        provider.emitFix(fix())
        runCurrent()

        assertTrue(sharing.state.value.signedOut)
        assertFalse(sharing.state.value.isTracking)
        assertNull(sharing.notice.value)
        assertTrue(repository.uploads.isEmpty())
    }

    @Test
    fun `stop makes one last upload attempt of pending points and turns OFF`() = runTest {
        connectivity.isOnline.value = false
        repository.nextResults += ApiResult.Failure(ApiError.NO_CONNECTION)
        val sharing = started()
        provider.emitFix(fix())
        runCurrent()
        assertEquals(1, sharing.state.value.pendingCount)

        sharing.stop()
        runCurrent()

        assertEquals(LocationSharingStatus.OFF, sharing.state.value.status)
        assertNull(sharing.notice.value) // the foreground service stops itself
        assertEquals(2, repository.uploads.size) // the failed one, then the final attempt
        provider.emitFix(fix(at = startedAt.plusSeconds(300)))
        runCurrent()
        assertEquals(2, repository.uploads.size) // nothing is collected after stop
    }

    @Test
    fun `starting again for the same emergency does not restart anything`() = runTest {
        val sharing = started()

        sharing.start(EMERGENCY_ID, startedAt)
        runCurrent()

        assertEquals(1, provider.subscriptions)
    }

    @Test
    fun `an old cached fix is shown but not saved to the emergency`() = runTest {
        val sharing = started()

        provider.emitFix(fix(at = startedAt.minusSeconds(3_600)))
        runCurrent()

        assertNotNull(sharing.state.value.lastFix)
        assertTrue(repository.uploads.isEmpty())
    }

    @Test
    fun `while offline only the newest points are kept in memory`() = runTest {
        connectivity.isOnline.value = false
        repository.defaultResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val sharing = started()

        repeat(EmergencyLocationSharing.MAX_PENDING + 20) { minute ->
            provider.emitFix(fix(at = startedAt.plusSeconds(60L * (minute + 1))))
            runCurrent()
        }

        assertEquals(EmergencyLocationSharing.MAX_PENDING, sharing.state.value.pendingCount)
    }

    // ---- Phase 11: SOS without internet ----------------------------------------------------------

    @Test
    fun `points of an SOS the server does not have yet are collected, and sent to its id once it has it`() = runTest {
        val sharing = sharing()
        runCurrent()
        sharing.start(LOCAL_ID, startedAt)
        runCurrent()
        provider.emitFix(fix(at = startedAt.plusSeconds(5)))
        runCurrent()
        assertTrue(repository.uploads.isEmpty()) // nothing is sent under a local id
        assertEquals(1, sharing.state.value.pendingCount)

        sharing.rebindEmergency(LOCAL_ID, EMERGENCY_ID)
        runCurrent()
        assertEquals(EMERGENCY_ID, repository.uploads.single().first)
        assertEquals(EMERGENCY_ID, sharing.state.value.emergencyId)
        assertEquals(0, sharing.state.value.pendingCount)
    }

    @Test
    fun `points of an SOS that ended before the server had it are kept in memory and sent once it has it`() = runTest {
        val sharing = sharing()
        runCurrent()
        sharing.start(LOCAL_ID, startedAt)
        runCurrent()
        provider.emitFix(fix(at = startedAt.plusSeconds(5)))
        runCurrent()
        sharing.stop()
        runCurrent()
        assertTrue(repository.uploads.isEmpty())
        assertNull(sharing.notice.value) // the service stops: the SOS ended

        sharing.rebindEmergency(LOCAL_ID, EMERGENCY_ID)
        runCurrent()
        assertEquals(EMERGENCY_ID, repository.uploads.single().first)

        // A sign-out forgets kept points (they belong to the account).
        sharing.start(LOCAL_ID - 1, startedAt)
        runCurrent()
        provider.emitFix(fix(at = startedAt.plusSeconds(9)))
        runCurrent()
        sharing.stop()
        session.clear()
        runCurrent()
        sharing.rebindEmergency(LOCAL_ID - 1, EMERGENCY_ID + 1)
        runCurrent()
        assertEquals(1, repository.uploads.size)
    }

    private companion object {
        const val LOCAL_ID = -77L
        const val EMERGENCY_ID = 10L
    }
}
