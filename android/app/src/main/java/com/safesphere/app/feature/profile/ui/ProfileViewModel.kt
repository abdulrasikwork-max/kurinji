package com.safesphere.app.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.moderation.domain.ModerationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ProfileUiState(val displayName: String?, val email: String?)

class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val moderationRepository: ModerationRepository
) : ViewModel() {

    val uiState: Flow<ProfileUiState> =
        authRepository.currentUser.map { ProfileUiState(it?.name, it?.email) }

    private val _isLoggedOut = MutableStateFlow(false)

    /** Turns true only after the saved session has really been cleared. */
    val isLoggedOut: StateFlow<Boolean> = _isLoggedOut.asStateFlow()

    private val _canModerate = MutableStateFlow(false)

    /**
     * Phase 7: whether to show the Moderation entry. Asked from the server each time Profile opens (a role removed
     * meanwhile hides it again). Only convenience: the moderation API refuses non-moderators on every call anyway.
     * Any failure (offline, error) hides the entry.
     */
    val canModerate: StateFlow<Boolean> = _canModerate.asStateFlow()

    fun refreshAccess() {
        viewModelScope.launch {
            val result = moderationRepository.access()
            _canModerate.value = result is ApiResult.Success && result.data.canModerate
        }
    }

    /**
     * The screen waits for [isLoggedOut] before navigating away. Navigating first would destroy this
     * ViewModel and could cancel the clean-up half way.
     */
    fun onLogoutConfirmed() {
        viewModelScope.launch {
            authRepository.logout()
            _isLoggedOut.value = true
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ProfileViewModel(container.authRepository, container.moderationRepository) }
        }
    }
}
