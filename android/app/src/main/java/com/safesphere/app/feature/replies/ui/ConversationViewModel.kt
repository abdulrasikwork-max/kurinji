package com.safesphere.app.feature.replies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.RepliesRepository
import com.safesphere.app.feature.replies.domain.Turn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUiState(
    val conversation: Conversation? = null,
    /** Starting a private reply: the moment it answers and its author's name (nothing exists on the server yet). */
    val startFor: String? = null,
    val startName: String? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    /** Gone, closed and deleted, or not the reader's: the same words. */
    val gone: Boolean = false,
    /** Collapsed messages the reader chose to read. */
    val uncovered: Set<String> = emptySet(),
    /** One-shot: the message went, the writing box empties. */
    val sent: Boolean = false,
    /** One-shot: the server suggested one calm support line (never stored). */
    val support: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    /** The writing box shows when the reader may write now, or when they are starting a private reply. */
    val canWrite: Boolean get() = conversation?.you?.canSend ?: (startFor != null)
}

/**
 * Phase N5 (D17, D18): one plan or private reply. A private reply takes strict turns (nobody sends twice in a row);
 * text only; no edit, no unsend; either person can end it. A plan is open to its members until it closes. Nothing says
 * whether or when anyone read anything. Starting a private reply happens here too: the first message creates it.
 */
class ConversationViewModel(private val repository: RepliesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var id: String? = null
    private var read: Job? = null

    fun load(conversationId: String) {
        if (id == conversationId && (_uiState.value.conversation != null || _uiState.value.loading)) return
        id = conversationId
        _uiState.update { it.copy(loading = true, failure = null, gone = false) }
        reread()
    }

    /** A new private reply to [momentId] (its author [name]); the screen waits for the first message. */
    fun start(momentId: String, name: String) {
        if (_uiState.value.conversation != null || id != null) return
        _uiState.update { it.copy(startFor = momentId, startName = name) }
    }

    /** Read again quietly (the screen comes back, and every little while it is on screen). */
    fun refresh() {
        if (id == null || _uiState.value.sending || read?.isActive == true) return
        reread()
    }

    fun retry() {
        if (id == null) return
        _uiState.update { it.copy(loading = true, failure = null) }
        reread()
    }

    fun onUncover(messageId: String) = _uiState.update { it.copy(uncovered = it.uncovered + messageId) }

    fun send(text: String) {
        val words = text.trim()
        val state = _uiState.value
        if (words.isEmpty() || state.sending || !state.canWrite) return
        _uiState.update { it.copy(sending = true, failure = null) }
        viewModelScope.launch {
            val conversation = _uiState.value.conversation
            if (conversation == null) {
                val momentId = _uiState.value.startFor ?: return@launch
                when (val result = repository.startReply(momentId, words)) {
                    is ApiResult.Success -> {
                        id = result.data.id
                        _uiState.update {
                            it.copy(conversation = result.data, sending = false, sent = true,
                                support = result.data.messages.any { m -> m.mine && m.supportSuggested })
                        }
                    }
                    is ApiResult.Failure -> fail(result)
                }
                return@launch
            }
            when (val result = repository.send(conversation.id, words)) {
                is ApiResult.Success -> {
                    _uiState.update { s ->
                        val now = s.conversation ?: return@update s
                        // Until the server's next answer: the new message shows, and a private reply waits its turn.
                        val you = if (now.kind == ConversationKind.REPLY) now.you.copy(turn = Turn.WAITING, canSend = false) else now.you
                        s.copy(conversation = now.copy(messages = now.messages + result.data, you = you), sending = false,
                            sent = true, support = s.support || result.data.supportSuggested)
                    }
                    reread()
                }
                is ApiResult.Failure -> {
                    fail(result)
                    // A refusal (turns, closed, a pause) means the state changed: read where it stands.
                    if (result.error == ApiError.CONFLICT || result.error == ApiError.FORBIDDEN) reread()
                }
            }
        }
    }

    /** D17: either person ends a private reply; it stays readable for both. */
    fun end() {
        val conversation = _uiState.value.conversation ?: return
        if (_uiState.value.sending) return
        _uiState.update { it.copy(sending = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.end(conversation.id)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(sending = false) }
                    reread()
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onSentHandled() = _uiState.update { it.copy(sent = false) }

    fun onSupportShown() = _uiState.update { it.copy(support = false) }

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun reread() {
        val conversationId = id ?: return
        read = viewModelScope.launch {
            when (val result = repository.conversation(conversationId)) {
                is ApiResult.Success -> _uiState.update { it.copy(conversation = result.data, loading = false) }
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                    _uiState.update { it.copy(loading = false, gone = true, conversation = null) }
                } else if (_uiState.value.conversation == null) {
                    fail(result)
                } else {
                    // A quiet read that failed keeps what is shown; a lost session is still said.
                    _uiState.update { it.copy(loading = false, sessionExpired = result.isSessionExpired()) }
                }
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(loading = false, sending = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ConversationViewModel(container.repliesRepository) }
        }
    }
}
