package com.safesphere.app.feature.emergency

import com.safesphere.app.R
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.data.UnsentEndingsCodec
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.domain.LocalEnd
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.feature.emergency.ui.EmergencyLocationUi
import com.safesphere.app.feature.emergency.ui.EmergencyUiState
import com.safesphere.app.feature.emergency.ui.EmergencyViewModel
import com.safesphere.app.feature.emergency.ui.LAST_KNOWN_AFTER_SECONDS
import com.safesphere.app.feature.emergency.ui.lastKnownOf
import com.safesphere.app.feature.emergency.ui.locationCardFor
import com.safesphere.app.testing.FakeEmergencyRepository
import com.safesphere.app.testing.FakeLocationProvider.Companion.fix
import com.safesphere.app.testing.FakeLocationSharingController
import com.safesphere.app.testing.FakeSafetyCircleRepository
import com.safesphere.app.testing.FakeActiveEmergencyStore
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.settle
import com.safesphere.app.testing.sosRecorderForTest
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 17F: the trigger travels with the SOS, and a position that is no longer new is called the last known one. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase17FEmergencyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val server = FakeEmergencyRepository()
    private val store = FakeActiveEmergencyStore()
    private val sharing = FakeLocationSharingController()

    // ---- the trigger travels with the SOS -----------------------------------------------------------------

    @Test
    fun `an SOS started by the shake gesture is recorded on the server as SHAKE, an ordinary one as MANUAL`() = runTest {
        val sos = sosRecorderForTest(server, store)
        runCurrent()
        sos.startLocal(7, EmergencyTrigger.SHAKE)
        settle()
        assertEquals(EmergencyTrigger.SHAKE, server.recordRequests.single().trigger)

        val second = FakeEmergencyRepository()
        val other = sosRecorderForTest(second, FakeActiveEmergencyStore())
        runCurrent()
        other.startLocal(7)
        settle()
        assertEquals(EmergencyTrigger.MANUAL, second.recordRequests.single().trigger)
    }

    @Test
    fun `the trigger is still there when the server could not be reached and the SOS is sent later`() = runTest {
        server.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val sos = sosRecorderForTest(server, store)
        runCurrent()
        sos.startLocal(7, EmergencyTrigger.SHAKE)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(server.recordRequests.size >= 2)
        assertTrue(server.recordRequests.all { it.trigger == EmergencyTrigger.SHAKE })
        assertEquals(EmergencyTrigger.SHAKE, store.record?.trigger)
    }

    @Test
    fun `the phone's storage format keeps the trigger, reads older lines as MANUAL, and stays digits and flags only`() {
        val ref = "00000000-0000-4000-8000-000000000044"
        val shake = ActiveEmergencyRecord(7, 30, Instant.ofEpochMilli(1_800_000_000_000), clientRef = ref, onServer = true,
            ended = LocalEnd(true, Instant.ofEpochMilli(1_800_000_100_000)), trigger = EmergencyTrigger.SHAKE)
        val line = UnsentEndingsCodec.encode(shake)
        assertEquals(shake, UnsentEndingsCodec.decode(line))
        assertTrue(line.matches(Regex("^[0-9a-f;\\-truefals]*$")))

        // A line written before 17F has eight fields: it is an ordinary (MANUAL) SOS.
        val legacy = line.substringBeforeLast(";")
        assertEquals(EmergencyTrigger.MANUAL, UnsentEndingsCodec.decode(legacy)?.trigger)
        // An unknown code never invents a trigger.
        assertEquals(EmergencyTrigger.MANUAL, UnsentEndingsCodec.decode(line.substringBeforeLast(";") + ";9")?.trigger)
    }

    @Test
    fun `the shake gesture opens the same cancellable countdown and the SOS it starts is recorded as SHAKE`() = runTest {
        val shakes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val viewModel = EmergencyViewModel(
            emergencyRepository = server,
            safetyCircleRepository = FakeSafetyCircleRepository(emptyList()),
            locationSharing = sharing,
            sos = sosRecorderForTest(server, store, 7L, rebinders = listOf(sharing)),
            currentUserId = { 7L },
            trackingServiceRunning = MutableStateFlow(false),
            shakeEvents = shakes
        )
        settle()
        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)

        shakes.tryEmit(Unit)
        runCurrent()
        assertEquals("a shake shows the normal countdown, nothing has started", EmergencyUiState.Countdown(5), viewModel.uiState.value)
        assertTrue(server.recordRequests.isEmpty())

        // ... which can be cancelled exactly like the button's countdown.
        viewModel.onCancelCountdownClicked()
        runCurrent()
        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        advanceTimeBy(10_000)
        assertTrue(server.recordRequests.isEmpty())

        shakes.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(5_100)
        settle()
        assertEquals(EmergencyTrigger.SHAKE, server.recordRequests.single().trigger)
    }

    @Test
    fun `a shake while a countdown or an SOS is already running changes nothing`() = runTest {
        val shakes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val viewModel = EmergencyViewModel(
            emergencyRepository = server,
            safetyCircleRepository = FakeSafetyCircleRepository(emptyList()),
            locationSharing = sharing,
            sos = sosRecorderForTest(server, store, 7L, rebinders = listOf(sharing)),
            currentUserId = { 7L },
            trackingServiceRunning = MutableStateFlow(false),
            shakeEvents = shakes
        )
        settle()
        viewModel.onStartSosClicked() // the button: MANUAL
        runCurrent()
        shakes.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(5_100)
        settle()
        assertEquals(EmergencyTrigger.MANUAL, server.recordRequests.single().trigger)
        shakes.tryEmit(Unit)
        settle()
        assertEquals(1, server.recordRequests.size)
    }

    // ---- last known position -------------------------------------------------------------------------------

    private val now = Instant.parse("2026-10-15T10:10:00Z")

    @Test
    fun `a position older than a minute is the last known one, with its age`() {
        val old = fix(at = now.minusSeconds(180))
        val lastKnown = lastKnownOf(old, now.toEpochMilli())
        assertNotNull(lastKnown)
        assertEquals(180L, lastKnown!!.ageSeconds)
        assertEquals(old.recordedAt, lastKnown.recordedAt)
    }

    @Test
    fun `a new position is not labelled, and an unknown clock invents no label`() {
        assertNull(lastKnownOf(fix(at = now.minusSeconds(LAST_KNOWN_AFTER_SECONDS - 1)), now.toEpochMilli()))
        assertNull(lastKnownOf(fix(at = now.minusSeconds(600)), nowMillis = null))
        assertNull(lastKnownOf(null, now.toEpochMilli()))
        // A phone clock that moved back never makes a negative age.
        assertEquals(0L, lastKnownOf(fix(at = now.plusSeconds(5)), now.toEpochMilli())?.ageSeconds ?: 0L)
    }

    private fun ui(status: LocationSharingStatus, fixAge: Long?, clock: Long? = now.toEpochMilli(), signedOut: Boolean = false) =
        EmergencyLocationUi(
            sharing = LocationSharingState(
                emergencyId = 1, status = status, access = LocationAccess.PRECISE, signedOut = signedOut,
                lastFix = fixAge?.let { fix(at = now.minusSeconds(it)) }
            ),
            backgroundTrackingActive = true,
            nowMillis = clock
        )

    @Test
    fun `the card marks an old position as last known whatever the status says`() {
        assertNotNull(locationCardFor(ui(LocationSharingStatus.UNAVAILABLE, fixAge = 200)).lastKnown)
        assertNotNull(locationCardFor(ui(LocationSharingStatus.LOCATION_DISABLED, fixAge = 900)).lastKnown)
        assertNotNull(locationCardFor(ui(LocationSharingStatus.AVAILABLE, fixAge = 120)).lastKnown)
    }

    @Test
    fun `the card does not mark a fresh position, and shows no position at all when signed out or without a fix`() {
        assertNull(locationCardFor(ui(LocationSharingStatus.AVAILABLE, fixAge = 5)).lastKnown)
        assertNull(locationCardFor(ui(LocationSharingStatus.UNAVAILABLE, fixAge = null)).lastKnown)
        assertNull(locationCardFor(ui(LocationSharingStatus.UNAVAILABLE, fixAge = 500, signedOut = true)).lastKnown)
        // The wording of the status itself is unchanged (R3 of the 17 plan).
        assertEquals(R.string.location_card_unavailable, locationCardFor(ui(LocationSharingStatus.UNAVAILABLE, fixAge = 200)).message)
    }
}
