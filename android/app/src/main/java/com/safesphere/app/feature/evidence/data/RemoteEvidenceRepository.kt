package com.safesphere.app.feature.evidence.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.evidence.data.remote.AttachRecoveryKeyDto
import com.safesphere.app.feature.evidence.data.remote.CustodyReportDto
import com.safesphere.app.feature.evidence.data.remote.RecoveryPasswordDto
import com.safesphere.app.feature.evidence.data.remote.RecoveryStatusDto
import com.safesphere.app.feature.evidence.data.remote.SetRecoveryDto
import com.safesphere.app.feature.evidence.domain.RecoverableEvidence
import com.safesphere.app.feature.evidence.domain.RecoverablePage
import com.safesphere.app.feature.evidence.domain.RecoveryEnvelope
import com.safesphere.app.feature.evidence.domain.RecoveryStatus
import java.util.Base64
import com.safesphere.app.feature.evidence.data.remote.DeclareEvidenceDto
import com.safesphere.app.feature.evidence.data.remote.DeleteEvidenceDto
import com.safesphere.app.feature.evidence.data.remote.EvidenceApi
import com.safesphere.app.feature.evidence.data.remote.EvidenceDto
import com.safesphere.app.feature.evidence.domain.CustodyEntry
import com.safesphere.app.feature.evidence.domain.CustodyReport
import com.safesphere.app.feature.evidence.domain.EvidenceDeclaration
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.EvidenceRepository
import com.safesphere.app.core.crypto.EvidenceCipher
import com.safesphere.app.feature.evidence.domain.RemoteEvidence
import com.safesphere.app.feature.evidence.domain.RemoteVerification
import com.safesphere.app.feature.evidence.domain.VaultKeyInfo
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

