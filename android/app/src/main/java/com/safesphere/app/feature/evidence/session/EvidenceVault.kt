package com.safesphere.app.feature.evidence.session

import com.safesphere.app.core.crypto.EscrowKey
import com.safesphere.app.core.crypto.EvidenceCipher
import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.core.device.ConnectivityMonitor
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.google.gson.reflect.TypeToken
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.data.EvidenceIndexFile
import com.safesphere.app.feature.evidence.data.EvidenceJson
import com.safesphere.app.feature.evidence.data.PendingCapture
import com.safesphere.app.feature.evidence.data.PlaintextWipe
import com.safesphere.app.feature.evidence.data.RecoveryKeys
import com.safesphere.app.feature.evidence.domain.AnchorStatus
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.CustodyAnchor
import com.safesphere.app.feature.evidence.domain.CustodyAnchoring
import com.safesphere.app.feature.evidence.domain.CustodyReport
import com.safesphere.app.feature.evidence.domain.EvidenceDeclaration
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.EvidenceRepository
import com.safesphere.app.feature.evidence.domain.RemoteEvidence
import com.safesphere.app.feature.evidence.domain.VaultItem
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Upload progress shown on the SOS card and the vault screen. */
data class EvidenceSyncState(
    val isOnline: Boolean = true,
    val uploadingRef: String? = null,
    /** Null until the server was asked. False = no escrow: cloud copies open only on this phone. */
    val vaultKeyAvailable: Boolean? = null,
    val signedOut: Boolean = false
)

/** Outcome of an action the person asked for on the vault screen. */
sealed interface VaultActionResult {
    data object Done : VaultActionResult
    data class Failed(val failure: ApiResult.Failure) : VaultActionResult
    /** A local problem (missing key, damaged file, hash mismatch). */
    data class LocalProblem(val reason: LocalProblemReason) : VaultActionResult
}

enum class LocalProblemReason {
    KEY_UNAVAILABLE,
    FILE_DAMAGED,
    NOT_ALLOWED,
    /** The server's stored copy failed its re-check: the phone copy was kept and is being uploaded again. */
    VAULT_COPY_DAMAGED
}

/** The server's stored copy, as checked just now. */
enum class VaultCopyCheck {
    INTACT,
    /** Damaged on the server; this phone's intact copy is being uploaded again. */
    DAMAGED_REUPLOADING,
    /** Damaged on the server and this phone has no intact copy to repair it. */
    DAMAGED,
    /** Not verified in the vault yet (still on its way). */
    NOT_UPLOADED,
    /** The server could not be asked (offline, signed out...). */
    UNAVAILABLE
}

/** "Check integrity": the phone copy against its recorded hash ([phone] null = not on this phone) + the vault copy. */
data class IntegrityResult(val phone: Boolean?, val vault: VaultCopyCheck, val failure: ApiResult.Failure? = null)

/** A vault item recovered on a new phone: the server's record + its item key, already sealed by THIS phone's Keystore. */
data class RestoredItem(val remote: RemoteEvidence, val sealedKey: String) {
    override fun toString(): String = "RestoredItem(id=${remote.id}, key=<redacted>)"
}

/** The server's custody report plus this phone's own comparison with what it saw before. */
data class CustodyCheck(val report: CustodyReport, val anchor: AnchorStatus)

/**
 * Keeps evidence uploads going when the app process is gone: on a device, a JobScheduler job that runs when the
 * network is available (AndroidEvidenceUploadScheduler); nothing in unit tests. Told whenever this person has
 * evidence waiting to upload.
 */
fun interface EvidenceUploadScheduler {
    fun onPendingUploads(pending: Boolean)

    companion object {
        val NONE = EvidenceUploadScheduler { }
    }
}

/**
 * The ONE holder of the phone's Evidence Vault (Phase 5), like EmergencyLocationSharing for locations. Lives in the
 * application scope, so sealing and uploading continue when the person leaves a screen.
 *
 * Capture -> seal -> upload:
 *  1. a capture writes PLAINTEXT only to the private staging folder, with a note ([prepareCapture]);
 *  2. [completeCapture] seals it at once: a fresh 256-bit key, SSEV1 encryption to vault/<ref>.ssev, both SHA-256
 *     hashes, the key sealed by the Android Keystore; then the plaintext is deleted;
 *  3. the upload loop declares the item (with an escrow copy of the key when the server publishes a vault recovery
 *     key), sends the ciphertext in resumable pieces, and asks the server to verify the hash and seal it.
 *
 * Offline: sealed items simply wait (they are encrypted, so keeping them on the phone is safe) and go up when the
 * connection returns; nothing is lost when the process dies, an interrupted capture is sealed on the next start
 * ([recover]), and [uploadScheduler] brings the process back for pending uploads once the network is there.
 * Items belong to the person who captured them: another account on the same phone never sees, opens, changes or
 * uploads them (every action checks the owner). Plaintext files are overwritten before they are deleted
 * ([PlaintextWipe], best effort). Nothing here logs content, hashes, keys or locations.
 *
 * Threading: public functions are called from the main thread; file and crypto work runs on [io]; the index is only
 * changed under [indexLock].
 */
