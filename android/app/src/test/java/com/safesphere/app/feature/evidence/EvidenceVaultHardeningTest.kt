package com.safesphere.app.feature.evidence

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.data.EvidenceIndexFile
import com.safesphere.app.feature.evidence.data.PlaintextWipe
import com.safesphere.app.feature.evidence.domain.AnchorStatus
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.CustodyEntry
import com.safesphere.app.feature.evidence.domain.CustodyReport
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.VaultItem
import com.safesphere.app.feature.evidence.session.EvidenceUploadScheduler
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.IntegrityResult
import com.safesphere.app.feature.evidence.session.LocalProblemReason
import com.safesphere.app.feature.evidence.session.VaultActionResult
import com.safesphere.app.feature.evidence.session.VaultCopyCheck
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEvidenceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 5 hardening of the phone's vault: account isolation of every action, a vault copy re-checked before the phone
 * copy is removed (and repaired when damaged), the integrity check of both copies, retrying a refused upload, the
 * crash-safe index, plaintext wiping, custody anchoring, and the background-upload signal. Virtual time only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceVaultHardeningTest {

    @get:Rule val folder = TemporaryFolder()

    private val start = Instant.parse("2026-10-20T09:00:00Z")
    private val repository = FakeEvidenceRepository()
    private val connectivity = FakeConnectivityMonitor(online = true)
    private val keyWrapper = FakeKeyWrapper()
    private val asha = AuthSession("token", AuthUser(USER, "Asha", "asha@example.com"))
    private val bina = AuthSession("token-b", AuthUser(OTHER_USER, "Bina", "bina@example.com"))
    private val session = MutableStateFlow<AuthSession?>(asha)
    private val files by lazy { EvidenceFiles(folder.root.resolve("files"), folder.root.resolve("cache")) }
    private val scheduled = mutableListOf<Boolean>()

    private fun TestScope.vault() = EvidenceVault(
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        files = files,
        keyWrapper = keyWrapper,
        repository = repository,
        connectivityMonitor = connectivity,
        session = session,
        now = { start.plusMillis(testScheduler.currentTime) },
        retryDelaysMillis = listOf(1_000, 2_000),
        uploadScheduler = EvidenceUploadScheduler { scheduled += it }
    )

    private suspend fun EvidenceVault.capture(bytes: ByteArray, user: Long = USER): VaultItem? {
        val (pending, file) = prepareCapture(user, EMERGENCY, EvidenceKind.AUDIO)
        file.writeBytes(bytes)
        return completeCapture(pending)
    }

    private fun stayOffline() {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1_000
    }

    // ---- account isolation -----------------------------------------------------------------------------

    @Test
    fun `another account on the phone cannot open, check, remove, retry or delete the first person's item`() = runTest {
        val vault = vault()
        runCurrent()
        val item = vault.capture("first person's evidence".toByteArray())!!
        runCurrent()
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)

        session.value = bina
        runCurrent()
        val callsBefore = repository.calls.size

        assertTrue(vault.openForViewing(item.clientRef).isFailure)
        assertTrue(files.viewDir.listFiles().orEmpty().isEmpty())
        assertFalse(vault.verifyOnPhone(item.clientRef))
        assertNull(vault.checkIntegrity(item.clientRef))
        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED), vault.removeFromPhone(item.clientRef))
        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED), vault.retryUpload(item.clientRef))
        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED), vault.deleteEverywhere(item.clientRef, "correct-horse"))

        assertEquals(callsBefore, repository.calls.size) // nothing reached the server with the other person's token
        assertTrue(files.vaultFile(item.clientRef).exists())
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
    }

    @Test
    fun `forgetting a deleted account removes only its items, files and keys, also from the index backup`() = runTest {
        stayOffline()
        val vault = vault()
        runCurrent()
        val mine = vault.capture("deleted account's evidence".toByteArray())!!
        session.value = bina
        runCurrent()
        val hers = vault.capture("other account's evidence".toByteArray(), user = OTHER_USER)!!
        runCurrent()
        assertEquals(2, vault.items.value.size)

        vault.forgetAccount(USER)
        runCurrent()

        assertEquals(listOf(hers.clientRef), vault.items.value.map { it.clientRef })
        assertFalse(files.vaultFile(mine.clientRef).exists())
        assertTrue(files.vaultFile(hers.clientRef).exists())
        // Neither the index nor its backup still holds the deleted account's sealed key.
        for (file in listOf(files.indexFile, files.indexBackupFile)) {
            if (file.exists()) assertFalse(file.name, file.readText().contains(mine.clientRef))
        }
        assertEquals(listOf(hers.clientRef), EvidenceIndexFile(files).read().map { it.clientRef })
    }

    @Test
    fun `while signed out nothing can be opened`() = runTest {
        stayOffline()
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(1, 2, 3))!!
        session.value = null
        runCurrent()

        assertTrue(vault.openForViewing(item.clientRef).isFailure)
    }

    // ---- the vault copy is re-checked before the phone copy goes -------------------------------------------

    @Test
    fun `remove from phone is refused when the server finds its copy damaged, and the vault is repaired from the phone`() = runTest {
        val vault = vault()
        runCurrent()
        val item = vault.capture(ByteArray(500) { it.toByte() })!!
        runCurrent()
        val stored = repository.items.values.single()
        val original = stored.bytes.toByteArray()
        stored.bytes.reset()
        stored.bytes.write(original.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }) // bit rot on the server

        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.VAULT_COPY_DAMAGED), vault.removeFromPhone(item.clientRef))
        assertTrue(files.vaultFile(item.clientRef).exists())
        assertTrue(vault.items.value.single().onPhone)

        runCurrent() // the upload loop repairs the vault
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
        assertArrayEquals(original, repository.items.values.single().bytes.toByteArray())
        assertEquals(VaultActionResult.Done, vault.removeFromPhone(item.clientRef))
        assertFalse(files.vaultFile(item.clientRef).exists())
    }

    @Test
    fun `remove from phone needs the server's answer now`() = runTest {
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(4, 5, 6))!!
        runCurrent()
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1

        assertTrue(vault.removeFromPhone(item.clientRef) is VaultActionResult.Failed)
        assertTrue(files.vaultFile(item.clientRef).exists())
    }

    @Test
    fun `check integrity reports the phone copy and the vault copy`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1 // the first upload attempt fails: the item waits on the phone
        val vault = vault()
        runCurrent()
        val item = vault.capture(ByteArray(300) { 9 })!!
        runCurrent()
        assertEquals(IntegrityResult(true, VaultCopyCheck.NOT_UPLOADED), vault.checkIntegrity(item.clientRef))

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntegrityResult(true, VaultCopyCheck.INTACT), vault.checkIntegrity(item.clientRef))

        repository.items.values.single().bytes.reset() // the vault copy is lost
        assertEquals(IntegrityResult(true, VaultCopyCheck.DAMAGED_REUPLOADING), vault.checkIntegrity(item.clientRef))
        runCurrent()
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)

        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 1
        assertEquals(VaultCopyCheck.UNAVAILABLE, vault.checkIntegrity(item.clientRef)!!.vault)
    }

    @Test
    fun `a damaged vault copy of an item no longer on the phone is reported, not hidden`() = runTest {
        val vault = vault()
        runCurrent()
        val item = vault.capture(ByteArray(200) { 1 })!!
        runCurrent()
        assertEquals(VaultActionResult.Done, vault.removeFromPhone(item.clientRef))
        repository.items.values.single().bytes.reset()

        assertEquals(IntegrityResult(null, VaultCopyCheck.DAMAGED), vault.checkIntegrity(item.clientRef))
        val after = vault.items.value.single()
        assertEquals(CloudState.UPLOADING, after.cloud)
        assertFalse(after.needsUpload)
        assertNull(after.sealedAt)
    }

    // ---- retry ------------------------------------------------------------------------------------------------

    @Test
    fun `a refused upload can be tried again`() = runTest {
        repository.declareFailure = ApiResult.Failure(ApiError.VALIDATION)
        val vault = vault()
        runCurrent()
        val item = vault.capture(byteArrayOf(7, 7))!!
        runCurrent()
        assertTrue(vault.items.value.single().uploadRefused)

        repository.declareFailure = null
        assertEquals(VaultActionResult.Done, vault.retryUpload(item.clientRef))
        runCurrent()
        assertEquals(CloudState.SEALED, vault.items.value.single().cloud)
        assertEquals(VaultActionResult.LocalProblem(LocalProblemReason.NOT_ALLOWED), vault.retryUpload(item.clientRef))
    }

    // ---- crash-safe index ----------------------------------------------------------------------------------------

    @Test
    fun `a damaged index is kept aside and the previous copy restores the item keys`() = runTest {
        stayOffline()
        val first = vault()
        runCurrent()
        val one = first.capture(byteArrayOf(1))!!
        first.capture(byteArrayOf(2))!!
        files.indexFile.writeText("{ this is not json")

        val second = vault()
        runCurrent()

        // index.json.bak holds the list before the last change: the first item and its sealed key survive.
        val restored = second.items.value.single { it.clientRef == one.clientRef }
        assertEquals(one.wrappedKey, restored.wrappedKey)
        assertTrue(files.vaultDir.listFiles()!!.any { it.name.startsWith("index.damaged-") })
        assertTrue(second.openForViewing(one.clientRef).isSuccess)
    }

    @Test
    fun `a crash between the two renames loses nothing`() = runTest {
        stayOffline()
        val first = vault()
        runCurrent()
        first.capture(byteArrayOf(1))
        first.capture(byteArrayOf(2))
        // What a crash right after "index -> .bak" and before ".tmp -> index" leaves behind.
        files.indexFile.copyTo(files.indexTempFile, overwrite = true)
        files.indexFile.delete()

        val second = vault()
        runCurrent()

        assertEquals(2, second.items.value.size)
        assertTrue(files.indexFile.exists())
    }

    @Test
    fun `index writes keep the previous version`() {
        files.ensureDirs()
        val index = EvidenceIndexFile(files)
        index.write(emptyList())
        index.write(emptyList())
        assertTrue(files.indexBackupFile.exists())
        assertFalse(files.indexTempFile.exists())
    }

    // ---- plaintext ------------------------------------------------------------------------------------------------

    @Test
    fun `plaintext wiping deletes the file and never throws`() {
        val file = folder.newFile("plain.aac").apply { writeBytes(ByteArray(200_000) { 5 }) }
        PlaintextWipe.wipe(file)
        assertFalse(file.exists())
        PlaintextWipe.wipe(file) // already gone: fine
    }

    @Test
    fun `no plaintext is left in staging or viewing after sealing and viewing`() = runTest {
        stayOffline()
        val vault = vault()
        runCurrent()
        val item = vault.capture("words spoken".toByteArray())!!
        vault.openForViewing(item.clientRef).getOrThrow()
        vault.closeViewer(item.clientRef)

        assertTrue(files.captureDir.listFiles().orEmpty().isEmpty())
        assertTrue(files.viewDir.listFiles().orEmpty().isEmpty())
    }

    // ---- custody anchoring ---------------------------------------------------------------------------------------

    @Test
    fun `the phone remembers the custody record and notices a later rewrite, across restarts`() = runTest {
        fun report(vararg hashes: String) = ApiResult.Success(CustodyReport(true, hashes.size.toLong(), hashes.last(), start,
            hashes.mapIndexed { i, h -> CustodyEntry(i + 1L, 1, "SEALED", start, h) }))

        val first = vault()
        runCurrent()
        repository.custodyReport = report("h1", "h2")
        assertEquals(AnchorStatus.FirstCheck, (first.custody(EMERGENCY) as ApiResult.Success).data.anchor)

        val second = vault()
        runCurrent()
        repository.custodyReport = report("h1", "h2", "h3")
        assertTrue((second.custody(EMERGENCY) as ApiResult.Success).data.anchor is AnchorStatus.Matches)

        repository.custodyReport = report("r1", "r2", "r3", "r4") // someone rebuilt the whole chain
        assertTrue((second.custody(EMERGENCY) as ApiResult.Success).data.anchor is AnchorStatus.Changed)
        // Another person on the same phone has their own, separate record.
        session.value = bina
        runCurrent()
        assertEquals(AnchorStatus.FirstCheck, (second.custody(EMERGENCY) as ApiResult.Success).data.anchor)
    }

    // ---- background uploads ----------------------------------------------------------------------------------------

    @Test
    fun `pending uploads are announced for the background job, and the job can wait for them`() = runTest {
        repository.failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        repository.failCount = 3
        val vault = vault()
        runCurrent()
        vault.capture(byteArrayOf(3, 3, 3))
        runCurrent()
        assertTrue(vault.hasPendingUploads.value)
        assertEquals(true, scheduled.last())

        val waiting = async { vault.awaitUploadsSettled(60_000) }
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(waiting.await())
        assertFalse(vault.hasPendingUploads.value)
        assertEquals(false, scheduled.last())
    }

    @Test
    fun `the job gives up for now (to run again) when uploads cannot finish`() = runTest {
        stayOffline()
        val vault = vault()
        runCurrent()
        vault.capture(byteArrayOf(3))

        val waiting = async { vault.awaitUploadsSettled(5_000) }
        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(waiting.await())
    }

    @Test
    fun `nothing is pending for a signed-out phone`() = runTest {
        stayOffline()
        val vault = vault()
        runCurrent()
        vault.capture(byteArrayOf(3))
        runCurrent()
        assertTrue(vault.hasPendingUploads.value)

        session.value = null
        runCurrent()
        assertFalse(vault.hasPendingUploads.value)
        val waiting = async { vault.awaitUploadsSettled(1_000) }
        runCurrent()
        assertTrue(waiting.await())
    }

    private companion object {
        const val USER = 7L
        const val OTHER_USER = 8L
        const val EMERGENCY = 42L
    }
}
