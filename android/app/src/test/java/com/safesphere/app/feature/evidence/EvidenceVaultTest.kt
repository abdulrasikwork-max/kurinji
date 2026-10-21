package com.safesphere.app.feature.evidence

import com.safesphere.app.core.crypto.EvidenceCipher
import com.safesphere.app.core.crypto.toHex
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.data.EvidenceJson
import com.safesphere.app.feature.evidence.data.PendingCapture
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.VaultItem
import com.safesphere.app.feature.evidence.domain.VaultKeyInfo
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.LocalProblemReason
import com.safesphere.app.feature.evidence.session.VaultActionResult
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEvidenceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 5: the phone's Evidence Vault end to end against an in-memory server: seal (plaintext gone, ciphertext + key
 * kept), crash recovery, resumable upload with server-side hash verification, offline waiting, refusals that keep the
 * evidence, escrow, deletion with the password, viewing, and account separation. Virtual time only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceVaultTest {

    @get:Rule val folder = TemporaryFolder()

    private val start = Instant.parse("2026-10-20T09:00:00Z")
    private val repository = FakeEvidenceRepository()
    private val connectivity = FakeConnectivityMonitor(online = true)
    private val keyWrapper = FakeKeyWrapper()
    private val session = MutableStateFlow<AuthSession?>(AuthSession("token", AuthUser(USER, "Asha", "asha@example.com")))
    private val files by lazy { EvidenceFiles(folder.root.resolve("files"), folder.root.resolve("cache")) }

    private fun TestScope.vault(pinnedKeyId: String? = null) = EvidenceVault(
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        files = files,
        keyWrapper = keyWrapper,
        repository = repository,
        connectivityMonitor = connectivity,
        session = session,
        pinnedVaultKeyId = pinnedKeyId,
        now = { start.plusMillis(testScheduler.currentTime) },
        retryDelaysMillis = listOf(1_000, 2_000)
    )

    private suspend fun EvidenceVault.capture(bytes: ByteArray, emergency: Long = EMERGENCY, user: Long = USER, kind: EvidenceKind = EvidenceKind.AUDIO): VaultItem? {
        val (pending, file) = prepareCapture(user, emergency, kind)
        file.writeBytes(bytes)
        return completeCapture(pending)
    }

    // ---- sealing --------------------------------------------------------------------------------------

    @Test
    fun `sealing encrypts at once, deletes the plaintext and records hashes and the protected key`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000 // keep it on the phone for this test
        val vault = vault()
        runCurrent()
        val plaintext = "help me, he is outside the door".toByteArray()

        val item = vault.capture(plaintext)!!

        assertTrue(files.captureDir.listFiles()!!.isEmpty())
        val ciphertext = files.vaultFile(item.clientRef).readBytes()
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("outside the door"))
        assertEquals(sha256(plaintext), item.plaintextSha256)
        assertEquals(sha256(ciphertext), item.ciphertextSha256)
        assertEquals(ciphertext.size.toLong(), item.ciphertextBytes)
        assertNotNull(item.wrappedKey)
        assertTrue(item.onPhone)
        // The index on disk holds the same, and no plaintext.
        assertFalse(files.indexFile.readText().contains("outside the door"))
        assertTrue(files.indexFile.readText().contains(item.ciphertextSha256))
    }

    @Test
    fun `sequence numbers count up per emergency`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000
        val vault = vault()
        runCurrent()

        val first = vault.capture(byteArrayOf(1))!!
        val second = vault.capture(byteArrayOf(2))!!
        val other = vault.capture(byteArrayOf(3), emergency = 9)!!

        assertEquals(listOf(0, 1, 0), listOf(first.sequenceNo, second.sequenceNo, other.sequenceNo))
    }

    @Test
    fun `an empty capture (camera cancelled) is dropped, not sealed`() = runTest {
        val vault = vault()
        runCurrent()
        val (pending, _) = vault.prepareCapture(USER, EMERGENCY, EvidenceKind.PHOTO)

        assertNull(vault.completeCapture(pending))
        assertTrue(vault.items.value.isEmpty())
        assertTrue(files.captureDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a capture interrupted by a crash is sealed on the next start, and leftovers are cleaned`() = runTest {
        files.ensureDirs()
        // What a killed process leaves behind: a staged audio part with its note, a note without media, media
        // without a note, and a decrypted file from a viewer.
        val pending = PendingCapture("11111111-1111-1111-1111-111111111111", USER, EMERGENCY, EvidenceKind.AUDIO, null, start)
        files.captureFile(pending.clientRef, EvidenceKind.AUDIO).writeBytes("half a recording".toByteArray())
        files.captureNote(pending.clientRef).writeText(EvidenceJson.gson.toJson(pending))
        val empty = pending.copy(clientRef = "22222222-2222-2222-2222-222222222222")
        files.captureNote(empty.clientRef).writeText(EvidenceJson.gson.toJson(empty))
        files.captureFile("33333333-3333-3333-3333-333333333333", EvidenceKind.PHOTO).writeBytes(byteArrayOf(9))
        files.viewFile(pending.clientRef, EvidenceKind.AUDIO).writeBytes("decrypted".toByteArray())
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000

        val vault = vault()
        runCurrent()

        val recovered = vault.items.value.single()
        assertEquals(pending.clientRef, recovered.clientRef)
        assertEquals(sha256("half a recording".toByteArray()), recovered.plaintextSha256)
        assertTrue(files.captureDir.listFiles()!!.isEmpty())
        assertTrue(files.viewDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `the index survives a restart`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000
        val first = vault()
        runCurrent()
        val item = first.capture(byteArrayOf(1, 2, 3))!!

        val second = vault()
        runCurrent()

        assertEquals(item.ciphertextSha256, second.items.value.single { it.clientRef == item.clientRef }.ciphertextSha256)
    }

    // ---- upload -------------------------------------------------------------------------------------------

    @Test
    fun `uploads in pieces, the server verifies the hash, and the item becomes SEALED`() = runTest {
        repository.chunkBytes = 100
        val vault = vault()
        runCurrent()
        val item = vault.capture(ByteArray(1_000) { it.toByte() })!!
        runCurrent()

        val sealed = vault.items.value.single()
        assertEquals(CloudState.SEALED, sealed.cloud)
        assertEquals(sealed.ciphertextBytes, sealed.uploadedBytes)
        val stored = repository.items.values.single()
        assertArrayEquals(files.vaultFile(item.clientRef).readBytes(), stored.bytes.toByteArray())
        assertTrue(repository.calls.count { it == "upload" } >= 10)
    }

    @Test
    fun `offline, evidence waits safely on the phone and goes up when the connection returns`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 3
        val vault = vault()
        runCurrent()
        vault.capture("words".toByteArray())
        runCurrent()

        assertEquals(CloudState.NOT_UPLOADED, vault.items.value.single().cloud)
        assertTrue(vault.items.value.single().onPhone)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
    }

    @Test
    fun `a lost answer is resumed from where the server is, not from zero`() = runTest {
        repository.chunkBytes = 100
        val vault = vault()
        runCurrent()
        session.value = null // pause uploads
        runCurrent()
        val item = vault.capture(ByteArray(450) { 7 })!!
        // The server already has the first 200 bytes (an earlier attempt whose answers were lost).
        val ciphertext = files.vaultFile(item.clientRef).readBytes()
        repository.declare(EMERGENCY, com.safesphere.app.feature.evidence.domain.EvidenceDeclaration(
            item.clientRef, item.kind, item.sequenceNo, null, item.ciphertextBytes, item.ciphertextSha256,
            item.plaintextSha256, item.capturedStartedAt, item.capturedEndedAt, null, null))
        repository.items.values.single().bytes.write(ciphertext, 0, 200)

        session.value = AuthSession("token", AuthUser(USER, "Asha", "asha@example.com"))
        runCurrent()

        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
        assertArrayEquals(ciphertext, repository.items.values.single().bytes.toByteArray())
    }

    @Test
    fun `corruption in transit is caught by the server hash and the upload starts over`() = runTest {
        repository.corruptNextUpload = true
        val vault = vault()
        runCurrent()
        vault.capture(ByteArray(300) { 3 })
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
        assertEquals(2, repository.calls.count { it == "seal" })
    }

    @Test
    fun `a file damaged on the phone is kept and no longer retried`() = runTest {
        val vault = vault()
        runCurrent()
        session.value = null
        runCurrent()
        val item = vault.capture(ByteArray(300) { 3 })!!
        val file = files.vaultFile(item.clientRef)
        file.writeBytes(file.readBytes().also { it[50] = (it[50].toInt() xor 1).toByte() })

        session.value = AuthSession("token", AuthUser(USER, "Asha", "asha@example.com"))
        runCurrent()

        val kept = vault.items.value.single()
        assertTrue(kept.uploadRefused)
        assertTrue(kept.onPhone)
        assertTrue(file.exists())
        assertFalse(vault.verifyOnPhone(item.clientRef))
    }

    @Test
    fun `a refusal by the server keeps the evidence on the phone and stops retrying it`() = runTest {
        repository.declareFailure = ApiResult.Failure(ApiError.VALIDATION)
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(1, 2))!!
        runCurrent()

        val kept = vault.items.value.single()
        assertTrue(kept.uploadRefused)
        assertTrue(files.vaultFile(item.clientRef).exists())
        val declares = repository.calls.count { it == "declare" }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(declares, repository.calls.count { it == "declare" })
    }

    @Test
    fun `with a vault key the item key is escrowed, and the offline private key recovers the evidence`() = runTest {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val keyId = sha256(pair.public.encoded)
        repository.vaultKey = ApiResult.Success(VaultKeyInfo(true, keyId, Base64.getEncoder().encodeToString(pair.public.encoded)))
        val vault = vault()
        runCurrent()
        val plaintext = "recoverable without the phone".toByteArray()
        vault.capture(plaintext)
        runCurrent()

        val stored = repository.items.values.single()
        assertEquals(keyId, stored.declaration.escrowKeyId)
        assertTrue(vault.items.value.single().escrowed)
        val itemKey = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, pair.private, com.safesphere.app.core.crypto.EscrowKey.OAEP)
        }.doFinal(Base64.getDecoder().decode(stored.declaration.escrowWrappedKey))
        val out = ByteArrayOutputStream()
        EvidenceCipher.decrypt(itemKey, ByteArrayInputStream(stored.bytes.toByteArray()), out)
        assertArrayEquals(plaintext, out.toByteArray())
    }

    @Test
    fun `a vault key that is not the pinned one is not used`() = runTest {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        repository.vaultKey = ApiResult.Success(VaultKeyInfo(true, sha256(pair.public.encoded), Base64.getEncoder().encodeToString(pair.public.encoded)))
        val vault = vault(pinnedKeyId = "a".repeat(64))
        runCurrent()
        vault.capture(byteArrayOf(5))
        runCurrent()

        assertNull(repository.items.values.single().declaration.escrowKeyId)
        assertEquals(false, vault.sync.value.vaultKeyAvailable)
    }

    @Test
    fun `nothing is declared while the vault key question cannot be answered`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1
        val vault = vault()
        runCurrent()
        vault.capture(byteArrayOf(5))
        runCurrent()

        assertEquals(listOf("vaultKey"), repository.calls)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
    }

    // ---- accounts -------------------------------------------------------------------------------------------

    @Test
    fun `another account on the same phone neither sees nor uploads the first person's evidence`() = runTest {
        session.value = null
        val vault = vault()
        runCurrent()
        vault.capture(byteArrayOf(1), user = USER)
        session.value = AuthSession("token-b", AuthUser(OTHER_USER, "Bina", "bina@example.com"))
        runCurrent()

        assertTrue(vault.itemsFor(OTHER_USER).isEmpty())
        assertTrue(repository.items.isEmpty())
        assertEquals(1, vault.itemsFor(USER).size)
    }

    // ---- owner actions ------------------------------------------------------------------------------------------

    @Test
    fun `delete everywhere needs the password, is refused during the emergency, then shreds the key`() = runTest {
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(1, 2, 3))!!
        runCurrent()

        repository.emergencyActive = true
        assertTrue(vault.deleteEverywhere(item.clientRef, "correct-horse") is VaultActionResult.Failed)
        repository.emergencyActive = false
        val wrong = vault.deleteEverywhere(item.clientRef, "guess") as VaultActionResult.Failed
        assertEquals("Password is incorrect", wrong.failure.message)
        assertTrue(files.vaultFile(item.clientRef).exists())

        assertEquals(VaultActionResult.Done, vault.deleteEverywhere(item.clientRef, "correct-horse"))
        val tombstone = vault.items.value.single()
        assertEquals(CloudState.DELETED, tombstone.cloud)
        assertNull(tombstone.wrappedKey)
        assertFalse(files.vaultFile(item.clientRef).exists())
        assertFalse(files.indexFile.readText().contains("\"wrappedKey\":\""))
        // Nor in the crash-safety backup of the index.
        assertFalse(files.indexBackupFile.readText().contains("\"wrappedKey\":\""))
    }

    @Test
    fun `an item never uploaded is declared first, so deletion is still password-checked by the server`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1 // the upload loop's first step fails: the item is never uploaded
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(1))!!
        runCurrent()
        assertEquals(listOf("vaultKey"), repository.calls)

        assertEquals(VaultActionResult.Done, vault.deleteEverywhere(item.clientRef, "correct-horse"))
        assertEquals(listOf("vaultKey", "declare", "delete"), repository.calls)
    }

    @Test
    fun `while signed out, evidence cannot be deleted from this phone`() = runTest {
        session.value = null
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(1))!!

        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED), vault.deleteEverywhere(item.clientRef, "correct-horse"))
        assertTrue(repository.calls.isEmpty())
        assertTrue(files.vaultFile(item.clientRef).exists())
    }

    @Test
    fun `remove from phone only after the server verified it, and bring back checks the hash`() = runTest {
        val vault = vault()
        runCurrent()
        session.value = null
        runCurrent()
        val item = vault.capture(byteArrayOf(4, 5, 6))!!
        assertTrue(vault.removeFromPhone(item.clientRef) is VaultActionResult.LocalProblem)

        session.value = AuthSession("token", AuthUser(USER, "Asha", "asha@example.com"))
        runCurrent()
        assertEquals(VaultActionResult.Done, vault.removeFromPhone(item.clientRef))
        assertFalse(files.vaultFile(item.clientRef).exists())
        assertNotNull(vault.items.value.single().wrappedKey)

        // The server sends back something else: refused.
        val stored = repository.items.values.single()
        val original = stored.bytes.toByteArray()
        stored.bytes.reset()
        stored.bytes.write(original.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.FILE_DAMAGED), vault.downloadToPhone(item.clientRef))
        assertFalse(files.vaultFile(item.clientRef).exists())

        stored.bytes.reset()
        stored.bytes.write(original)
        assertEquals(VaultActionResult.Done, vault.downloadToPhone(item.clientRef))
        assertTrue(vault.verifyOnPhone(item.clientRef))
    }

    @Test
    fun `viewing decrypts into the private viewing folder only until the viewer closes`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000
        val vault = vault()
        runCurrent()
        val plaintext = "the evidence itself".toByteArray()
        val item = vault.capture(plaintext, kind = EvidenceKind.PHOTO)!!

        val opened = vault.openForViewing(item.clientRef).getOrThrow()
        assertArrayEquals(plaintext, opened.readBytes())
        assertEquals(files.viewDir, opened.parentFile)

        vault.closeViewer(item.clientRef)
        assertFalse(opened.exists())

        keyWrapper.available = false
        assertTrue(vault.openForViewing(item.clientRef).exceptionOrNull() is EvidenceVault.KeyUnavailableException)
    }

    @Test
    fun `if sealing fails (Keystore unavailable) the plaintext is kept privately and sealed on the next start`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000
        keyWrapper.available = false
        val first = vault()
        runCurrent()
        val (pending, file) = first.prepareCapture(USER, EMERGENCY, EvidenceKind.AUDIO)
        file.writeBytes("must not be lost".toByteArray())

        assertNull(first.completeCapture(pending))
        assertTrue(file.exists())
        assertTrue(first.items.value.isEmpty())

        keyWrapper.available = true
        val second = vault()
        runCurrent()
        val recovered = second.items.value.single()
        assertEquals(sha256("must not be lost".toByteArray()), recovered.plaintextSha256)
        assertFalse(file.exists())
    }

    @Test
    fun `a deleted item stays deleted whatever the upload loop does later`() = runTest {
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(9, 9))!!
        runCurrent()
        assertEquals(VaultActionResult.Done, vault.deleteEverywhere(item.clientRef, "correct-horse"))

        vault.syncNow()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(CloudState.DELETED, vault.items.value.single().cloud)
        assertNull(vault.items.value.single().wrappedKey)
        assertFalse(files.vaultFile(item.clientRef).exists())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private companion object {
        const val USER = 7L
        const val OTHER_USER = 8L
        const val EMERGENCY = 42L
    }
}
