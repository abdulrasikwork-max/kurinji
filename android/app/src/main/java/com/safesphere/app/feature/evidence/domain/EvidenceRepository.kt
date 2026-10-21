package com.safesphere.app.feature.evidence.domain

import com.safesphere.app.core.network.ApiResult
import java.io.File

/**
 * The Evidence Vault API (Phase 5). Every call carries the person's JWT; another person's item answers NOT_FOUND.
 * The bytes sent are always ciphertext produced on this phone.
 */
interface EvidenceRepository {
    suspend fun vaultKey(): ApiResult<VaultKeyInfo>
    suspend fun declare(emergencyId: Long, declaration: EvidenceDeclaration): ApiResult<RemoteEvidence>
    suspend fun get(evidenceId: Long): ApiResult<RemoteEvidence>
    suspend fun list(emergencyId: Long): ApiResult<List<RemoteEvidence>>

    /** Sends [length] bytes of [file] starting at [offset]. */
    suspend fun uploadPiece(evidenceId: Long, file: File, offset: Long, length: Long): ApiResult<RemoteEvidence>
    suspend fun seal(evidenceId: Long): ApiResult<RemoteEvidence>

    /** Asks the server to re-hash its STORED copy of a sealed item now. */
    suspend fun verify(evidenceId: Long): ApiResult<RemoteVerification>

    /** Writes the sealed ciphertext into [target]. */
    suspend fun download(evidenceId: Long, target: File): ApiResult<Unit>
    suspend fun delete(evidenceId: Long, password: String): ApiResult<RemoteEvidence>
    suspend fun custody(emergencyId: Long): ApiResult<CustodyReport>

    // ---- restore on a new phone (V11). The passphrase is never an argument here: it never leaves the phone. ----

    suspend fun recoveryStatus(): ApiResult<RecoveryStatus>

    /** Creates ([expectedEnvelopeVersion] null) or replaces the envelope; the account password is re-checked. */
    suspend fun setRecovery(
        password: String,
        expectedEnvelopeVersion: Int?,
        rkVersion: Int,
        kdf: String,
        kdfIterations: Int,
        kdfSalt: ByteArray,
        wrappedRecoveryKey: ByteArray
    ): ApiResult<RecoveryStatus>

    suspend fun recoveryEnvelope(password: String): ApiResult<RecoveryEnvelope>
    suspend fun recoverableItems(page: Int): ApiResult<RecoverablePage>
    suspend fun attachRecoveryKey(evidenceId: Long, rkVersion: Int, wrappedKey: ByteArray): ApiResult<RemoteEvidence>
}
