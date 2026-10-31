package com.safesphere.app.feature.social.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.ExperienceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 17C: one video on the player screen. Before the platform player opens the address, the first byte is asked for
 * through the shared client (a lapsed token is renewed on the way; "not found" and "no connection" get our own words).
 * It remembers where playback was across a rotation and whether to carry on: playback starts only because the person
 * tapped the video, and coming back to the app later never starts it again by itself.
 */
class VideoPlayerViewModel(private val repository: ExperienceRepository, val url: String) : ViewModel() {

    sealed interface State {
        data object Checking : State
        data object Ready : State
        data class Problem(@StringRes val message: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Where playback was (ms). */
    var positionMs: Int = 0
        private set

    /** True on the first open (the tap) and across a rotation while it was playing; false after leaving the app. */
    var playWhenReady: Boolean = true
        private set

    init {
        check()
    }

    fun onTryAgain() = check()

    fun onPlaybackError() {
        _state.value = State.Problem(R.string.video_cannot_play)
    }

    /** The screen went away: [keepPlaying] only when it comes straight back (a rotation) while it was playing. */
    fun onLeft(positionMs: Int, keepPlaying: Boolean) {
        if (positionMs > 0) this.positionMs = positionMs
        playWhenReady = keepPlaying
    }

    fun onFinished() {
        positionMs = 0
        playWhenReady = false
    }

    private fun check() {
        _state.value = State.Checking
        viewModelScope.launch {
            _state.value = when (val result = repository.checkVideo(url)) {
                is ApiResult.Success -> State.Ready
                is ApiResult.Failure -> State.Problem(when (result.error) {
                    ApiError.NOT_FOUND, ApiError.FORBIDDEN -> R.string.video_not_found
                    ApiError.NO_CONNECTION -> R.string.video_offline
                    else -> R.string.video_cannot_play
                })
            }
        }
    }
}
