package com.safesphere.app.feature.evidence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.domain.PassphraseGenerator
import com.safesphere.app.feature.evidence.domain.PassphrasePolicy
import com.safesphere.app.feature.evidence.domain.PassphraseProblem
import com.safesphere.app.feature.evidence.domain.RecoveryEnvelope
import com.safesphere.app.feature.evidence.session.EvidenceRecovery
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.RecoveryResult
import com.safesphere.app.feature.evidence.session.VaultLock
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Restore on a new phone" section of the vault screen. */
data class RecoveryUi(
    /** False until the server answered (or when it could not be asked). */
    val statusKnown: Boolean = false,
    val unavailable: Boolean = false,
    val configured: Boolean = false,
    val keyOnPhone: Boolean = false,
    val updatedAt: Instant? = null,
    val lastDownloadedAt: Instant? = null,
    /** Items in the vault whose key the server also holds for the recovery key / all of the person's vault items. */
    val protectedItems: Int = 0,
    val vaultItems: Int = 0,
    val busy: Boolean = false
) {
    val canSetUp get() = statusKnown && !configured
    val canRestore get() = statusKnown && configured && !keyOnPhone
    val canChange get() = statusKnown && configured && keyOnPhone
}

sealed interface RecoveryEvent {
    data class Result(val result: RecoveryResult) : RecoveryEvent
    data class Problem(val problem: PassphraseProblem) : RecoveryEvent
}

/**
 * Drives [EvidenceRecovery] for the vault screen. Lives behind the same [VaultLock] as the vault: while locked it
 * shows nothing and refuses every action. Passphrases arrive as CharArrays and are wiped here after use; the account
 * password is needed by the server for every change and for fetching the envelope.
 */
class EvidenceRecoveryViewModel(
    private val recovery: EvidenceRecovery,
    private val vault: EvidenceVault,
    private val lock: VaultLock,
    private val user: Flow<AuthUser?>,
    private val policy: () -> PassphrasePolicy,
    private val generator: () -> PassphraseGenerator,
    /** Where the word lists are loaded from assets (first use only). */
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryUi())
    val state: StateFlow<RecoveryUi> = _state.asStateFlow()

    private val _event = MutableStateFlow<RecoveryEvent?>(null)
    val event: StateFlow<RecoveryEvent?> = _event.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /** Kept only in memory, only while this screen lives, so a mistyped passphrase does not need the password again. */
    private var envelope: RecoveryEnvelope? = null

    private val userId: Long? get() = vault.currentUserId.value?.takeIf { lock.isUnlocked(it) }

    fun refresh() {
        val id = userId ?: return
        viewModelScope.launch {
            val result = recovery.status()
            // Deletions done on another phone also destroy this phone's copies of the keys (§11).
            vault.syncDeletions(id)
            val items = vault.itemsFor(id).filter { it.serverId != null && !it.isDeleted }
            // Applied to the LATEST state: an action may have started or ended (busy) while the server was asked.
            _state.update { current ->
                val counted = current.copy(
                    keyOnPhone = recovery.hasKeyOnPhone(id),
                    protectedItems = items.count { it.recoverable },
                    vaultItems = items.size
                )
                when (result) {
                    is ApiResult.Success -> counted.copy(
                        statusKnown = true, unavailable = false, configured = result.data.configured,
                        updatedAt = result.data.updatedAt, lastDownloadedAt = result.data.lastDownloadedAt
                    )
                    is ApiResult.Failure -> counted.copy(statusKnown = false, unavailable = true)
                }
            }
            if (result is ApiResult.Failure) report(result)
        }
    }

    /** A fresh suggestion that passes the policy for this person (a name could, rarely, hide inside a word). */
    suspend fun suggest(): CharArray {
        val person = user.first()
        val gen = withContext(io) { generator() }
        val rules = withContext(io) { policy() }
        repeat(10) {
            val candidate = gen.generate()
            if (rules.check(candidate, candidate, person?.email, person?.name, null) == null) return candidate
            candidate.fill(' ')
        }
        return gen.generate()
    }

    fun onSetUp(passphrase: CharArray, confirmation: CharArray, password: CharArray) = perform(passphrase, confirmation, password) { id ->
        if (!acceptable(passphrase, confirmation, password)) return@perform
        finish(recovery.setUp(id, passphrase, String(password)))
    }

    fun onRestore(passphrase: CharArray, password: CharArray) = perform(passphrase, password) { id ->
        val known = envelope ?: when (val fetched = recovery.fetchEnvelope(String(password))) {
            is ApiResult.Success -> fetched.data.also { envelope = it }
            is ApiResult.Failure -> return@perform finish(
                if (fetched.error == com.safesphere.app.core.network.ApiError.NOT_FOUND) RecoveryResult.NotSetUp
                else RecoveryResult.Failed(fetched)
            )
        }
        val result = recovery.restore(id, known, passphrase)
        if (result !is RecoveryResult.WrongPassphrase) envelope = null
        finish(result)
    }

    fun onChange(current: CharArray, passphrase: CharArray, confirmation: CharArray, password: CharArray) =
        perform(current, passphrase, confirmation, password) { id ->
            if (!acceptable(passphrase, confirmation, password)) return@perform
            val known = when (val fetched = recovery.fetchEnvelope(String(password))) {
                is ApiResult.Success -> fetched.data
                is ApiResult.Failure -> return@perform finish(RecoveryResult.Failed(fetched))
            }
            finish(recovery.changePassphrase(id, known, current, passphrase, String(password)))
        }

    fun onForgot(passphrase: CharArray, confirmation: CharArray, password: CharArray) = perform(passphrase, confirmation, password) { id ->
        if (!acceptable(passphrase, confirmation, password)) return@perform
        finish(recovery.replaceForgotten(id, passphrase, String(password)))
    }

    fun onEventHandled() {
        _event.value = null
    }

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    /** The vault screen was left: forget the cached envelope. */
    fun onLeftScreen() {
        envelope = null
        _state.value = RecoveryUi()
    }

    private suspend fun acceptable(passphrase: CharArray, confirmation: CharArray, password: CharArray): Boolean {
        val person = user.first()
        val problem = withContext(io) { policy() }.check(passphrase, confirmation, person?.email, person?.name, password)
        if (problem != null) _event.value = RecoveryEvent.Problem(problem)
        return problem == null
    }

    private fun finish(result: RecoveryResult) {
        if (result is RecoveryResult.Failed) report(result.failure)
        _event.value = RecoveryEvent.Result(result)
        refresh()
    }

    /** Runs [block] once at a time and only while the vault is unlocked; wipes every secret afterwards. */
    private fun perform(vararg secrets: CharArray, block: suspend (Long) -> Unit) {
        val id = userId
        if (id == null || _state.value.busy) {
            secrets.forEach { it.fill(' ') }
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(id)
            } finally {
                secrets.forEach { it.fill(' ') }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun report(result: ApiResult<*>) {
        if (result.isSessionExpired()) _sessionExpired.value = true
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                EvidenceRecoveryViewModel(
                    recovery = container.evidenceRecovery,
                    vault = container.evidenceVault,
                    lock = container.vaultLock,
                    user = container.currentUser,
                    policy = { container.passphrasePolicy },
                    generator = { container.passphraseGenerator }
                )
            }
        }
    }
}
