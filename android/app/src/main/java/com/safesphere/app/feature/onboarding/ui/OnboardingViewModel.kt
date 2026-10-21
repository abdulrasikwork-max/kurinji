package com.safesphere.app.feature.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.feature.onboarding.domain.OnboardingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val onboardingRepository: OnboardingRepository
) : ViewModel() {

    private val _isFinished = MutableStateFlow(false)

    /** Becomes true once the "completed" flag has been saved; the screen then moves on. */
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    /** Called by both "Skip" and "Get started". */
    fun completeOnboarding() {
        viewModelScope.launch {
            onboardingRepository.markOnboardingCompleted()
            _isFinished.value = true
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { OnboardingViewModel(container.onboardingRepository) }
        }
    }
}
