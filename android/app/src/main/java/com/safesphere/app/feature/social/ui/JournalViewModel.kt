package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.core.network.needsMembership
import com.safesphere.app.feature.social.data.MediaPick
import com.safesphere.app.feature.social.data.MediaSource
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentRepository
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
    /** N4: the person's own moments that have not ended, and the ones they keep (never a count). */
    val current: List<Moment> = emptyList(),
    val kept: List<Moment> = emptyList(),
    /** False until the moments were read once (a non-member has none: the lists stay hidden). */
    val momentsLoaded: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * The head of the You tab (real name, handle, programme and year, bio; never a count) and, since N4, the person's own
 * live and kept moments. Phase 17E: the profile photo
 * (picked, made square on the phone, uploaded; removable). Loads the person's own profile each time You opens; nothing is stored on the phone.
 */
class JournalViewModel(
    private val repository: SocialRepository,
    private val media: MediaSource,
    private val moments: MomentRepository
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
        // The moments are their own request: a person without a membership has a profile draft but no moments (403),
        // and that is not an error of this screen (the membership card already says what to do).
        viewModelScope.launch {
            when (val result = moments.mine()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(current = result.data.current, kept = result.data.kept, momentsLoaded = true)
                }
                is ApiResult.Failure -> {
                    // Not a member (any more): nothing of theirs is on Today. Any other failure keeps what is shown.
                    if (result.needsMembership()) _uiState.update {
                        it.copy(current = emptyList(), kept = emptyList(), momentsLoaded = false)
                    }
                    if (result.isSessionExpired()) fail(result)
                }
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

    fun onPhotoNoticeShown() = _uiState.update {
        if (it.photo == PhotoStatus.FAILED || it.photo == PhotoStatus.UNREADABLE) it.copy(photo = PhotoStatus.IDLE) else it
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isLoading = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { JournalViewModel(container.socialRepository, container.mediaSource, container.momentRepository) }
        }
    }
}
