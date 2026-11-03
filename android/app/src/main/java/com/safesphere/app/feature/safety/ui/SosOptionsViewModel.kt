package com.safesphere.app.feature.safety.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.feature.emergency.domain.AutoAudioChoice
import com.safesphere.app.feature.emergency.domain.SosOptions
import com.safesphere.app.feature.emergency.domain.SosOptionsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 17F: the person's own SOS choices (shake gesture, audio in an SOS), per account on this phone. Both are OFF
 * until the person says otherwise; nothing here is sent to the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosOptionsViewModel(
    private val store: SosOptionsStore,
    private val userId: Flow<Long?>,
    /** False on a phone without an accelerometer: the shake switch is then disabled and says why. */
    val shakeSensorAvailable: Boolean
) : ViewModel() {

    /** Null until a signed-in account is known. */
    val options: StateFlow<SosOptions?> = userId
        .flatMapLatest { id -> if (id == null) flowOf(null) else store.options(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setShake(on: Boolean) = withUser { store.setShake(it, on) }

    fun setAutoAudio(choice: AutoAudioChoice) = withUser { store.setAutoAudio(it, choice) }

    /** True while the one-time question about audio has not been answered (the Safety hub then asks it). */
    suspend fun audioQuestionDue(): Boolean {
        val choice = (options.value ?: currentOptions())?.autoAudio ?: return false
        return choice == AutoAudioChoice.NOT_ASKED
    }

    private suspend fun currentOptions(): SosOptions? = userId.first()?.let { store.options(it).first() }

    private fun withUser(block: suspend (Long) -> Unit) {
        viewModelScope.launch { userId.first()?.let { block(it) } }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SosOptionsViewModel(
                    store = container.sosOptionsStore,
                    userId = container.currentUser.map { it?.id },
                    shakeSensorAvailable = container.shakeSensor.isAvailable
                )
            }
        }
    }
}
