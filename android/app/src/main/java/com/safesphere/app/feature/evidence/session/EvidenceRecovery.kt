package com.safesphere.app.feature.evidence.session

import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.core.crypto.RecoveryKeyCrypto
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.evidence.data.RecoveryKeyStore
import com.safesphere.app.feature.evidence.domain.EvidenceRepository
import com.safesphere.app.feature.evidence.domain.RecoveryEnvelope
import com.safesphere.app.feature.evidence.domain.RecoveryStatus
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Outcome of a recovery action, in words on screen. */
sealed interface RecoveryResult {
    data object Done : RecoveryResult
    /** [restored] items added to this phone's vault; [unreadable] could not be opened with the recovery key. */
    data class Restored(val restored: Int, val unreadable: Int) : RecoveryResult
    /** The passphrase does not open the recovery key (GCM tag check failed). Retried locally, no server call. */
    data object WrongPassphrase : RecoveryResult
    /** Restore / change asked for, but the person never set up a passphrase. */
    data object NotSetUp : RecoveryResult
    /** Set up asked for, but a passphrase already exists (set up on another phone): restore instead. */
    data object AlreadySetUp : RecoveryResult
    /** "Forgot my passphrase" needs a phone that still holds the recovery key; this one does not. */
    data object NoKeyOnPhone : RecoveryResult
    /** The Android Keystore failed (nothing was changed on the server). */
    data object LocalFailure : RecoveryResult
    data class Failed(val failure: ApiResult.Failure) : RecoveryResult
}

/**
 * "Restore my evidence on a new phone" (Phase 5, docs/PHASE5.md §11): the one place that handles the recovery
 * passphrase. The passphrase arrives as a CharArray, is turned into a key (PBKDF2, [iterations]) on [io], and is
 * wiped by the caller; it is never stored, logged or sent. The server only ever receives the wrapped recovery key and
 * the wrapped item keys, which it cannot open.
 *
 *  - [setUp]: a new recovery key (or this phone's existing one), wrapped with the passphrase, stored on the server;
 *    the vault's upload loop then gives every earlier item its recovery copy (back-fill).
 *  - [restore]: the envelope (fetched with the account password) + the passphrase → the recovery key → every vault
 *    item's key, re-sealed by THIS phone's Keystore; content is downloaded only on "Bring back".
 *  - [changePassphrase]: proves the current passphrase against the server's envelope, then re-wraps the same key.
 *  - [replaceForgotten]: a phone that still holds the recovery key sets a new passphrase without the old one
 *    (the vault screen is behind the screen lock and the server re-checks the account password).
 */