class EvidenceVault(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val files: EvidenceFiles,
    private val keyWrapper: LocalKeyWrapper,
    private val repository: EvidenceRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    session: Flow<AuthSession?>,
    private val pinnedVaultKeyId: String? = null,
    private val now: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
    private val retryDelaysMillis: List<Long> = listOf(5_000, 10_000, 20_000, 40_000, 60_000),
    private val uploadScheduler: EvidenceUploadScheduler = EvidenceUploadScheduler.NONE,
    /** The person's recovery key on this phone, if they set up "restore on a new phone" (§11). */
    private val recoveryKeys: RecoveryKeys = RecoveryKeys.NONE
) {
    private val index = EvidenceIndexFile(files)
    private val indexLock = Mutex()
    private val anchorLock = Mutex()
    private val anchorListType = object : TypeToken<List<CustodyAnchor>>() {}.type

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    /** Every item on this phone, for every account; screens filter by the signed-in person ([itemsFor]). */
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _sync = MutableStateFlow(EvidenceSyncState())
    val sync: StateFlow<EvidenceSyncState> = _sync.asStateFlow()

    private val _currentUserId = MutableStateFlow<Long?>(null)
    val currentUserId: StateFlow<Long?> = _currentUserId.asStateFlow()

    /** False until the saved session has been read once (signed in or not). */
    private val sessionKnown = MutableStateFlow(false)

    private val _hasPendingUploads = MutableStateFlow(false)
    /** True while the signed-in person has evidence on this phone that still has to reach the vault. */
    val hasPendingUploads: StateFlow<Boolean> = _hasPendingUploads.asStateFlow()

    private val uploadSignal = Channel<Unit>(Channel.CONFLATED)
    private var escrowKey: EscrowKey? = null
    private var escrowChecked = false
    private var uploadJob: Job? = null

    /** Becomes complete once the index is loaded and interrupted captures are sealed. */
    val ready: Job = scope.launch { recover() }

    init {
        scope.launch {
            connectivityMonitor.isOnline.collect { online ->
                _sync.value = _sync.value.copy(isOnline = online)
                if (online) uploadSignal.trySend(Unit)
            }
        }
        scope.launch {
            session.map { it?.user?.id }.distinctUntilChanged().collectLatest { userId ->
                _currentUserId.value = userId
                sessionKnown.value = true
                _sync.value = _sync.value.copy(signedOut = userId == null, uploadingRef = null)
                escrowKey = null
                escrowChecked = false
                if (userId != null) {
                    ready.join()
                    uploadLoop(userId)
                }
            }
        }
        scope.launch {
            combine(_items, _currentUserId, ::pendingFor).distinctUntilChanged().collect { pending ->
                _hasPendingUploads.value = pending
                uploadScheduler.onPendingUploads(pending)
            }
        }
    }

    /**
     * For the background upload job: waits until nothing of the signed-in person is left to upload. True when that
     * happened within [timeoutMillis] (the job is done), false when uploads are still pending (run the job again).
     */
    suspend fun awaitUploadsSettled(timeoutMillis: Long): Boolean = withTimeoutOrNull(timeoutMillis) {
        // In a process just started by the job, the index and the saved session must be known first; otherwise
        // "nothing pending" would be read too early and the job would end before uploading anything.
        ready.join()
        sessionKnown.first { it }
        syncNow()
        combine(_items, _currentUserId, ::pendingFor).first { !it }
        true
    } ?: false

    private fun pendingFor(items: List<VaultItem>, userId: Long?): Boolean =
        userId != null && items.any { it.userId == userId && it.needsUpload }

    fun itemsFor(userId: Long?, emergencyId: Long? = null): List<VaultItem> =
        _items.value.filter { it.userId == userId && (emergencyId == null || it.emergencyId == emergencyId) }
            .sortedWith(compareBy<VaultItem> { it.capturedStartedAt }.thenBy { it.sequenceNo })

    /** Wakes the upload loop (new item, screen opened, "retry" tapped). */
    fun syncNow() {
        uploadSignal.trySend(Unit)
    }

    // ---- capture ------------------------------------------------------------------------------------

    /**
     * Reserves a staging file for a new capture and writes its note, so even a crash right after this point leaves
     * something [recover] can seal. Returns the plaintext file the recorder / camera app must write into.
     */
    suspend fun prepareCapture(
        userId: Long,
        emergencyId: Long,
        kind: EvidenceKind,
        recordingRef: String? = null
    ): Pair<PendingCapture, File> = withContext(io) {
        files.ensureDirs()
        val pending = PendingCapture(UUID.randomUUID().toString(), userId, emergencyId, kind, recordingRef, now())
        val file = files.captureFile(pending.clientRef, kind)
        file.delete()
        file.createNewFile()
        files.captureNote(pending.clientRef).writeText(EvidenceJson.gson.toJson(pending))
        pending to file
    }

    /** The capture is finished: seal it now and queue it for upload. Returns the sealed item, or null if it was empty. */
    suspend fun completeCapture(pending: PendingCapture, endedAt: Instant = now()): VaultItem? {
        val sealed = withContext(io) {
            // Write the end time into the note first: if sealing fails now, the next start seals it with this time.
            files.captureNote(pending.clientRef).takeIf { it.exists() }
                ?.writeText(EvidenceJson.gson.toJson(pending.copy(endedAt = endedAt)))
            try {
                seal(pending.copy(endedAt = endedAt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keystore or storage failure: the plaintext and its note STAY in the private staging folder and are
                // sealed by [recover] on the next start. Evidence is never thrown away because sealing failed.
                null
            }
        }
        if (sealed != null) uploadSignal.trySend(Unit)
        return sealed
    }

    /** Cancelled before anything was captured (e.g. the camera app was closed). */
    suspend fun discardCapture(pending: PendingCapture) = withContext(io) {
        PlaintextWipe.wipe(files.captureFile(pending.clientRef, pending.kind))
        files.captureNote(pending.clientRef).delete()
    }

    private suspend fun seal(pending: PendingCapture): VaultItem? {
        val plaintext = files.captureFile(pending.clientRef, pending.kind)
        val note = files.captureNote(pending.clientRef)
        indexLock.withLock {
            val known = _items.value.firstOrNull { it.clientRef == pending.clientRef }
            if (known != null) { // sealed earlier; the crash came before the plaintext was deleted
                PlaintextWipe.wipe(plaintext)
                note.delete()
                return known
            }
        }
        if (!plaintext.exists() || plaintext.length() == 0L) {
            plaintext.delete()
            note.delete()
            return null
        }
        val itemKey = EvidenceCipher.newKey(random)
        try {
            val temp = File(files.vaultDir, "${pending.clientRef}.sealing")
            val result = plaintext.inputStream().buffered().use { input ->
                FileOutputStream(temp).use { file ->
                    val output = file.buffered()
                    EvidenceCipher.encrypt(itemKey, input, output, random = random).also {
                        output.flush()
                        file.fd.sync() // on the chip before the plaintext is deleted
                    }
                }
            }
            val wrapped = Base64.getEncoder().encodeToString(keyWrapper.wrap(itemKey))
            EvidenceIndexFile.replace(temp, files.vaultFile(pending.clientRef))
            val item = indexLock.withLock {
                val sequence = (_items.value.filter { it.emergencyId == pending.emergencyId }.maxOfOrNull { it.sequenceNo } ?: -1) + 1
                val created = VaultItem(
                    clientRef = pending.clientRef,
                    userId = pending.userId,
                    emergencyId = pending.emergencyId,
                    kind = pending.kind,
                    sequenceNo = sequence,
                    recordingRef = pending.recordingRef,
                    capturedStartedAt = pending.startedAt,
                    capturedEndedAt = maxOf(pending.endedAt ?: Instant.ofEpochMilli(plaintext.lastModified()), pending.startedAt),
                    plaintextBytes = result.plaintextBytes,
                    ciphertextBytes = result.ciphertextBytes,
                    plaintextSha256 = result.plaintextSha256,
                    ciphertextSha256 = result.ciphertextSha256,
                    wrappedKey = wrapped,
                    onPhone = true
                )
                writeIndex(_items.value + created)
                created
            }
            // Only now, with the ciphertext and its key safely recorded, does the plaintext go.
            PlaintextWipe.wipe(plaintext)
            note.delete()
            return item
        } finally {
            itemKey.fill(0)
        }
    }

    /** On every start: load the index, drop leftovers of viewing, and seal any capture a crash interrupted. */
    private suspend fun recover() = withContext(io) {
        files.ensureDirs()
        files.viewDir.listFiles()?.forEach { PlaintextWipe.wipe(it) }
        files.vaultDir.listFiles { file -> file.name.endsWith(".sealing") || file.name.endsWith(".download") }
            ?.forEach { it.delete() }
        indexLock.withLock { _items.value = index.read() }
        files.captureDir.listFiles { file -> file.name.endsWith(".json") }?.forEach { note ->
            val pending = try {
                EvidenceJson.gson.fromJson(note.readText(), PendingCapture::class.java)
            } catch (e: Exception) {
                null
            }
            if (pending == null) {
                note.delete()
            } else {
                try {
                    seal(pending)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Left in staging; tried again at the next start.
                }
            }
        }
        // Media files without a note cannot be attributed to a person or emergency: removed.
        files.captureDir.listFiles { file -> !file.name.endsWith(".json") }?.forEach { media ->
            val note = runCatching { files.captureNote(media.nameWithoutExtension) }.getOrNull()
            if (note == null || !note.exists()) PlaintextWipe.wipe(media)
        }
    }

    // ---- upload ---------------------------------------------------------------------------------------

    private suspend fun uploadLoop(userId: Long) {
        var attempt = 0
        while (true) {
            val next = itemsFor(userId).firstOrNull { it.needsUpload }
            if (next == null) {
                // Nothing to upload: give earlier items their recovery copy (once per wake-up), then wait.
                try {
                    backfillRecoveryKeys(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never stops the upload loop; tried again at the next wake-up.
                }
                uploadSignal.receive()
                continue
            }
            _sync.value = _sync.value.copy(uploadingRef = next.clientRef)
            val step = try {
                uploadOne(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A local file problem (e.g. the item was deleted or removed while uploading) must never stop the
                // loop or crash the app: the next round sees the new state.
                Step.RETRY_LATER
            }
            when (step) {
                Step.DONE -> attempt = 0
                Step.RETRY_LATER -> {
                    _sync.value = _sync.value.copy(uploadingRef = null)
                    withTimeoutOrNull(retryDelaysMillis[attempt.coerceAtMost(retryDelaysMillis.lastIndex)]) { uploadSignal.receive() }
                    attempt++
                }
                Step.SIGNED_OUT -> {
                    _sync.value = _sync.value.copy(uploadingRef = null)
                    return
                }
            }
            _sync.value = _sync.value.copy(uploadingRef = null)
        }
    }

    private enum class Step { DONE, RETRY_LATER, SIGNED_OUT }

    private suspend fun uploadOne(start: VaultItem): Step {
        var item = start
        val remote: RemoteEvidence = if (item.serverId == null) {
            // The vault key must be known BEFORE declaring: escrow can only be attached at declaration time.
            if (!escrowChecked && repository.vaultKey().let { loadEscrowKey(it) } == null) return Step.RETRY_LATER
            val escrow = escrowFor(item)
            val recovery = recoveryFor(item)
            val first = repository.declare(item.emergencyId, declarationFor(item, escrow, recovery))
            val result = if (recovery != null && first is ApiResult.Failure && first.error == ApiError.CONFLICT &&
                first.message?.contains("recovery", ignoreCase = true) == true
            ) {
                // The server does not accept this recovery copy (no envelope, or the key changed): never let that
                // hold back the evidence itself. Declared without it; the back-fill tries again later.
                repository.declare(item.emergencyId, declarationFor(item, escrow, null))
            } else first
            when (result) {
                is ApiResult.Success -> result.data.also { item = update(item.clientRef) { it.withRemote(result.data) } ?: return Step.DONE }
                is ApiResult.Failure -> return onDeclareFailure(item, result)
            }
        } else {
            when (val result = repository.get(item.serverId!!)) {
                is ApiResult.Success -> result.data
                is ApiResult.Failure -> return onFailure(item, result)
            }
        }
        when (remote.status) {
            "SEALED" -> {
                update(item.clientRef) { it.withRemote(remote) }
                return Step.DONE
            }
            "DELETED" -> {
                // Deleted everywhere from another phone (password-checked by the server): honour it here too.
                shredLocally(item.clientRef, remote.deletedAt ?: now())
                return Step.DONE
            }
        }

        val file = files.vaultFile(item.clientRef)
        if (!file.exists()) {
            update(item.clientRef) { it.copy(onPhone = false) }
            return Step.DONE
        }
        val chunk = (if (remote.uploadChunkBytes > 0) remote.uploadChunkBytes else DEFAULT_CHUNK).toLong()
        var offset = remote.receivedBytes
        while (offset < item.ciphertextBytes) {
            val length = minOf(chunk, item.ciphertextBytes - offset)
            when (val result = repository.uploadPiece(remote.id, file, offset, length)) {
                is ApiResult.Success -> {
                    offset = result.data.receivedBytes
                    item = update(item.clientRef) { it.copy(uploadedBytes = offset, cloud = CloudState.UPLOADING) } ?: return Step.DONE
                }
                is ApiResult.Failure -> {
                    if (result.error != ApiError.CONFLICT) return onFailure(item, result)
                    // Out of step (a lost answer): ask where to continue.
                    when (val fresh = repository.get(remote.id)) {
                        is ApiResult.Success -> {
                            if (fresh.data.status != "UPLOADING") return Step.DONE.also { update(item.clientRef) { it.withRemote(fresh.data) } }
                            offset = fresh.data.receivedBytes
                        }
                        is ApiResult.Failure -> return onFailure(item, fresh)
                    }
                }
            }
        }
        return when (val sealed = repository.seal(remote.id)) {
            is ApiResult.Success -> {
                update(item.clientRef) { it.withRemote(sealed.data) }
                Step.DONE
            }
            is ApiResult.Failure -> {
                if (sealed.error == ApiError.CONFLICT) {
                    // The server's hash did not match what arrived. If the local file itself no longer matches its
                    // recorded hash it is damaged here: stop retrying it (and keep it). Otherwise start over.
                    val intact = withContext(io) { file.inputStream().buffered().use(EvidenceCipher::sha256) } == item.ciphertextSha256
                    if (!intact) {
                        update(item.clientRef) { it.copy(uploadRefused = true) }
                        return Step.DONE
                    }
                    update(item.clientRef) { it.copy(uploadedBytes = 0) }
                    Step.RETRY_LATER
                } else {
                    onFailure(item, sealed)
                }
            }
        }
    }

    private suspend fun onDeclareFailure(item: VaultItem, failure: ApiResult.Failure): Step = when (failure.error) {
        ApiError.CONFLICT -> {
            if (failure.message?.contains("vault recovery key", ignoreCase = true) == true) {
                escrowKey = null // the server rotated its key: fetch it again next time
                escrowChecked = false
                Step.RETRY_LATER
            } else {
                update(item.clientRef) { it.copy(uploadRefused = true) }
                Step.DONE
            }
        }
        else -> onFailure(item, failure)
    }

    private suspend fun onFailure(item: VaultItem, failure: ApiResult.Failure): Step = when (failure.error) {
        ApiError.UNAUTHORIZED -> Step.SIGNED_OUT
        // FORBIDDEN is never sent for evidence (Phase 7 uses it for community only); if a proxy sends it, keep trying.
        ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN, ApiError.TOO_MANY_ATTEMPTS, ApiError.FORBIDDEN ->
            Step.RETRY_LATER
        // Will not become valid by retrying (e.g. the emergency is not this account's, or a time outside it).
        // The evidence itself is KEPT on the phone; only its upload stops.
        ApiError.VALIDATION, ApiError.NOT_FOUND, ApiError.CONFLICT -> {
            update(item.clientRef) { it.copy(uploadRefused = true) }
            Step.DONE
        }
    }

    /** The item key sealed for the vault recovery key, when the server publishes one (and it passes the pin). */
    private suspend fun escrowFor(item: VaultItem): Pair<String, String>? {
        val key = escrowKey ?: return null
        val wrapped = item.wrappedKey ?: return null
        return withContext(io) {
            val itemKey = try {
                keyWrapper.unwrap(Base64.getDecoder().decode(wrapped))
            } catch (e: Exception) {
                return@withContext null
            }
            try {
                key.keyId to Base64.getEncoder().encodeToString(key.wrap(itemKey))
            } finally {
                itemKey.fill(0)
            }
        }
    }

    /**
     * Remembers the server's answer about the vault key for this session. Returns Unit when the answer is known
     * (key or "no key"), null when the server could not be asked (the caller retries later instead of uploading an
     * item without escrow that could have had it).
     */
    private fun loadEscrowKey(result: ApiResult<com.safesphere.app.feature.evidence.domain.VaultKeyInfo>): Unit? {
        if (result !is ApiResult.Success) return null
        escrowChecked = true
        val info = result.data
        escrowKey = if (info.available && info.keyId != null && info.publicKey != null) {
            try {
                EscrowKey.parse(info.publicKey, info.keyId, pinnedVaultKeyId)
            } catch (e: Exception) {
                null // wrong id or not the pinned key: upload WITHOUT escrow rather than trust it
            }
        } else null
        _sync.value = _sync.value.copy(vaultKeyAvailable = escrowKey != null)
        return Unit
    }

    /** The item key wrapped with the person's recovery key, when this phone holds one (works offline). */
    private suspend fun recoveryFor(item: VaultItem): Pair<Int, String>? {
        val wrapped = item.wrappedKey ?: return null
        if (!recoveryKeys.hasKey(item.userId)) return null
        return withContext(io) {
            val itemKey = try {
                keyWrapper.unwrap(Base64.getDecoder().decode(wrapped))
            } catch (e: Exception) {
                return@withContext null
            }
            try {
                recoveryKeys.wrapItemKey(item.userId, item.clientRef, item.ciphertextSha256, itemKey)
                    ?.let { (version, bytes) -> version to Base64.getEncoder().encodeToString(bytes) }
            } catch (e: Exception) {
                null
            } finally {
                itemKey.fill(0)
            }
        }
    }

    /**
     * Gives every declared item of [userId] whose key this phone holds a recovery copy on the server (items declared
     * before the passphrase was set up, or while the server refused it). Stops at the first failure; the next wake-up
     * of the upload loop tries again.
     */
    suspend fun backfillRecoveryKeys(userId: Long) {
        if (!recoveryKeys.hasKey(userId)) return
        val candidates = itemsFor(userId).filter {
            it.serverId != null && !it.recoverable && !it.isDeleted && it.wrappedKey != null
        }
        for (item in candidates) {
            if (_currentUserId.value != userId) return
            val (version, wrapped) = recoveryFor(item) ?: continue
            when (val result = repository.attachRecoveryKey(item.serverId!!, version, Base64.getDecoder().decode(wrapped))) {
                is ApiResult.Success -> update(item.clientRef) { it.withRemote(result.data) }
                is ApiResult.Failure -> {
                    if (result.error == ApiError.CONFLICT && result.message?.contains("deleted", ignoreCase = true) == true) {
                        continue // the tombstone sync shreds it
                    }
                    return
                }
            }
        }
    }

    /**
     * Restore on a new phone (§11): adds the person's vault items, whose keys were recovered with the passphrase and
     * are given here already sealed by THIS phone's Keystore, to the index as "in your vault, not on this phone".
     * The ciphertext is downloaded only when the person taps "Bring back" (hash-checked, recorded in custody).
     * Items this phone already knows are left alone. Returns how many were added.
     */
    suspend fun importRestored(userId: Long, restored: List<RestoredItem>): Int = indexLock.withLock {
        val known = _items.value.map { it.clientRef }.toSet()
        val added = restored.filter { it.remote.clientRef !in known && it.remote.status == "SEALED" && it.remote.kind != null }
            .map { (remote, sealedKey) ->
                VaultItem(
                    clientRef = remote.clientRef,
                    userId = userId,
                    emergencyId = remote.emergencyId,
                    kind = remote.kind!!,
                    sequenceNo = remote.sequenceNo,
                    recordingRef = remote.recordingRef,
                    capturedStartedAt = remote.capturedStartedAt,
                    capturedEndedAt = remote.capturedEndedAt,
                    // The plaintext size is not known before decryption; the ciphertext size is ~0.05 % larger.
                    plaintextBytes = remote.sizeBytes,
                    ciphertextBytes = remote.sizeBytes,
                    plaintextSha256 = remote.plaintextSha256.orEmpty(),
                    ciphertextSha256 = remote.ciphertextSha256,
                    wrappedKey = sealedKey,
                    onPhone = false,
                    cloud = CloudState.SEALED,
                    serverId = remote.id,
                    uploadedBytes = remote.sizeBytes,
                    escrowed = remote.escrowed,
                    sealedAt = remote.sealedAt,
                    recoverable = true
                )
            }
        if (added.isNotEmpty()) writeIndex(_items.value + added)
        added.size
    }

    /**
     * Honours "delete everywhere" done on ANOTHER phone for items this phone has already seen verified: their key is
     * destroyed here too. (The upload loop only learns it for items still uploading.) Asks the server once per
     * emergency. Returns false when the server could not be asked.
     */
    suspend fun syncDeletions(userId: Long): Boolean {
        val emergencies = itemsFor(userId).filter { it.serverId != null && !it.isDeleted && it.cloud == CloudState.SEALED }
            .map { it.emergencyId }.distinct()
        for (emergencyId in emergencies) {
            val remote = when (val result = repository.list(emergencyId)) {
                is ApiResult.Success -> result.data
                is ApiResult.Failure -> return false
            }
            if (_currentUserId.value != userId) return false
            remote.filter { it.status == "DELETED" }.forEach { deleted ->
                val local = owned(deleted.clientRef)
                if (local != null && !local.isDeleted) shredLocally(local.clientRef, deleted.deletedAt ?: now())
            }
        }
        return true
    }

    private fun declarationFor(item: VaultItem, escrow: Pair<String, String>?, recovery: Pair<Int, String>?) = EvidenceDeclaration(
        clientRef = item.clientRef,
        kind = item.kind,
        sequenceNo = item.sequenceNo,
        recordingRef = item.recordingRef,
        sizeBytes = item.ciphertextBytes,
        ciphertextSha256 = item.ciphertextSha256,
        plaintextSha256 = item.plaintextSha256,
        capturedStartedAt = item.capturedStartedAt,
        capturedEndedAt = item.capturedEndedAt,
        escrowKeyId = escrow?.first,
        escrowWrappedKey = escrow?.second,
        recoveryRkVersion = recovery?.first,
        recoveryWrappedKey = recovery?.second
    )

    // ---- actions on the vault screen --------------------------------------------------------------------

    /**
     * Frees space on the phone once the server holds a verified copy. The item key is kept, so it can come back.
     * The server re-hashes its stored copy FIRST: the phone's copy is only removed when the vault copy is intact right
     * now (a copy that was fine when sealed may have been damaged on the server's disk since).
     */
    suspend fun removeFromPhone(clientRef: String): VaultActionResult {
        val item = owned(clientRef) ?: return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        val serverId = item.serverId
        if (item.cloud != CloudState.SEALED || !item.onPhone || serverId == null) {
            return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        }
        when (val check = repository.verify(serverId)) {
            is ApiResult.Failure -> return VaultActionResult.Failed(check)
            is ApiResult.Success -> {
                update(clientRef) { it.withRemote(check.data.item) }
                if (!check.data.intact) {
                    uploadSignal.trySend(Unit) // repair the vault from this phone's copy
                    return VaultActionResult.LocalProblem(LocalProblemReason.VAULT_COPY_DAMAGED)
                }
            }
        }
        withContext(io) { files.vaultFile(clientRef).delete() }
        update(clientRef) { it.copy(onPhone = false) }
        return VaultActionResult.Done
    }

    /** Brings a verified vault copy back to the phone; the hash is checked before it is accepted. */
    suspend fun downloadToPhone(clientRef: String): VaultActionResult {
        val item = owned(clientRef) ?: return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        val serverId = item.serverId
        if (item.onPhone || item.cloud != CloudState.SEALED || serverId == null) {
            return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        }
        val target = files.downloadFile(clientRef)
        return when (val result = repository.download(serverId, target)) {
            is ApiResult.Failure -> VaultActionResult.Failed(result).also { withContext(io) { target.delete() } }
            is ApiResult.Success -> {
                val matches = withContext(io) { target.inputStream().buffered().use(EvidenceCipher::sha256) } == item.ciphertextSha256
                if (!matches) {
                    withContext(io) { target.delete() }
                    VaultActionResult.LocalProblem(LocalProblemReason.FILE_DAMAGED)
                } else {
                    withContext(io) { EvidenceIndexFile.replace(target, files.vaultFile(clientRef)) }
                    update(clientRef) { it.copy(onPhone = true) }
                    VaultActionResult.Done
                }
            }
        }
    }

    /** Re-hashes the local ciphertext against the hash recorded when it was sealed. */
    suspend fun verifyOnPhone(clientRef: String): Boolean {
        val item = owned(clientRef) ?: return false
        if (!item.onPhone) return false
        return withContext(io) {
            val file = files.vaultFile(clientRef)
            file.exists() && file.inputStream().buffered().use(EvidenceCipher::sha256) == item.ciphertextSha256
        }
    }

    /**
     * "Check integrity": this phone's copy against its recorded hash, and (for a verified item) the server's stored
     * copy re-hashed by the server now. A damaged vault copy is repaired automatically from an intact phone copy.
     */
    suspend fun checkIntegrity(clientRef: String): IntegrityResult? {
        val item = owned(clientRef) ?: return null
        val phone = if (item.onPhone) verifyOnPhone(clientRef) else null
        val serverId = item.serverId
        if (item.cloud != CloudState.SEALED || serverId == null) return IntegrityResult(phone, VaultCopyCheck.NOT_UPLOADED)
        return when (val check = repository.verify(serverId)) {
            is ApiResult.Failure -> IntegrityResult(phone, VaultCopyCheck.UNAVAILABLE, check)
            is ApiResult.Success -> {
                update(clientRef) { it.withRemote(check.data.item) }
                val vault = when {
                    check.data.intact -> VaultCopyCheck.INTACT
                    phone == true -> VaultCopyCheck.DAMAGED_REUPLOADING.also { uploadSignal.trySend(Unit) }
                    else -> VaultCopyCheck.DAMAGED
                }
                IntegrityResult(phone, vault)
            }
        }
    }

    /** "Try uploading again" for an item the server refused earlier (the refusal may not apply any more). */
    suspend fun retryUpload(clientRef: String): VaultActionResult {
        val item = owned(clientRef) ?: return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        if (!item.uploadRefused || !item.onPhone || item.isDeleted) return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        update(clientRef) { it.copy(uploadRefused = false) }
        uploadSignal.trySend(Unit)
        return VaultActionResult.Done
    }

    /**
     * Deletes an item EVERYWHERE. Goes through the server even for items never uploaded (declared first), because the
     * server checks the account password and refuses while the emergency is active: an unlocked phone in someone
     * else's hands is not enough to destroy evidence. Then the item key is destroyed on this phone (crypto-shredding).
     */
    suspend fun deleteEverywhere(clientRef: String, password: String): VaultActionResult {
        var item = owned(clientRef) ?: return VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED)
        if (item.isDeleted) return VaultActionResult.Done
        if (item.serverId == null) {
            when (val declared = repository.declare(item.emergencyId, declarationFor(item, null, null))) {
                is ApiResult.Success -> item = update(clientRef) { it.withRemote(declared.data) } ?: return VaultActionResult.Done
                is ApiResult.Failure -> return VaultActionResult.Failed(declared)
            }
        }
        return when (val result = repository.delete(item.serverId!!, password)) {
            is ApiResult.Success -> {
                shredLocally(clientRef, result.data.deletedAt ?: now())
                VaultActionResult.Done
            }
            is ApiResult.Failure -> VaultActionResult.Failed(result)
        }
    }

    /**
     * Decrypts an item into the private viewing folder for playback. The caller MUST call [closeViewer] when done;
     * leftovers are removed at the next start anyway.
     */
    suspend fun openForViewing(clientRef: String): Result<File> = withContext(io) {
        // Only the person who captured it, even though the Keystore key itself is shared by every account on the phone.
        val item = owned(clientRef) ?: return@withContext Result.failure(IllegalStateException("Unknown item"))
        val wrapped = item.wrappedKey
        if (!item.onPhone || wrapped == null) return@withContext Result.failure(IllegalStateException("Not on this phone"))
        val itemKey = try {
            keyWrapper.unwrap(Base64.getDecoder().decode(wrapped))
        } catch (e: Exception) {
            return@withContext Result.failure(KeyUnavailableException())
        }
        val target = files.viewFile(clientRef, item.kind)
        try {
            files.viewDir.mkdirs()
            files.vaultFile(clientRef).inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> EvidenceCipher.decrypt(itemKey, input, output) }
            }
            Result.success(target)
        } catch (e: CancellationException) {
            PlaintextWipe.wipe(target)
            throw e
        } catch (e: Exception) {
            PlaintextWipe.wipe(target)
            Result.failure(e)
        } finally {
            itemKey.fill(0)
        }
    }

    suspend fun closeViewer(clientRef: String) = withContext(io) {
        val item = find(clientRef) ?: return@withContext
        PlaintextWipe.wipe(files.viewFile(clientRef, item.kind))
    }

    /** The custody report, compared with what THIS phone saw earlier (see [CustodyAnchoring]). */
    suspend fun custody(emergencyId: Long): ApiResult<CustodyCheck> {
        val userId = _currentUserId.value ?: return ApiResult.Failure(ApiError.UNAUTHORIZED)
        val report = when (val result = repository.custody(emergencyId)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        val status = anchorLock.withLock {
            withContext(io) {
                val anchors = readAnchors()
                val previous = anchors.firstOrNull { it.userId == userId && it.emergencyId == emergencyId }
                val outcome = CustodyAnchoring.check(previous, report, userId, emergencyId, now())
                if (outcome.nextAnchor != previous) {
                    val others = anchors.filterNot { it.userId == userId && it.emergencyId == emergencyId }
                    writeAnchors(others + listOfNotNull(outcome.nextAnchor))
                }
                outcome.status
            }
        }
        return ApiResult.Success(CustodyCheck(report, status))
    }

    private fun readAnchors(): List<CustodyAnchor> = try {
        if (!files.custodyAnchorsFile.exists()) emptyList()
        else EvidenceJson.gson.fromJson<List<CustodyAnchor>>(files.custodyAnchorsFile.readText(), anchorListType).orEmpty()
    } catch (e: Exception) {
        emptyList() // a damaged anchor file only means "first check" again; never blocks the vault
    }

    private fun writeAnchors(anchors: List<CustodyAnchor>) {
        files.vaultDir.mkdirs()
        val temp = File(files.vaultDir, "custody-anchors.json.tmp")
        EvidenceIndexFile.writeSynced(temp, EvidenceJson.gson.toJson(anchors, anchorListType).toByteArray(Charsets.UTF_8))
        EvidenceIndexFile.replace(temp, files.custodyAnchorsFile)
    }

    suspend fun remoteItems(emergencyId: Long): ApiResult<List<RemoteEvidence>> = repository.list(emergencyId)

    // ---- index helpers ---------------------------------------------------------------------------------

    private suspend fun shredLocally(clientRef: String, deletedAt: Instant) {
        withContext(io) { files.vaultFile(clientRef).delete() }
        update(clientRef) {
            it.copy(onPhone = false, wrappedKey = null, cloud = CloudState.DELETED, deletedAt = deletedAt, uploadRefused = false)
        }
        // The write above moved the previous index (which still held this item's sealed key) to index.json.bak.
        // Write once more so the backup is the shredded list too: no copy of the key stays on the phone.
        indexLock.withLock { writeIndex(_items.value) }
    }

    private fun find(clientRef: String): VaultItem? = _items.value.firstOrNull { it.clientRef == clientRef }

    /** The item, only if it belongs to the person signed in now. */
    private fun owned(clientRef: String): VaultItem? =
        find(clientRef)?.takeIf { item -> _currentUserId.value.let { it != null && it == item.userId } }

    private suspend fun update(clientRef: String, transform: (VaultItem) -> VaultItem): VaultItem? = indexLock.withLock {
        var changed: VaultItem? = null
        // A deleted item is final: a late upload answer can never bring it back.
        val next = _items.value.map {
            if (it.clientRef == clientRef && !it.isDeleted) transform(it).also { new -> changed = new } else it
        }
        if (changed != null) writeIndex(next)
        changed
    }

    /** Called with [indexLock] held. Writes first, then publishes, so the screen never shows an unsaved state. */
    private suspend fun writeIndex(next: List<VaultItem>) {
        withContext(io) { index.write(next) }
        _items.value = next
    }

    private fun VaultItem.withRemote(remote: RemoteEvidence): VaultItem = copy(
        serverId = remote.id,
        uploadedBytes = remote.receivedBytes,
        escrowed = remote.escrowed,
        recoverable = remote.recoverable,
        cloud = when (remote.status) {
            "SEALED" -> CloudState.SEALED
            "DELETED" -> CloudState.DELETED
            else -> CloudState.UPLOADING
        },
        // Back to UPLOADING (the server found its copy damaged) means "not verified" any more.
        sealedAt = if (remote.status == "UPLOADING") null else remote.sealedAt ?: sealedAt
    )

    class KeyUnavailableException : Exception("The key for this item is not available on this phone")

    private companion object {
        const val DEFAULT_CHUNK = 1024 * 1024
    }
}
