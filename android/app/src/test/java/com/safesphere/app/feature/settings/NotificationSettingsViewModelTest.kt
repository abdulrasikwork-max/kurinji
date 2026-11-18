package com.safesphere.app.feature.settings

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.push.PushSwitches
import com.safesphere.app.feature.settings.ui.NotificationSettingsState
import com.safesphere.app.feature.settings.ui.NotificationSettingsViewModel
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** N7 (D31): Settings → Notifications. */
class NotificationSettingsViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private class Local : NotificationSettingsViewModel.LocalChoices {
        var show = false
        var quiet = true
        override suspend fun showContent() = show
        override suspend fun quietAtNight() = quiet
        override suspend fun setShowContent(value: Boolean) { show = value }
        override suspend fun setQuietAtNight(value: Boolean) { quiet = value }
    }

    private var server = PushSwitches()
    private var failSave = false

    private fun viewModel(local: Local = Local(), configured: Boolean = true, staff: Boolean = false) =
        NotificationSettingsViewModel(
            load = { ApiResult.Success(server) },
            save = { s -> if (failSave) ApiResult.Failure(ApiError.NO_CONNECTION) else ApiResult.Success(s.also { server = it }) },
            isStaff = { staff },
            local = local,
            configured = configured
        )

    @Test
    fun `loads the server switches and the phone choices and staff see their switch`() = runTest {
        val vm = viewModel(staff = true)
        vm.start()
        advanceUntilIdle()
        assertEquals(PushSwitches(), vm.state.value.switches)
        assertTrue(vm.state.value.staff)
        assertTrue(vm.state.value.quietAtNight)
        assertFalse(vm.state.value.showContent)
    }

    @Test
    fun `a switch is saved on the server, and a failed save puts it back`() = runTest {
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        vm.change { it.copy(replies = false) }
        advanceUntilIdle()
        assertFalse(server.replies)
        assertFalse(vm.state.value.switches!!.replies)

        failSave = true
        vm.change { it.copy(plans = false) }
        advanceUntilIdle()
        assertTrue("put back", vm.state.value.switches!!.plans)
        assertEquals(NotificationSettingsState.Problem.SAVE, vm.state.value.problem)
    }

    @Test
    fun `the phone choices stay on the phone`() = runTest {
        val local = Local()
        val vm = viewModel(local)
        vm.start()
        advanceUntilIdle()
        vm.setShowContent(true)
        vm.setQuietAtNight(false)
        advanceUntilIdle()
        assertTrue(local.show)
        assertFalse(local.quiet)
        assertEquals(PushSwitches(), server)
    }

    @Test
    fun `a build without push says so and never calls the server`() = runTest {
        val vm = viewModel(configured = false)
        vm.start()
        advanceUntilIdle()
        assertNull(vm.state.value.switches)
        assertFalse(vm.state.value.configured)
    }
}
