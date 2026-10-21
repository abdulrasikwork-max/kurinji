package com.safesphere.app.feature.splash

import com.safesphere.app.feature.splash.ui.SplashViewModel
import com.safesphere.app.feature.splash.ui.StartDestination
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.FakeOnboardingRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SplashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(onboardingCompleted: Boolean, signedIn: Boolean) = SplashViewModel(
        FakeOnboardingRepository(completed = onboardingCompleted),
        FakeAuthRepository(signedInUser = if (signedIn) FakeAuthRepository.DEFAULT_USER else null)
    )

    @Test
    fun `first launch goes to onboarding after the minimum splash time`() = runTest {
        val viewModel = viewModel(onboardingCompleted = false, signedIn = false)

        advanceTimeBy(SplashViewModel.SPLASH_MINIMUM_DURATION_MS - 1)
        assertNull(viewModel.destination.value)

        advanceUntilIdle()
        assertEquals(StartDestination.Onboarding, viewModel.destination.value)
    }

    @Test
    fun `signed-out launches after onboarding go to login`() = runTest {
        val viewModel = viewModel(onboardingCompleted = true, signedIn = false)

        advanceUntilIdle()

        assertEquals(StartDestination.Login, viewModel.destination.value)
    }

    @Test
    fun `a saved session skips login and opens home`() = runTest {
        val viewModel = viewModel(onboardingCompleted = true, signedIn = true)

        advanceUntilIdle()

        assertEquals(StartDestination.Home, viewModel.destination.value)
    }
}
