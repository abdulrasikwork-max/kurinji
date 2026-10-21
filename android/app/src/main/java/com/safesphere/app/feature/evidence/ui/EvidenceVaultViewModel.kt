package com.safesphere.app.feature.evidence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.evidence.domain.AnchorStatus
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.RemoteEvidence
import com.safesphere.app.feature.evidence.domain.VaultItem
import com.safesphere.app.feature.evidence.session.CustodyCheck
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.IntegrityResult
import com.safesphere.app.feature.evidence.session.VaultActionResult
import com.safesphere.app.feature.evidence.session.VaultLock
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Status of one row, in words on screen (never colour alone). */
enum class RowStatus {
    /** Encrypted on this phone, not uploaded yet. */
    ON_PHONE,
    UPLOADING,
    /** Server verified the hash; a copy is also on this phone. */
    VERIFIED_IN_VAULT,
    /** Server verified the hash; removed from this phone (can be brought back). */
    ONLY_IN_VAULT,
    /** The server refused it (kept on the phone). */
    UPLOAD_REFUSED,
    DELETED,
    /** In the vault, but recorded on another phone (or before a reinstall): its key is not on this phone. */
    OTHER_PHONE,
    /** The server found its stored copy damaged, and this phone no longer has the item to repair it. */
    DAMAGED_IN_VAULT
}

data class VaultRow(
    val clientRef: String,
    val kind: EvidenceKind?,
    val capturedStartedAt: Instant,
    val duration: Duration,
    val sizeBytes: Long,
    val status: RowStatus,
    val uploadPercent: Int,
    val escrowed: Boolean,
    val canOpen: Boolean,
    val canRemoveFromPhone: Boolean,
    val canDownload: Boolean,
    val canDelete: Boolean,
    val canVerify: Boolean,
    val canRetryUpload: Boolean = false
)

data class CustodyUi(val intact: Boolean, val chainLength: Long, val verifiedAt: Instant, val anchor: AnchorStatus)

data class VaultUiState(
    /** True until the person confirms the phone's screen lock; nothing about the evidence is shown meanwhile. */
    val locked: Boolean = true,
    /** The phone has no screen lock, so the vault could not be locked (shown as a warning). */
    val unprotected: Boolean = false,
    val rows: List<VaultRow> = emptyList(),
    val custody: CustodyUi? = null,
    val custodyUnavailable: Boolean = false,
    val busyRef: String? = null
)

/** One-shot messages for the screen. */
sealed interface VaultEvent {
    data class Open(val clientRef: String, val kind: EvidenceKind, val file: File) : VaultEvent
    data class Result(val result: VaultActionResult) : VaultEvent
    data class Integrity(val result: IntegrityResult) : VaultEvent
    data object OpenFailed : VaultEvent
    data object KeyUnavailable : VaultEvent
}

/**
 * The Evidence Vault screen (Phase 5): every item of one emergency (or all of the person's items), its honest state,
 * and the actions the owner may take. Rows come from the phone's index, plus, for one emergency, the server's list
 * (items whose key is not on this phone are shown as such, not hidden).
 *
 * Behind the [VaultLock]: while locked, no rows are produced and every action is refused, whatever the view does.
 */
