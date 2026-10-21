package com.safesphere.app.testing

import com.safesphere.app.feature.onboarding.domain.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory test double; keeps ViewModel tests free of Android and DataStore. */
class FakeOnboardingRepository(completed: Boolean = false) : OnboardingRepository {

    private val completedFlow = MutableStateFlow(completed)
    var markCompletedCallCount = 0
        private set

    override val isOnboardingCompleted: Flow<Boolean> = completedFlow

    override suspend fun markOnboardingCompleted() {
        markCompletedCallCount++
        completedFlow.value = true
    }
}
