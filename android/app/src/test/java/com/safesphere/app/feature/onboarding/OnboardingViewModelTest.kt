package com.safesphere.app.feature.onboarding

import com.safesphere.app.feature.onboarding.ui.OnboardingViewModel
import com.safesphere.app.testing.FakeOnboardingRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `completing onboarding saves the flag and signals the screen to move on`() = runTest {
        val repository = FakeOnboardingRepository()
        val viewModel = OnboardingViewModel(repository)
        assertFalse(viewModel.isFinished.value)

        viewModel.completeOnboarding()
        advanceUntilIdle()

        assertEquals(1, repository.markCompletedCallCount)
        assertTrue(viewModel.isFinished.value)
    }
}