class EvidenceVaultViewModel(
    private val vault: EvidenceVault,
    private val lock: VaultLock,
    /** -1 = every emergency of the signed-in person. */
    private val emergencyId: Long
) : ViewModel() {

    private val remote = MutableStateFlow<List<RemoteEvidence>>(emptyList())
    private val custody = MutableStateFlow<CustodyCheck?>(null)
    private val custodyUnavailable = MutableStateFlow(false)
    private val busy = MutableStateFlow<String?>(null)

    private val _event = MutableStateFlow<VaultEvent?>(null)
    val event: StateFlow<VaultEvent?> = _event.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val access = combine(vault.currentUserId, lock.unlockedFor, lock.unprotected) { user, unlockedFor, unprotected ->
        (user != null && user == unlockedFor) to unprotected
    }

    val uiState: StateFlow<VaultUiState> =
        combine(
            vault.items, vault.sync, remote, access,
            combine(custody, custodyUnavailable, busy) { c, u, b -> Triple(c, u, b) }
        ) { _, sync, remoteItems, (unlocked, unprotected), (check, unavailable, busyRef) ->
            if (!unlocked) return@combine VaultUiState(locked = true)
            val local = vault.itemsFor(vault.currentUserId.value, emergencyId.takeIf { it >= 0 })
            VaultUiState(
                locked = false,
                unprotected = unprotected,
                rows = rowsFor(local, remoteItems, sync.uploadingRef),
                custody = check?.let { CustodyUi(it.report.chainIntact, it.report.chainLength, it.report.verifiedAt, it.anchor) },
                custodyUnavailable = unavailable,
                busyRef = busyRef
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, VaultUiState())

    private val unlocked: Boolean get() = lock.isUnlocked(vault.currentUserId.value)

    /** The phone's screen lock answered (see ScreenLockGate). */
    fun onScreenLockResult(result: ScreenLockGate.Result) {
        val user = vault.currentUserId.value ?: return
        when (result) {
            ScreenLockGate.Result.CONFIRMED -> lock.unlock(user)
            // Without a screen lock there is nothing to ask; the person is told the vault is unprotected.
            ScreenLockGate.Result.NO_SCREEN_LOCK -> lock.unlock(user, withoutScreenLock = true)
            ScreenLockGate.Result.CANCELLED -> return
        }
        refresh()
    }

    /** The vault screen is no longer visible (home button, screen off, back): lock it again. */
    fun onLeftScreen() {
        lock.lock()
        custody.value = null
        remote.value = emptyList()
    }

    fun refresh() {
        vault.syncNow()
        if (emergencyId < 0 || !unlocked) return
        viewModelScope.launch {
            when (val result = vault.remoteItems(emergencyId)) {
                is ApiResult.Success -> remote.value = result.data
                is ApiResult.Failure -> report(result)
            }
            when (val result = vault.custody(emergencyId)) {
                is ApiResult.Success -> {
                    custody.value = result.data
                    custodyUnavailable.value = false
                }
                is ApiResult.Failure -> {
                    report(result)
                    custodyUnavailable.value = true
                }
            }
        }
    }

    fun onOpen(clientRef: String) = act(clientRef) {
        val kind = vault.itemsFor(vault.currentUserId.value).firstOrNull { it.clientRef == clientRef }?.kind ?: return@act
        vault.openForViewing(clientRef)
            .onSuccess { _event.value = VaultEvent.Open(clientRef, kind, it) }
            .onFailure { _event.value = if (it is EvidenceVault.KeyUnavailableException) VaultEvent.KeyUnavailable else VaultEvent.OpenFailed }
    }

    fun onViewerClosed(clientRef: String) {
        viewModelScope.launch { vault.closeViewer(clientRef) }
    }

    fun onVerify(clientRef: String) = act(clientRef) {
        val result = vault.checkIntegrity(clientRef) ?: return@act
        result.failure?.let(::report)
        _event.value = VaultEvent.Integrity(result)
    }

    fun onRemoveFromPhone(clientRef: String) = act(clientRef) { result(vault.removeFromPhone(clientRef)) }

    fun onDownload(clientRef: String) = act(clientRef) { result(vault.downloadToPhone(clientRef)) }

    fun onRetryUpload(clientRef: String) = act(clientRef) { result(vault.retryUpload(clientRef)) }

    fun onDelete(clientRef: String, password: String) = act(clientRef) {
        result(vault.deleteEverywhere(clientRef, password))
        refresh()
    }

    fun onEventHandled() {
        _event.value = null
    }

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    private fun result(result: VaultActionResult) {
        if (result is VaultActionResult.Failed) report(result.failure)
        _event.value = VaultEvent.Result(result)
    }

    /** Every action: refused while locked (defence in depth: the locked screen shows no buttons anyway). */
    private fun act(clientRef: String, block: suspend () -> Unit) {
        if (busy.value != null || !unlocked) return
        busy.value = clientRef
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = null
            }
        }
    }

    private fun report(result: ApiResult<*>) {
        if (result.isSessionExpired()) _sessionExpired.value = true
    }

    companion object {
        fun factory(container: AppContainer, emergencyId: Long) = viewModelFactory {
            initializer { EvidenceVaultViewModel(container.evidenceVault, container.vaultLock, emergencyId) }
        }

        /** Pure: local items first (with their real state), then server items this phone has no key for. */
        fun rowsFor(local: List<VaultItem>, remote: List<RemoteEvidence>, uploadingRef: String?): List<VaultRow> {
            val known = local.map { it.clientRef }.toSet()
            val localRows = local.map { item ->
                val status = when {
                    item.isDeleted -> RowStatus.DELETED
                    item.uploadRefused -> RowStatus.UPLOAD_REFUSED
                    item.cloud == CloudState.SEALED && item.onPhone -> RowStatus.VERIFIED_IN_VAULT
                    item.cloud == CloudState.SEALED -> RowStatus.ONLY_IN_VAULT
                    // Only reachable after the server found its copy damaged (removal needs a verified copy).
                    !item.onPhone && item.cloud == CloudState.UPLOADING -> RowStatus.DAMAGED_IN_VAULT
                    item.cloud == CloudState.UPLOADING || item.clientRef == uploadingRef -> RowStatus.UPLOADING
                    else -> RowStatus.ON_PHONE
                }
                VaultRow(
                    clientRef = item.clientRef,
                    kind = item.kind,
                    capturedStartedAt = item.capturedStartedAt,
                    duration = Duration.between(item.capturedStartedAt, item.capturedEndedAt),
                    sizeBytes = item.plaintextBytes,
                    status = status,
                    uploadPercent = if (item.ciphertextBytes > 0) ((item.uploadedBytes * 100) / item.ciphertextBytes).toInt() else 0,
                    escrowed = item.escrowed,
                    canOpen = item.onPhone && item.wrappedKey != null,
                    canRemoveFromPhone = item.onPhone && item.cloud == CloudState.SEALED,
                    canDownload = !item.onPhone && item.cloud == CloudState.SEALED && item.wrappedKey != null,
                    canDelete = !item.isDeleted,
                    canVerify = item.onPhone || item.cloud == CloudState.SEALED,
                    canRetryUpload = item.uploadRefused && item.onPhone && !item.isDeleted
                )
            }
            val remoteRows = remote.filter { it.clientRef !in known }.map { item ->
                VaultRow(
                    clientRef = item.clientRef,
                    kind = item.kind,
                    capturedStartedAt = item.capturedStartedAt,
                    duration = Duration.between(item.capturedStartedAt, item.capturedEndedAt),
                    sizeBytes = item.sizeBytes,
                    status = if (item.status == "DELETED") RowStatus.DELETED else RowStatus.OTHER_PHONE,
                    uploadPercent = 0,
                    escrowed = item.escrowed,
                    canOpen = false,
                    canRemoveFromPhone = false,
                    canDownload = false,
                    canDelete = false,
                    canVerify = false
                )
            }
            return (localRows + remoteRows).sortedBy { it.capturedStartedAt }
        }
    }
}
