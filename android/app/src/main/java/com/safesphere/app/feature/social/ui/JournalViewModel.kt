package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.data.MediaPick
import com.safesphere.app.feature.social.data.MediaSource
import com.safesphere.app.feature.social.domain.DailyState
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Phase 17E: what the profile photo is doing. */
enum class PhotoStatus { IDLE, PREPARING, UPLOADING, FAILED, UNREADABLE }

data class JournalUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    /** Null after loading = no profile yet (or the age is not confirmed yet): You offers "Make your profile". */
    val profile: OwnProfile? = null,
    val photo: PhotoStatus = PhotoStatus.IDLE,
    /** Phase 17E (D-7): a change of today's state is on its way. */
    val stateBusy: Boolean = false,
    /** One-shot: today's state was set (the screen says who sees it, once). */
    val stateSaved: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13F: the head of the You tab (name, handle, city, bio, interests; never a count). Phase 17E: the profile photo
 * (picked, made square on the phone, uploaded; removable) and today's state (D-7: connections only, ends at midnight
 * IST, nobody notified). Loads the person's own profile each time You opens; nothing is stored on the phone.
 */
class JournalViewModel(
    private val repository: SocialRepository,
    private val media: MediaSource
) : ViewModel() {

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
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    /** A photo from the picker: made square on the phone, then uploaded; the profile shows the new key. */
    fun onPhotoPicked(pick: MediaPick) {
        val status = _uiState.value.photo
        if (status == PhotoStatus.PREPARING || status == PhotoStatus.UPLOADING || _uiState.value.profile == null) return
        _uiState.update { it.copy(photo = PhotoStatus.PREPARING, failure = null) }
        viewModelScope.launch {
            val jpeg = media.prepareAvatar(pick)
            if (jpeg == null) {
                _uiState.update { it.copy(photo = PhotoStatus.UNREADABLE) }
                return@launch
            }
            _uiState.update { it.copy(photo = PhotoStatus.UPLOADING) }
            when (val result = repository.uploadProfilePhoto(jpeg)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(photo = PhotoStatus.IDLE, profile = it.profile?.copy(photo = result.data))
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(photo = PhotoStatus.FAILED) }
                    if (result.isSessionExpired()) fail(result)
                }
            }
        }
    }

    fun onRemovePhoto() {
        if (_uiState.value.photo == PhotoStatus.UPLOADING) return
        viewModelScope.launch {
            when (val result = repository.removeProfilePhoto()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(photo = PhotoStatus.IDLE, profile = it.profile?.copy(photo = null))
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    /** Phase 17E (D-7): sets today's state ([state] = null clears it). */
    fun onDailyState(state: DailyState?) {
        if (_uiState.value.stateBusy || _uiState.value.profile == null) return
        _uiState.update { it.copy(stateBusy = true, failure = null) }
        viewModelScope.launch {
            val result = if (state == null) repository.clearDailyState().map { null }
                else repository.setDailyState(state).map { it }
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(stateBusy = false, stateSaved = state != null,
                        profile = it.profile?.copy(dailyState = result.data))
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(stateBusy = false) }
                    fail(result)
                }
            }
        }
    }

    fun onStateSavedShown() = _uiState.update { it.copy(stateSaved = false) }
    fun onPhotoNoticeShown() = _uiState.update {
        if (it.photo == PhotoStatus.FAILED || it.photo == PhotoStatus.UNREADABLE) it.copy(photo = PhotoStatus.IDLE) else it
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isLoading = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { JournalViewModel(container.socialRepository, container.mediaSource) }
        }
    }
}

private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}
