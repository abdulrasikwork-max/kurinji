package com.safesphere.app.feature.splash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.onboarding.domain.OnboardingRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the app should go after the splash screen. */
sealed interface StartDestination {
    data object Onboarding : StartDestination
    data object Login : StartDestination
    data object Home : StartDestination
}

class SplashViewModel(
    private val onboardingRepository: OnboardingRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _destination = MutableStateFlow<StartDestination?>(null)

    /** Null while the splash is still showing. */
    val destination: StateFlow<StartDestination?> = _destination.asStateFlow()

    init {
        decideDestination()
    }

    private fun decideDestination() {
        viewModelScope.launch {
            // The branding stays visible for a short, fixed time even if the lookup is instant.
            val minimumDisplay = launch { delay(SPLASH_MINIMUM_DURATION_MS) }
            val onboardingCompleted = onboardingRepository.isOnboardingCompleted.first()
            // A saved session means the person is still signed in: skip the login screen.
            val signedIn = authRepository.currentUser.first() != null
            minimumDisplay.join()

            _destination.value = when {
                !onboardingCompleted -> StartDestination.Onboarding
                signedIn -> StartDestination.Home
                else -> StartDestination.Login
            }
        }
    }

    companion object {
        const val SPLASH_MINIMUM_DURATION_MS = 1_200L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SplashViewModel(container.onboardingRepository, container.authRepository) }
        }
    }
}
