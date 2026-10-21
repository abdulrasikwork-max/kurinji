package com.safesphere.app.feature.profile

import com.safesphere.app.feature.profile.ui.ProfileViewModel
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.FakeModerationRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `logout clears the session and only then reports logged out`() = runTest {
        val repository = FakeAuthRepository(FakeAuthRepository.DEFAULT_USER)
        val viewModel = ProfileViewModel(repository, FakeModerationRepository())
        assertFalse(viewModel.isLoggedOut.value)

        viewModel.onLogoutConfirmed()
        advanceUntilIdle()

        assertEquals(1, repository.logoutCalls)
        assertTrue(viewModel.isLoggedOut.value)
    }
}