class RemoteEvidenceRepository(
    private val api: EvidenceApi,
    private val handler: ApiCallHandler
) : EvidenceRepository {

    override suspend fun vaultKey(): ApiResult<VaultKeyInfo> = handler.execute {
        api.vaultKey().let { VaultKeyInfo(it.available, it.keyId, it.publicKey) }
    }

    override suspend fun declare(emergencyId: Long, declaration: EvidenceDeclaration): ApiResult<RemoteEvidence> =
        handler.execute {
            api.declare(emergencyId, DeclareEvidenceDto(
                clientRef = declaration.clientRef,
                kind = declaration.kind.name,
                mediaType = declaration.kind.mediaType,
                sequenceNo = declaration.sequenceNo,
                recordingRef = declaration.recordingRef,
                sizeBytes = declaration.sizeBytes,
                ciphertextSha256 = declaration.ciphertextSha256,
                plaintextSha256 = declaration.plaintextSha256,
                encryption = EvidenceCipher.FORMAT,
                capturedStartedAt = declaration.capturedStartedAt.toString(),
                capturedEndedAt = declaration.capturedEndedAt.toString(),
                escrowKeyId = declaration.escrowKeyId,
                escrowWrappedKey = declaration.escrowWrappedKey,
                recoveryRkVersion = declaration.recoveryRkVersion,
                recoveryWrappedKey = declaration.recoveryWrappedKey
            )).toDomain()
        }

    override suspend fun get(evidenceId: Long): ApiResult<RemoteEvidence> = handler.execute { api.get(evidenceId).toDomain() }

    override suspend fun list(emergencyId: Long): ApiResult<List<RemoteEvidence>> =
        handler.execute { api.list(emergencyId).map { it.toDomain() } }

    override suspend fun uploadPiece(evidenceId: Long, file: File, offset: Long, length: Long): ApiResult<RemoteEvidence> =
        handler.execute { api.upload(evidenceId, offset, FileRangeBody(file, offset, length)).toDomain() }

    override suspend fun seal(evidenceId: Long): ApiResult<RemoteEvidence> = handler.execute { api.seal(evidenceId).toDomain() }

    override suspend fun verify(evidenceId: Long): ApiResult<RemoteVerification> = handler.execute {
        api.verify(evidenceId).let { RemoteVerification(it.intact, Instant.parse(it.verifiedAt), it.item.toDomain()) }
    }

    override suspend fun download(evidenceId: Long, target: File): ApiResult<Unit> = handler.execute {
        val body = api.download(evidenceId)
        withContext(Dispatchers.IO) {
            body.use { response ->
                FileOutputStream(target).use { out ->
                    response.byteStream().copyTo(out)
                    out.fd.sync() // the hash is checked next; then this file replaces nothing until it is complete
                }
            }
        }
    }

    override suspend fun delete(evidenceId: Long, password: String): ApiResult<RemoteEvidence> =
        handler.execute { api.delete(evidenceId, DeleteEvidenceDto(password)).toDomain() }

    override suspend fun custody(emergencyId: Long): ApiResult<CustodyReport> = handler.execute { api.custody(emergencyId).toDomain() }

    override suspend fun recoveryStatus(): ApiResult<RecoveryStatus> = handler.execute { api.recoveryStatus().toDomain() }

    override suspend fun setRecovery(
        password: String,
        expectedEnvelopeVersion: Int?,
        rkVersion: Int,
        kdf: String,
        kdfIterations: Int,
        kdfSalt: ByteArray,
        wrappedRecoveryKey: ByteArray
    ): ApiResult<RecoveryStatus> = handler.execute {
        api.setRecovery(SetRecoveryDto(password, expectedEnvelopeVersion, rkVersion, kdf, kdfIterations,
            b64.encodeToString(kdfSalt), b64.encodeToString(wrappedRecoveryKey))).toDomain()
    }

    override suspend fun recoveryEnvelope(password: String): ApiResult<RecoveryEnvelope> = handler.execute {
        api.recoveryEnvelope(RecoveryPasswordDto(password)).let {
            RecoveryEnvelope(it.rkVersion, it.envelopeVersion, it.kdf, it.kdfIterations,
                Base64.getDecoder().decode(it.kdfSalt), Base64.getDecoder().decode(it.wrappedRecoveryKey))
        }
    }

    override suspend fun recoverableItems(page: Int): ApiResult<RecoverablePage> = handler.execute {
        api.recoverableItems(page).let { dto ->
            RecoverablePage(
                items = dto.items.orEmpty().map {
                    RecoverableEvidence(it.item.toDomain(), it.recoveryRkVersion, Base64.getDecoder().decode(it.recoveryWrappedKey))
                },
                hasMore = dto.hasMore
            )
        }
    }

    override suspend fun attachRecoveryKey(evidenceId: Long, rkVersion: Int, wrappedKey: ByteArray): ApiResult<RemoteEvidence> =
        handler.execute { api.attachRecoveryKey(evidenceId, AttachRecoveryKeyDto(rkVersion, b64.encodeToString(wrappedKey))).toDomain() }

    /** Streams [length] bytes of [file] from [offset]; never loads the whole file. */
    private class FileRangeBody(private val file: File, private val offset: Long, private val length: Long) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val b64: Base64.Encoder = Base64.getEncoder()
    }
}

internal fun RecoveryStatusDto.toDomain() = RecoveryStatus(
    configured = configured,
    rkVersion = rkVersion,
    envelopeVersion = envelopeVersion,
    updatedAt = updatedAt?.let(Instant::parse),
    lastDownloadedAt = lastDownloadedAt?.let(Instant::parse)
)

internal fun EvidenceDto.toDomain() = RemoteEvidence(
    id = id,
    clientRef = clientRef,
    emergencyId = emergencyId,
    kind = kind?.let { value -> EvidenceKind.entries.firstOrNull { it.name == value } },
    status = status,
    sizeBytes = sizeBytes,
    receivedBytes = receivedBytes,
    ciphertextSha256 = ciphertextSha256,
    escrowed = escrowed,
    capturedStartedAt = Instant.parse(capturedStartedAt),
    capturedEndedAt = Instant.parse(capturedEndedAt),
    sealedAt = sealedAt?.let(Instant::parse),
    deletedAt = deletedAt?.let(Instant::parse),
    uploadChunkBytes = uploadChunkBytes,
    sequenceNo = sequenceNo,
    recordingRef = recordingRef,
    plaintextSha256 = plaintextSha256,
    recoverable = recoverable
)

internal fun CustodyReportDto.toDomain() = CustodyReport(
    chainIntact = chainIntact,
    chainLength = chainLength,
    latestEntryHash = latestEntryHash,
    verifiedAt = Instant.parse(verifiedAt),
    entries = entries.orEmpty().map { CustodyEntry(it.sequenceNo, it.evidenceId, it.action, Instant.parse(it.occurredAt), it.entryHash) }
)