class EvidenceRecovery(
    private val io: CoroutineDispatcher,
    private val vault: EvidenceVault,
    private val repository: EvidenceRepository,
    private val store: RecoveryKeyStore,
    private val keyWrapper: LocalKeyWrapper,
    private val iterations: Int = RecoveryKeyCrypto.DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom()
) {

    fun hasKeyOnPhone(userId: Long): Boolean = store.hasKey(userId)

    suspend fun status(): ApiResult<RecoveryStatus> = repository.recoveryStatus()

    /** The envelope, for [restore] or [changePassphrase]; the account password is checked (and counted) by the server. */
    suspend fun fetchEnvelope(password: String): ApiResult<RecoveryEnvelope> = repository.recoveryEnvelope(password)

    suspend fun setUp(userId: Long, passphrase: CharArray, password: String): RecoveryResult {
        val rkVersion = store.rkVersion(userId) ?: 1
        // A key this phone already holds is reused; a NEW key is stored only after the server accepted its envelope,
        // so a refused set-up never leaves this phone wrapping evidence for a key nobody else has.
        val existing = withContext(io) { store.withKey(userId) { _, key -> key.copyOf() } }
        val key = existing ?: RecoveryKeyCrypto.newRecoveryKey(random)
        try {
            val sealed = withContext(io) {
                try {
                    envelopeFor(passphrase, key, rkVersion)
                } catch (e: Exception) {
                    null
                }
            } ?: return RecoveryResult.LocalFailure
            return when (val result = repository.setRecovery(password, null, rkVersion, RecoveryKeyCrypto.KDF, iterations,
                sealed.first, sealed.second)) {
                is ApiResult.Success -> {
                    if (existing == null) {
                        val stored = withContext(io) { runCatching { store.put(userId, rkVersion, key) }.isSuccess }
                        // The envelope is on the server: "Restore" with the passphrase brings the key to this phone.
                        if (!stored) return RecoveryResult.LocalFailure
                    }
                    vault.syncNow() // the upload loop back-fills earlier items
                    RecoveryResult.Done
                }
                is ApiResult.Failure -> conflictOr(result) { RecoveryResult.AlreadySetUp }
            }
        } finally {
            key.fill(0)
        }
    }

    suspend fun restore(userId: Long, envelope: RecoveryEnvelope, passphrase: CharArray): RecoveryResult {
        val rk = openEnvelope(envelope, passphrase) ?: return RecoveryResult.WrongPassphrase
        try {
            val stored = withContext(io) {
                try {
                    store.put(userId, envelope.rkVersion, rk)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!stored) return RecoveryResult.LocalFailure
            var restored = 0
            var unreadable = 0
            var page = 0
            while (true) {
                val result = when (val fetched = repository.recoverableItems(page)) {
                    is ApiResult.Success -> fetched.data
                    is ApiResult.Failure -> return RecoveryResult.Failed(fetched)
                }
                val items = withContext(io) {
                    result.items.mapNotNull { entry ->
                        if (entry.rkVersion != envelope.rkVersion) {
                            unreadable++
                            return@mapNotNull null
                        }
                        val itemKey = try {
                            RecoveryKeyCrypto.openItemKey(rk, entry.wrappedKey, entry.item.clientRef, entry.item.ciphertextSha256)
                        } catch (e: GeneralSecurityException) {
                            unreadable++ // a damaged or swapped copy: never imported
                            return@mapNotNull null
                        }
                        try {
                            RestoredItem(entry.item, Base64.getEncoder().encodeToString(keyWrapper.wrap(itemKey)))
                        } catch (e: GeneralSecurityException) {
                            unreadable++
                            null
                        } finally {
                            itemKey.fill(0)
                        }
                    }
                }
                restored += vault.importRestored(userId, items)
                if (!result.hasMore) break
                page++
            }
            vault.syncNow()
            return RecoveryResult.Restored(restored, unreadable)
        } finally {
            rk.fill(0)
        }
    }

    suspend fun changePassphrase(
        userId: Long,
        envelope: RecoveryEnvelope,
        current: CharArray,
        new: CharArray,
        password: String
    ): RecoveryResult {
        val rk = openEnvelope(envelope, current) ?: return RecoveryResult.WrongPassphrase
        try {
            val sealed = withContext(io) {
                try {
                    if (!store.hasKey(userId)) store.put(userId, envelope.rkVersion, rk)
                    envelopeFor(new, rk, envelope.rkVersion)
                } catch (e: Exception) {
                    null
                }
            } ?: return RecoveryResult.LocalFailure
            return send(password, envelope.envelopeVersion, envelope.rkVersion, sealed)
        } finally {
            rk.fill(0)
        }
    }

    suspend fun replaceForgotten(userId: Long, new: CharArray, password: String): RecoveryResult {
        val status = when (val result = repository.recoveryStatus()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return RecoveryResult.Failed(result)
        }
        val envelopeVersion = status.envelopeVersion
        if (!status.configured || envelopeVersion == null) return RecoveryResult.NotSetUp
        val localVersion = store.rkVersion(userId)
        if (localVersion == null || localVersion != status.rkVersion) return RecoveryResult.NoKeyOnPhone
        val sealed = withContext(io) {
            try {
                store.withKey(userId) { version, rk -> envelopeFor(new, rk, version) }
            } catch (e: Exception) {
                null
            }
        } ?: return RecoveryResult.NoKeyOnPhone
        return send(password, envelopeVersion, localVersion, sealed)
    }

    private suspend fun send(password: String, envelopeVersion: Int, rkVersion: Int, sealed: Pair<ByteArray, ByteArray>) =
        when (val result = repository.setRecovery(password, envelopeVersion, rkVersion, RecoveryKeyCrypto.KDF, iterations,
            sealed.first, sealed.second)) {
            is ApiResult.Success -> RecoveryResult.Done
            is ApiResult.Failure -> RecoveryResult.Failed(result)
        }

    /** (salt, wrapped RK) for [passphrase]. Runs PBKDF2: call on [io]. */
    private fun envelopeFor(passphrase: CharArray, rk: ByteArray, rkVersion: Int): Pair<ByteArray, ByteArray> {
        val salt = RecoveryKeyCrypto.newSalt(random)
        val kek = RecoveryKeyCrypto.deriveKek(passphrase, salt, iterations)
        try {
            return salt to RecoveryKeyCrypto.wrapRecoveryKey(kek, rk, rkVersion, random)
        } finally {
            kek.fill(0)
        }
    }

    /** The recovery key, or null when the passphrase is wrong (or the envelope damaged / of another format). */
    private suspend fun openEnvelope(envelope: RecoveryEnvelope, passphrase: CharArray): ByteArray? = withContext(io) {
        if (envelope.kdf != RecoveryKeyCrypto.KDF || envelope.kdfIterations < 1) return@withContext null
        val kek = RecoveryKeyCrypto.deriveKek(passphrase, envelope.kdfSalt, envelope.kdfIterations)
        try {
            RecoveryKeyCrypto.openRecoveryKey(kek, envelope.wrappedRecoveryKey, envelope.rkVersion)
        } catch (e: GeneralSecurityException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    /** A 409 that is about the password stays a plain failure (its message is shown); any other 409 maps to [other]. */
    private inline fun conflictOr(failure: ApiResult.Failure, other: () -> RecoveryResult): RecoveryResult =
        if (failure.error == ApiError.CONFLICT && failure.message?.contains("password", ignoreCase = true) != true) other()
        else RecoveryResult.Failed(failure)
}
