package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JournalUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    /** Null after loading = no profile yet (or the age is not confirmed yet): You offers "Make your profile". */
    val profile: OwnProfile? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13F: the journal header of the You tab (name, handle, city, bio, interests; never a count). Loads the person's
 * own profile each time You opens; nothing is stored on the phone.
 */
class JournalViewModel(private val repository: SocialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.ownProfile()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, hasLoaded = true, profile = result.data)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { JournalViewModel(container.socialRepository) }
        }
    }
}
