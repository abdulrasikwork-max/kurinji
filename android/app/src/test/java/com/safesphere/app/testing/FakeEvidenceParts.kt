package com.safesphere.app.testing

import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.evidence.capture.AudioSegmentWriter
import com.safesphere.app.feature.evidence.domain.CustodyReport
import com.safesphere.app.feature.evidence.domain.EvidenceDeclaration
import com.safesphere.app.feature.evidence.domain.RemoteEvidence
import com.safesphere.app.feature.evidence.domain.RemoteVerification
import com.safesphere.app.feature.evidence.domain.EvidenceRepository
import com.safesphere.app.feature.evidence.domain.RecoverableEvidence
import com.safesphere.app.feature.evidence.domain.RecoverablePage
import com.safesphere.app.feature.evidence.domain.RecoveryEnvelope
import com.safesphere.app.feature.evidence.domain.RecoveryStatus
import com.safesphere.app.feature.evidence.domain.VaultKeyInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software stand-in for the Android Keystore (AES-GCM with a key that exists only in this test). */
class FakeKeyWrapper(var available: Boolean = true) : LocalKeyWrapper {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    override fun wrap(itemKey: ByteArray): ByteArray {
        if (!available) throw GeneralSecurityException("Keystore unavailable")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(itemKey)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (!available) throw GeneralSecurityException("Keystore unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

/**
 * A small in-memory Evidence Vault server with the real rules that matter to the phone: idempotent declaration,
 * offsets that must continue at receivedBytes, hash verification at seal, password-checked deletion.
 */
class FakeEvidenceRepository : EvidenceRepository {

    class Stored(val declaration: EvidenceDeclaration, val emergencyId: Long, val id: Long) {
        val bytes = ByteArrayOutputStream()
        var status = "UPLOADING"
        var sealedAt: Instant? = null
        var deletedAt: Instant? = null
    }

    var vaultKey: ApiResult<VaultKeyInfo> = ApiResult.Success(VaultKeyInfo(false, null, null))
    var chunkBytes = 1024
    var password = "correct-horse"
    var emergencyActive = false
    /** Next N calls of any kind fail with this error (then normal service resumes). */
    var failNext: ApiResult.Failure? = null
    var failCount = 0
    var declareFailure: ApiResult.Failure? = null
    /** Flip one byte of what arrives (simulates corruption in transit). */
    var corruptNextUpload = false

    val items = linkedMapOf<Long, Stored>()
    val calls = mutableListOf<String>()
    private var nextId = 100L

    private fun failing(): ApiResult.Failure? {
        val failure = failNext ?: return null
        if (failCount <= 0) return null
        failCount--
        if (failCount == 0) failNext = null
        return failure
    }

    override suspend fun vaultKey(): ApiResult<VaultKeyInfo> {
        calls += "vaultKey"
        return failing() ?: vaultKey
    }

    override suspend fun declare(emergencyId: Long, declaration: EvidenceDeclaration): ApiResult<RemoteEvidence> {
        calls += "declare"
        failing()?.let { return it }
        declareFailure?.let { return it }
        val known = items.values.firstOrNull { it.declaration.clientRef == declaration.clientRef }
        val recovery = declaration.recoveryWrappedKey
        if (known == null && recovery != null) {
            val e = envelope ?: return ApiResult.Failure(ApiError.CONFLICT, "No recovery passphrase is set up")
            if (e.rkVersion != declaration.recoveryRkVersion) return ApiResult.Failure(ApiError.CONFLICT, "The recovery key has changed; fetch it again")
        }
        val stored = known ?: Stored(declaration, emergencyId, nextId++).also { items[it.id] = it }
        if (known == null && recovery != null) {
            recoveryWraps[stored.id] = declaration.recoveryRkVersion!! to java.util.Base64.getDecoder().decode(recovery)
        }
        return ApiResult.Success(stored.remote())
    }

    override suspend fun get(evidenceId: Long): ApiResult<RemoteEvidence> {
        calls += "get"
        failing()?.let { return it }
        return items[evidenceId]?.let { ApiResult.Success(it.remote()) } ?: ApiResult.Failure(ApiError.NOT_FOUND)
    }

    override suspend fun list(emergencyId: Long): ApiResult<List<RemoteEvidence>> =
        ApiResult.Success(items.values.filter { it.emergencyId == emergencyId }.map { it.remote() })

    override suspend fun uploadPiece(evidenceId: Long, file: File, offset: Long, length: Long): ApiResult<RemoteEvidence> {
        calls += "upload"
        failing()?.let { return it }
        val stored = items[evidenceId] ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        if (offset != stored.bytes.size().toLong()) return ApiResult.Failure(ApiError.CONFLICT, "Upload must continue at byte ${stored.bytes.size()}")
        if (length > chunkBytes) return ApiResult.Failure(ApiError.VALIDATION)
        val piece = ByteArray(length.toInt())
        RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(piece) }
        if (corruptNextUpload) {
            piece[0] = (piece[0].toInt() xor 1).toByte()
            corruptNextUpload = false
        }
        stored.bytes.write(piece)
        return ApiResult.Success(stored.remote())
    }

    override suspend fun seal(evidenceId: Long): ApiResult<RemoteEvidence> {
        calls += "seal"
        failing()?.let { return it }
        val stored = items[evidenceId] ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        val hash = MessageDigest.getInstance("SHA-256").digest(stored.bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        if (hash != stored.declaration.ciphertextSha256) {
            stored.bytes.reset()
            return ApiResult.Failure(ApiError.CONFLICT, "The uploaded bytes do not match the evidence hash; upload it again")
        }
        stored.status = "SEALED"
        stored.sealedAt = Instant.parse("2026-10-20T10:00:00Z")
        return ApiResult.Success(stored.remote())
    }

    override suspend fun verify(evidenceId: Long): ApiResult<RemoteVerification> {
        calls += "verify"
        failing()?.let { return it }
        val stored = items[evidenceId] ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        if (stored.status != "SEALED") return ApiResult.Failure(ApiError.CONFLICT, "This evidence is not sealed yet")
        val intact = sha256Hex(stored.bytes.toByteArray()) == stored.declaration.ciphertextSha256
        if (!intact) { // like the server: discard, back to UPLOADING
            stored.bytes.reset()
            stored.status = "UPLOADING"
            stored.sealedAt = null
        }
        return ApiResult.Success(RemoteVerification(intact, Instant.parse("2026-10-20T10:30:00Z"), stored.remote()))
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override suspend fun download(evidenceId: Long, target: File): ApiResult<Unit> {
        val stored = items[evidenceId] ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        target.writeBytes(stored.bytes.toByteArray())
        return ApiResult.Success(Unit)
    }

    override suspend fun delete(evidenceId: Long, password: String): ApiResult<RemoteEvidence> {
        calls += "delete"
        val stored = items[evidenceId] ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        if (emergencyActive) return ApiResult.Failure(ApiError.CONFLICT, "Evidence cannot be deleted while the emergency is active")
        if (password != this.password) return ApiResult.Failure(ApiError.CONFLICT, "Password is incorrect")
        stored.status = "DELETED"
        recoveryWraps.remove(evidenceId) // crypto-shredding of the recovery copy, like the server
        stored.deletedAt = Instant.parse("2026-10-20T11:00:00Z")
        stored.bytes.reset()
        return ApiResult.Success(stored.remote())
    }

    /** What GET .../custody answers; null = a simple intact report without entries. */
    var custodyReport: ApiResult<CustodyReport>? = null

    override suspend fun custody(emergencyId: Long): ApiResult<CustodyReport> = custodyReport
        ?: ApiResult.Success(CustodyReport(true, items.size.toLong(), null, Instant.parse("2026-10-20T12:00:00Z"), emptyList()))

    // ---- restore on a new phone (V11): the server only stores opaque bytes, like the real one ----

    var envelope: RecoveryEnvelope? = null
    var envelopeUpdatedAt: Instant? = null
    var envelopeFetchedAt: Instant? = null
    var recoveryPageSize = 200
    val recoveryWraps = mutableMapOf<Long, Pair<Int, ByteArray>>()

    override suspend fun recoveryStatus(): ApiResult<RecoveryStatus> {
        calls += "recoveryStatus"
        failing()?.let { return it }
        val e = envelope
        return ApiResult.Success(RecoveryStatus(e != null, e?.rkVersion, e?.envelopeVersion, envelopeUpdatedAt, envelopeFetchedAt))
    }

    override suspend fun setRecovery(
        password: String, expectedEnvelopeVersion: Int?, rkVersion: Int, kdf: String, kdfIterations: Int,
        kdfSalt: ByteArray, wrappedRecoveryKey: ByteArray
    ): ApiResult<RecoveryStatus> {
        calls += "setRecovery"
        failing()?.let { return it }
        if (password != this.password) return ApiResult.Failure(ApiError.CONFLICT, "Password is incorrect")
        val current = envelope
        if (current == null && expectedEnvelopeVersion != null) return ApiResult.Failure(ApiError.CONFLICT, "No recovery passphrase is set up")
        if (current != null && expectedEnvelopeVersion != current.envelopeVersion) {
            return ApiResult.Failure(ApiError.CONFLICT, "The recovery passphrase was changed elsewhere; check it again")
        }
        if (current != null && rkVersion != current.rkVersion) return ApiResult.Failure(ApiError.VALIDATION)
        envelope = RecoveryEnvelope(rkVersion, (current?.envelopeVersion ?: 0) + 1, kdf, kdfIterations, kdfSalt, wrappedRecoveryKey)
        envelopeUpdatedAt = Instant.parse("2026-10-20T13:00:00Z")
        return recoveryStatus()
    }

    override suspend fun recoveryEnvelope(password: String): ApiResult<RecoveryEnvelope> {
        calls += "recoveryEnvelope"
        failing()?.let { return it }
        if (password != this.password) return ApiResult.Failure(ApiError.CONFLICT, "Password is incorrect")
        val e = envelope ?: return ApiResult.Failure(ApiError.NOT_FOUND, "No recovery passphrase is set up")
        envelopeFetchedAt = Instant.parse("2026-10-20T14:00:00Z")
        return ApiResult.Success(e)
    }

    override suspend fun recoverableItems(page: Int): ApiResult<RecoverablePage> {
        calls += "recoverableItems"
        failing()?.let { return it }
        val all = items.values.filter { it.status == "SEALED" && recoveryWraps.containsKey(it.id) }
        val slice = all.drop(page * recoveryPageSize).take(recoveryPageSize)
        return ApiResult.Success(RecoverablePage(
            slice.map { RecoverableEvidence(it.remote(), recoveryWraps.getValue(it.id).first, recoveryWraps.getValue(it.id).second) },
            hasMore = all.size > (page + 1) * recoveryPageSize
        ))
    }

    override suspend fun attachRecoveryKey(evidenceId: Long, rkVersion: Int, wrappedKey: ByteArray): ApiResult<RemoteEvidence> {
        calls += "attach"
        failing()?.let { return it }
        val stored = items[evidenceId] ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        if (stored.status == "DELETED") return ApiResult.Failure(ApiError.CONFLICT, "This evidence was deleted")
        val e = envelope ?: return ApiResult.Failure(ApiError.CONFLICT, "No recovery passphrase is set up")
        if (e.rkVersion != rkVersion) return ApiResult.Failure(ApiError.CONFLICT, "The recovery key has changed; fetch it again")
        if (recoveryWraps[evidenceId]?.first != rkVersion) recoveryWraps[evidenceId] = rkVersion to wrappedKey
        return ApiResult.Success(stored.remote())
    }

    private fun Stored.remote() = RemoteEvidence(
        id = id,
        clientRef = declaration.clientRef,
        emergencyId = emergencyId,
        kind = declaration.kind,
        status = status,
        sizeBytes = declaration.sizeBytes,
        receivedBytes = bytes.size().toLong(),
        ciphertextSha256 = declaration.ciphertextSha256,
        escrowed = declaration.escrowWrappedKey != null,
        capturedStartedAt = declaration.capturedStartedAt,
        capturedEndedAt = declaration.capturedEndedAt,
        sealedAt = sealedAt,
        deletedAt = deletedAt,
        uploadChunkBytes = chunkBytes,
        sequenceNo = declaration.sequenceNo,
        recordingRef = declaration.recordingRef,
        plaintextSha256 = declaration.plaintextSha256,
        recoverable = recoveryWraps.containsKey(id)
    )
}

/** Writes recognisable bytes into each audio part instead of using a microphone. */
class FakeAudioWriter : AudioSegmentWriter {
    var failStart = false
    var current: File? = null
    var onError: (() -> Unit)? = null
    val started = mutableListOf<File>()
    var stops = 0

    override fun start(file: File, onError: () -> Unit) {
        if (failStart) throw IllegalStateException("microphone busy")
        current = file
        this.onError = onError
        started += file
    }

    override fun stop(): Boolean {
        val file = current ?: return false
        current = null
        stops++
        file.writeBytes("audio part ${started.size}".toByteArray())
        return true
    }
}
