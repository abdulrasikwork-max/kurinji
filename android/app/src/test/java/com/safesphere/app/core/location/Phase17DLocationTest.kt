package com.safesphere.app.core.location

import com.safesphere.app.feature.safewalk.session.SafeWalkState
import com.safesphere.app.feature.safewalk.ui.SetupLocation
import com.safesphere.app.feature.safewalk.ui.setupLocationFor
import com.safesphere.app.feature.safewalk.ui.walkLocationNeed
import com.safesphere.app.testing.FakeLocationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17D: the shared pieces of the location UX — the best-fix search, the one-action policy, the stream status —
 * and a source scan that keeps the location code on the shared path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase17DLocationTest {

    private fun fix(accuracy: Float?) = FakeLocationProvider.fix(accuracy = accuracy)

    // ---- preciseFix ------------------------------------------------------------------------------------------------

    @Test
    fun `a first rough position does not end the search, a good one does, and the progress is the best so far`() = runTest {
        val provider = FakeLocationProvider()
        val progress = mutableListOf<Float?>()
        val result = async { provider.preciseFix(onProgress = { progress += it }) }
        runCurrent()
        provider.emitFix(fix(400f))
        runCurrent()
        provider.emitFix(fix(900f)) // worse: not progress
        runCurrent()
        provider.emitFix(fix(60f))
        advanceUntilIdle()
        assertEquals(PreciseFix.Good(fix(60f)), result.await())
        assertEquals(listOf<Float?>(400f, 60f), progress)
    }

    @Test
    fun `the target itself counts as good, one metre more does not`() = runTest {
        val provider = FakeLocationProvider()
        val a = async { provider.preciseFix(targetMeters = 100f) }
        runCurrent()
        provider.emitFix(fix(100f))
        advanceUntilIdle()
        assertTrue(a.await() is PreciseFix.Good)

        val b = async { provider.preciseFix(targetMeters = 100f, windowMillis = 5_000) }
        runCurrent()
        provider.emitFix(fix(101f))
        advanceUntilIdle()
        assertEquals(PreciseFix.Rough(fix(101f)), b.await())
    }

    @Test
    fun `a position that stayed rougher than the target comes back as rough with the best one, never as good`() = runTest {
        val provider = FakeLocationProvider()
        val result = async { provider.preciseFix(windowMillis = 10_000) }
        runCurrent()
        provider.emitFix(fix(300f))
        runCurrent()
        provider.emitFix(fix(180f))
        runCurrent()
        provider.emitFix(fix(250f))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(PreciseFix.Rough(fix(180f)), result.await())
    }

    @Test
    fun `a position that does not state its accuracy is never good`() = runTest {
        val provider = FakeLocationProvider()
        val result = async { provider.preciseFix(windowMillis = 5_000) }
        runCurrent()
        provider.emitFix(fix(null))
        runCurrent()
        provider.emitFix(fix(Float.NaN))
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(result.await() is PreciseFix.Rough)
    }

    @Test
    fun `nothing in the window is not found`() = runTest {
        val provider = FakeLocationProvider()
        val result = async { provider.preciseFix(windowMillis = 3_000) }
        runCurrent()
        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)) // routine while GPS warms up: keeps waiting
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(PreciseFix.NotFound, result.await())
    }

    @Test
    fun `permission, approximate-only and location off are known at once, without asking for any position`() = runTest {
        val none = FakeLocationProvider(access = LocationAccess.NONE)
        assertEquals(PreciseFix.NeedsPermission, none.preciseFix())
        val approximate = FakeLocationProvider(access = LocationAccess.APPROXIMATE)
        assertEquals(PreciseFix.NeedsPrecisePermission, approximate.preciseFix())
        val off = FakeLocationProvider(locationEnabled = false)
        assertEquals(PreciseFix.LocationOff, off.preciseFix())
        assertEquals(0, none.subscriptions + approximate.subscriptions + off.subscriptions)
    }

    @Test
    fun `a lasting problem during the search ends it with that problem, even after a rough position`() = runTest {
        val provider = FakeLocationProvider()
        val off = async { provider.preciseFix() }
        runCurrent()
        provider.emitFix(fix(300f))
        runCurrent()
        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED))
        advanceUntilIdle()
        assertEquals(PreciseFix.LocationOff, off.await())

        val revoked = async { provider.preciseFix() }
        runCurrent()
        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED))
        advanceUntilIdle()
        assertEquals(PreciseFix.NeedsPermission, revoked.await())

        val services = async { provider.preciseFix() }
        runCurrent()
        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.SERVICES_UNAVAILABLE))
        advanceUntilIdle()
        assertEquals(PreciseFix.Unavailable, services.await())
    }

    @Test
    fun `cancelling the caller stops listening`() = runTest {
        val provider = FakeLocationProvider()
        val job = async { provider.preciseFix() }
        runCurrent()
        job.cancel()
        runCurrent()
        // A later position finds nobody listening: it must not throw and there is nothing to deliver.
        provider.emitFix(fix(5f))
        runCurrent()
        assertTrue(job.isCancelled)
    }

    // ---- LocationNeeds ---------------------------------------------------------------------------------------------

    @Test
    fun `the one action follows the facts, and an upgrade that came back approximate goes to settings`() {
        fun need(access: LocationAccess, on: Boolean = true, precise: Boolean = false, denied: Boolean = false, declined: Boolean = false) =
            LocationNeeds.of(access, on, precise, denied, declined)
        assertEquals(LocationNeed.ALLOW, need(LocationAccess.NONE))
        assertEquals(LocationNeed.OPEN_APP_SETTINGS, need(LocationAccess.NONE, denied = true))
        assertEquals(LocationNeed.TURN_ON, need(LocationAccess.PRECISE, on = false))
        assertEquals(LocationNeed.NONE, need(LocationAccess.PRECISE))
        assertEquals("approximate is enough to look", LocationNeed.NONE, need(LocationAccess.APPROXIMATE))
        assertEquals(LocationNeed.UPGRADE_PRECISE, need(LocationAccess.APPROXIMATE, precise = true))
        assertEquals(LocationNeed.UPGRADE_PRECISE_IN_SETTINGS, need(LocationAccess.APPROXIMATE, precise = true, declined = true))
        // Location off outranks the upgrade: nothing works until the switch is on.
        assertEquals(LocationNeed.TURN_ON, need(LocationAccess.APPROXIMATE, on = false, precise = true))
        // No permission at all outranks everything, even with the switch off.
        assertEquals(LocationNeed.ALLOW, need(LocationAccess.NONE, on = false, precise = true))
    }

    // ---- LocationStreamPolicy --------------------------------------------------------------------------------------

    @Test
    fun `a stream says nothing about a routine no-fix, reports lasting problems at once and silence after the grace`() {
        val s = LocationStreamStatus.SEARCHING
        assertNull(LocationStreamPolicy.after(s, LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)))
        assertEquals(LocationStreamStatus.AVAILABLE, LocationStreamPolicy.after(s, LocationUpdate.Fix(fix(10f))))
        assertEquals(LocationStreamStatus.LOCATION_OFF,
            LocationStreamPolicy.after(s, LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED)))
        assertEquals(LocationStreamStatus.SEARCHING,
            LocationStreamPolicy.after(LocationStreamStatus.LOCATION_OFF, LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)))
        assertEquals(LocationStreamStatus.NO_SIGNAL, LocationStreamPolicy.afterSilence(LocationStreamStatus.SEARCHING))
        assertEquals(LocationStreamStatus.NO_SIGNAL, LocationStreamPolicy.afterSilence(LocationStreamStatus.AVAILABLE))
        assertEquals(LocationStreamStatus.LOCATION_OFF, LocationStreamPolicy.afterSilence(LocationStreamStatus.LOCATION_OFF))
        assertEquals(LocationStreamStatus.NEEDS_PERMISSION, LocationStreamPolicy.afterSilence(LocationStreamStatus.NEEDS_PERMISSION))
        assertEquals(LocationStreamStatus.NEEDS_PERMISSION, LocationStreamPolicy.initial(LocationAccess.NONE))
        assertEquals(LocationStreamStatus.SEARCHING, LocationStreamPolicy.initial(LocationAccess.APPROXIMATE))
    }

    @Test
    fun `the silence that counts grows with a slow location rate, so a battery-saving walk is not reported`() {
        assertEquals(60_000L, LocationStreamPolicy.graceMillis(10_000))
        assertEquals(90_000L, LocationStreamPolicy.graceMillis(30_000))
        assertEquals(180_000L, LocationStreamPolicy.graceMillis(60_000))
    }

    // ---- Safe Walk set-up and during-walk actions ------------------------------------------------------------------

    @Test
    fun `the set-up line states facts only`() {
        assertEquals(SetupLocation.NOT_ALLOWED, setupLocationFor(LocationAccess.NONE, true))
        assertEquals(SetupLocation.NOT_ALLOWED, setupLocationFor(LocationAccess.NONE, false))
        assertEquals(SetupLocation.OFF, setupLocationFor(LocationAccess.PRECISE, false))
        assertEquals(SetupLocation.APPROXIMATE, setupLocationFor(LocationAccess.APPROXIMATE, true))
        assertEquals(SetupLocation.PRECISE, setupLocationFor(LocationAccess.PRECISE, true))
    }

    @Test
    fun `during a walk the one action is the switch, the permission or the upgrade, and only when route checks want precise`() {
        fun walk(access: LocationAccess, status: LocationStreamStatus, checks: Boolean) =
            SafeWalkState(access = access, locationStatus = status, movementChecks = checks)
        assertEquals(LocationNeed.TURN_ON, walkLocationNeed(walk(LocationAccess.PRECISE, LocationStreamStatus.LOCATION_OFF, true)))
        assertEquals(LocationNeed.ALLOW, walkLocationNeed(walk(LocationAccess.NONE, LocationStreamStatus.NEEDS_PERMISSION, false)))
        assertEquals(LocationNeed.UPGRADE_PRECISE, walkLocationNeed(walk(LocationAccess.APPROXIMATE, LocationStreamStatus.AVAILABLE, true)))
        assertEquals("approximate is fine when route checks are off", LocationNeed.NONE,
            walkLocationNeed(walk(LocationAccess.APPROXIMATE, LocationStreamStatus.AVAILABLE, false)))
        assertEquals(LocationNeed.NONE, walkLocationNeed(walk(LocationAccess.PRECISE, LocationStreamStatus.NO_SIGNAL, true)))
    }

    // ---- source scan -----------------------------------------------------------------------------------------------

    @Test
    fun `screens ask for location only through the shared LocationAsk, except the SOS screen`() {
        val root = File("src/main/java/com/safesphere/app")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("RequestMultiplePermissions") }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filterNot { it == "core/ui/LocationHelp.kt" || it == "feature/emergency/ui/EmergencyFragment.kt" }
            .toList()
        assertTrue("own location launchers in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no screen reads a position outside the shared helpers or the continuous holders`() {
        // One-shot readers must use oneFix / preciseFix (visible states, bounded waits). Streams belong to the holders
        // of the SOS and the Safe Walk and to the provider itself.
        val root = File("src/main/java/com/safesphere/app")
        val allowed = setOf(
            "core/location/FusedLocationProvider.kt", "core/location/LocationProvider.kt", "core/location/OneFix.kt",
            "core/location/PreciseFix.kt", "feature/emergency/location/EmergencyLocationSharing.kt",
            "feature/safewalk/session/SafeWalkSession.kt"
        )
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("\\.(updates|freshUpdates)\\(").containsMatchIn(it.readText(Charsets.UTF_8)) }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filterNot { it in allowed }
            .toList()
        assertTrue("direct location streams in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the location switch outranks searching, available and no signal, and back on starts searching again`() {
        for (s in listOf(LocationStreamStatus.SEARCHING, LocationStreamStatus.AVAILABLE, LocationStreamStatus.NO_SIGNAL)) {
            assertEquals(LocationStreamStatus.LOCATION_OFF, LocationStreamPolicy.afterSwitch(s, false))
            assertEquals(s, LocationStreamPolicy.afterSwitch(s, true))
        }
        assertEquals(LocationStreamStatus.SEARCHING, LocationStreamPolicy.afterSwitch(LocationStreamStatus.LOCATION_OFF, true))
        assertEquals(LocationStreamStatus.NEEDS_PERMISSION, LocationStreamPolicy.afterSwitch(LocationStreamStatus.NEEDS_PERMISSION, false))
        assertEquals(LocationStreamStatus.SERVICES_UNAVAILABLE, LocationStreamPolicy.afterSwitch(LocationStreamStatus.SERVICES_UNAVAILABLE, false))
    }
}
