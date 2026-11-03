package com.safesphere.app.feature.community

import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.ui.RuntimePermissionAsk
import com.safesphere.app.feature.community.ui.AreaState
import com.safesphere.app.feature.community.ui.CommunityViewModel
import com.safesphere.app.testing.FakeCommunityOutbox
import com.safesphere.app.testing.FakeCommunityRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.ScriptedLocationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 17H, found on the Galaxy A23 (Track B): after Android stopped showing its location dialog, Nearby kept an
 * "Allow location" button that did nothing, and the "Open settings" wording never appeared. Cause: the second refusal
 * left the view model's state EQUAL to the one before, a StateFlow does not emit an equal value, and the fragment's
 * own plain field was never read again. The same pass found a notification button whose label stayed "Allow" while its
 * action had turned into "open settings".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase17HPermissionDenialTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCommunityRepository()
    private val outbox = FakeCommunityOutbox()
    private val location = ScriptedLocationProvider()

    private fun viewModel() = CommunityViewModel(repository, outbox, location) { 1_000_000_000L }

    // ---- Nearby: the refusal is part of the state ---------------------------------------------------------------------

    @Test
    fun `a permanent refusal CHANGES the state even though the area stays needs-permission`() = runTest {
        location.access = LocationAccess.NONE
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        val before = vm.ui.value
        assertEquals(AreaState.NeedsPermission, before.area)
        assertFalse(before.permissionBlocked)

        vm.onPermissionResult(permanentlyDenied = true)
        advanceUntilIdle()
        val after = vm.ui.value

        assertEquals("the area is the same as before", AreaState.NeedsPermission, after.area)
        assertTrue(after.permissionBlocked)
        assertNotEquals("a StateFlow only emits a DIFFERENT value, so the screen can redraw", before, after)
    }

    @Test
    fun `a first refusal that can still be asked again is not shown as blocked`() = runTest {
        location.access = LocationAccess.NONE
        val vm = viewModel()
        vm.onScreenVisible()
        vm.onPermissionResult(permanentlyDenied = false)
        advanceUntilIdle()
        assertEquals(AreaState.NeedsPermission, vm.ui.value.area)
        assertFalse(vm.ui.value.permissionBlocked)
    }

    @Test
    fun `leaving and coming back keeps the blocked state while there is still no permission`() = runTest {
        location.access = LocationAccess.NONE
        val vm = viewModel()
        vm.onPermissionResult(permanentlyDenied = true)
        advanceUntilIdle()
        vm.onScreenVisible() // onStart again, e.g. back from another screen
        advanceUntilIdle()
        assertTrue(vm.ui.value.permissionBlocked)
        assertEquals(AreaState.NeedsPermission, vm.ui.value.area)
    }

    @Test
    fun `allowing location in Settings clears the blocked state and loads the area`() = runTest {
        location.access = LocationAccess.NONE
        val vm = viewModel()
        vm.onPermissionResult(permanentlyDenied = true)
        advanceUntilIdle()
        assertTrue(vm.ui.value.permissionBlocked)

        location.access = LocationAccess.PRECISE // the person allowed it in Settings and came back
        vm.onScreenVisible()
        advanceUntilIdle()

        assertFalse(vm.ui.value.permissionBlocked)
        assertTrue("the area is read again", vm.ui.value.area is AreaState.Ready)
    }

    @Test
    fun `a granted answer from the dialog is never shown as blocked`() = runTest {
        location.access = LocationAccess.PRECISE
        val vm = viewModel()
        vm.onPermissionResult(permanentlyDenied = false)
        advanceUntilIdle()
        assertFalse(vm.ui.value.permissionBlocked)
        assertTrue(vm.ui.value.area is AreaState.Ready)
    }

    // ---- the shared rule for the other runtime permissions ------------------------------------------------------------

    @Test
    fun `settings are offered only after this screen asked and Android will not ask again`() {
        assertFalse("never asked: ask", RuntimePermissionAsk.needsSettings(asked = false, shouldShowRationale = false))
        assertFalse("never asked, rationale shown: ask", RuntimePermissionAsk.needsSettings(asked = false, shouldShowRationale = true))
        assertFalse("asked once, Android would ask again: ask", RuntimePermissionAsk.needsSettings(asked = true, shouldShowRationale = true))
        assertTrue("asked, no rationale: the dialog will not come back", RuntimePermissionAsk.needsSettings(asked = true, shouldShowRationale = false))
    }

    // ---- source guards: label and action come from the same place -----------------------------------------------------

    private fun source(path: String) = File("src/main/java/com/safesphere/app/$path").readText(Charsets.UTF_8)

    @Test
    fun `Nearby draws the blocked wording from the state, not from a field of the fragment`() {
        val fragment = source("feature/community/ui/CommunityFragment.kt")
        assertTrue(fragment.contains("renderArea(ui.area, ui.permissionBlocked)"))
        assertTrue(fragment.contains("onPermissionResult(permanentlyDenied = status == LocationPermissionStatus.PERMANENTLY_DENIED)"))
        val renderArea = fragment.substringAfter("private fun renderArea(").substringBefore("is AreaState.LocationProblem")
        assertFalse("renderArea must not read the fragment field", renderArea.contains("locationAsk.permanentlyDenied"))
    }

    @Test
    fun `the SOS options buttons take label AND action from the same rule`() {
        val fragment = source("feature/safety/ui/SosOptionsFragment.kt")
        val uses = Regex("(?<!fun )(microphone|notifications)NeedsSettings\\(\\)").findAll(fragment).count()
        assertEquals("microphone: label + click; notifications: label + click", 4, uses)
        assertTrue(fragment.contains("RuntimePermissionAsk.needsSettings"))
        assertFalse("no second, hand-written copy of the rule",
            Regex("asked\\w+ && !shouldShowRequestPermissionRationale").containsMatchIn(fragment))
        assertTrue("the notification button is relabelled",
            fragment.contains("shakeNotificationsButton.setText("))
    }
}
