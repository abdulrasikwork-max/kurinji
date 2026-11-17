package com.safesphere.app.feature.replies.ui

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.AppContainer
import com.safesphere.app.R
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.replies.domain.PrivateReplies
import com.safesphere.app.feature.replies.domain.RepliesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PrivateRepliesUiState(
    /** Null until read from the server (the choice is never guessed on the phone). */
    val value: PrivateReplies? = null,
    val busy: Boolean = false,
    /** One-shot: the person asked to choose; the dialog opens once the value is known. */
    val choose: Boolean = false,
    /** One-shot: the new choice was saved. */
    val saved: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/** Phase N5 (D17): who may reply privately to the person's moments: My people (default), Anyone at MIET, Nobody. */
class PrivateRepliesViewModel(private val repository: RepliesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivateRepliesUiState())
    val uiState: StateFlow<PrivateRepliesUiState> = _uiState.asStateFlow()

    /** The row was tapped: read the current choice (again), then the dialog opens. */
    fun onChoose() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.privateReplies()) {
                is ApiResult.Success -> _uiState.update { it.copy(value = result.data, busy = false, choose = true) }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onChosen(value: PrivateReplies) {
        if (_uiState.value.busy || value == _uiState.value.value) return
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.setPrivateReplies(value)) {
                is ApiResult.Success -> _uiState.update { it.copy(value = result.data, busy = false, saved = true) }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onChooseShown() = _uiState.update { it.copy(choose = false) }
    fun onSavedShown() = _uiState.update { it.copy(saved = false) }
    fun onFailureShown() = _uiState.update { it.copy(failure = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(busy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        /** The choices in their order on screen. */
        val CHOICES = listOf(PrivateReplies.MY_PEOPLE, PrivateReplies.COMMUNITY, PrivateReplies.NOBODY)

        @StringRes
        fun label(value: PrivateReplies): Int = when (value) {
            PrivateReplies.MY_PEOPLE -> R.string.private_replies_my_people
            PrivateReplies.COMMUNITY -> R.string.private_replies_community
            PrivateReplies.NOBODY -> R.string.private_replies_nobody
        }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PrivateRepliesViewModel(container.repliesRepository) }
        }
    }
}

/** The choice dialog (opened with the fragment's themed context). */
object PrivateRepliesDialog {
    fun show(fragment: Fragment, current: PrivateReplies, onChosen: (PrivateReplies) -> Unit) {
        val context = fragment.requireContext()
        val choices = PrivateRepliesViewModel.CHOICES
        var chosen = choices.indexOf(current)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.private_replies_title)
            .setSingleChoiceItems(choices.map { context.getString(PrivateRepliesViewModel.label(it)) }.toTypedArray(), chosen) { _, which ->
                chosen = which
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ -> choices.getOrNull(chosen)?.let(onChosen) }
            .show()
    }
}
