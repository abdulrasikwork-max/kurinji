package com.safesphere.app.feature.evidence

import com.safesphere.app.core.crypto.RecoveryKeyCrypto
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.data.RecoveryKeyStore
import com.safesphere.app.feature.evidence.domain.CloudState
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.VaultItem
import com.safesphere.app.feature.evidence.session.EvidenceRecovery
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.RecoveryResult
import com.safesphere.app.feature.evidence.session.VaultActionResult
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEvidenceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Restore on a new phone (§11), phone side: set-up, protection of new and earlier items, restore on a NEW phone (its
 * own folder and its own Keystore key) with download on demand, wrong passphrase, change / forgotten passphrase,
 * account isolation, and deletions made on another phone. The fake server stores the opaque bytes like the real one.
 * PBKDF2 runs with 1 000 iterations here (the app uses 600 000; the server refuses fewer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceRecoveryTest {

    @get:Rule val folder = TemporaryFolder()

    private val start = Instant.parse("2026-10-20T09:00:00Z")
    private val server = FakeEvidenceRepository()
    private val asha = AuthSession("token", AuthUser(USER, "Asha", "asha@example.com"))
    private val passphrase = "velvet lantern orbit thicket meadow"
    private val password = "correct-horse"

    /** One phone: its own files and its own (fake) Keystore key. */
    private inner class Phone(name: String, private val scope: TestScope) {
        val keyWrapper = FakeKeyWrapper()
        val files = EvidenceFiles(folder.root.resolve("$name/files"), folder.root.resolve("$name/cache"))
        val session = MutableStateFlow<AuthSession?>(asha)
        val store = RecoveryKeyStore(files, keyWrapper)
        val vault = EvidenceVault(
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            files = files,
            keyWrapper = keyWrapper,
            repository = server,
            connectivityMonitor = FakeConnectivityMonitor(online = true),
            session = session,
            now = { start.plusMillis(scope.testScheduler.currentTime) },
            retryDelaysMillis = listOf(1_000, 2_000),
            recoveryKeys = store
        )
        val recovery = EvidenceRecovery(StandardTestDispatcher(scope.testScheduler), vault, server, store, keyWrapper, iterations = 1_000)

        suspend fun capture(bytes: ByteArray, user: Long = USER): VaultItem {
            val (pending, file) = vault.prepareCapture(user, EMERGENCY, EvidenceKind.AUDIO)
            file.writeBytes(bytes)
            return vault.completeCapture(pending)!!
        }

        suspend fun open(clientRef: String): ByteArray {
            val file = vault.openForViewing(clientRef).getOrThrow()
            return file.readBytes().also { vault.closeViewer(clientRef) }
        }
    }

    private suspend fun EvidenceRecovery.restoreWith(phrase: String): RecoveryResult {
        val envelope = (fetchEnvelope(password) as ApiResult.Success).data
        return restore(USER, envelope, phrase.toCharArray())
    }

    @Test
    fun `set up, then a new phone restores every item with the passphrase and opens it after Bring back`() = runTest {
        val old = Phone("old", this)
        runCurrent()
        val before = old.capture("recorded before set-up".toByteArray()) // gets its recovery copy by back-fill
        runCurrent()
        assertFalse(old.vault.items.value.single().recoverable)

        assertEquals(RecoveryResult.Done, old.recovery.setUp(USER, passphrase.toCharArray(), password))
        runCurrent()
        val after = old.capture("recorded after set-up".toByteArray()) // declared with its recovery copy
        runCurrent()
        assertTrue(old.vault.items.value.all { it.recoverable && it.cloud == CloudState.SEALED })
        assertEquals(2, server.recoveryWraps.size)
        // The server never received the passphrase or any key it could use.
        assertTrue(server.calls.none { it.contains("velvet") })

        val new = Phone("new", this)
        runCurrent()
        assertEquals(RecoveryResult.Restored(2, 0), new.recovery.restoreWith(passphrase))
        runCurrent()
        val restored = new.vault.itemsFor(USER)
        assertEquals(setOf(before.clientRef, after.clientRef), restored.map { it.clientRef }.toSet())
        assertTrue(restored.all { !it.onPhone && it.cloud == CloudState.SEALED })
        assertTrue(new.store.hasKey(USER))

        // Download on demand, hash-checked, then opened with the key the new phone re-sealed.
        assertEquals(VaultActionResult.Done, new.vault.downloadToPhone(before.clientRef))
        assertArrayEquals("recorded before set-up".toByteArray(), new.open(before.clientRef))
        // Restoring twice adds nothing.
        assertEquals(RecoveryResult.Restored(0, 0), new.recovery.restoreWith(passphrase))
    }

    @Test
    fun `a wrong passphrase restores nothing and stores no key`() = runTest {
        val old = Phone("old", this)
        runCurrent()
        old.capture(byteArrayOf(1, 2, 3))
        old.recovery.setUp(USER, passphrase.toCharArray(), password)
        runCurrent()

        val new = Phone("new", this)
        runCurrent()
        assertEquals(RecoveryResult.WrongPassphrase, new.recovery.restoreWith("velvet lantern orbit thicket meadows"))
        assertTrue(new.vault.itemsFor(USER).isEmpty())
        assertFalse(new.store.hasKey(USER))
    }

    @Test
    fun `set-up needs the right account password and is refused when one already exists`() = runTest {
        val phone = Phone("one", this)
        runCurrent()
        val wrong = phone.recovery.setUp(USER, passphrase.toCharArray(), "not-it")
        assertTrue(wrong is RecoveryResult.Failed && wrong.failure.message == "Password is incorrect")
        assertNull(server.envelope)

        assertEquals(RecoveryResult.Done, phone.recovery.setUp(USER, passphrase.toCharArray(), password))
        val other = Phone("two", this)
        runCurrent()
        assertEquals(RecoveryResult.AlreadySetUp, other.recovery.setUp(USER, "another long passphrase here".toCharArray(), password))
        assertFalse("a refused set-up keeps no key", other.store.hasKey(USER))
    }

    @Test
    fun `changing the passphrase needs the current one, keeps the same key, and the old passphrase stops working`() = runTest {
        val phone = Phone("one", this)
        runCurrent()
        phone.capture("keep me".toByteArray())
        phone.recovery.setUp(USER, passphrase.toCharArray(), password)
        runCurrent()
        val envelope = (phone.recovery.fetchEnvelope(password) as ApiResult.Success).data

        assertEquals(RecoveryResult.WrongPassphrase,
            phone.recovery.changePassphrase(USER, envelope, "not the passphrase at all".toCharArray(), "new river words here now".toCharArray(), password))
        assertEquals(RecoveryResult.Done,
            phone.recovery.changePassphrase(USER, envelope, passphrase.toCharArray(), "new river words here now".toCharArray(), password))
        assertEquals(2, server.envelope!!.envelopeVersion)

        val new = Phone("new", this)
        runCurrent()
        assertEquals(RecoveryResult.WrongPassphrase, new.recovery.restoreWith(passphrase))
        assertEquals(RecoveryResult.Restored(1, 0), new.recovery.restoreWith("new river words here now"))
    }

    @Test
    fun `a forgotten passphrase can be replaced only from a phone that holds the recovery key`() = runTest {
        val phone = Phone("one", this)
        runCurrent()
        phone.recovery.setUp(USER, passphrase.toCharArray(), password)
        val elsewhere = Phone("two", this)
        runCurrent()
        assertEquals(RecoveryResult.NoKeyOnPhone, elsewhere.recovery.replaceForgotten(USER, "brand new words for me".toCharArray(), password))

        assertEquals(RecoveryResult.Done, phone.recovery.replaceForgotten(USER, "brand new words for me".toCharArray(), password))
        assertEquals(RecoveryResult.WrongPassphrase, elsewhere.recovery.restoreWith(passphrase))
        assertEquals(RecoveryResult.Restored(0, 0), elsewhere.recovery.restoreWith("brand new words for me"))
    }

    @Test
    fun `a declaration is never held back when the server refuses the recovery copy`() = runTest {
        val phone = Phone("one", this)
        runCurrent()
        phone.recovery.setUp(USER, passphrase.toCharArray(), password)
        server.envelope = null // e.g. the server lost it: declaring WITH a recovery copy now answers 409
        phone.capture("still uploaded".toByteArray())
        runCurrent()
        val item = phone.vault.items.value.single()
        assertEquals(CloudState.SEALED, item.cloud)
        assertFalse(item.recoverable)
        assertFalse(item.uploadRefused)
    }

    @Test
    fun `restore is per account - another person's items are never imported`() = runTest {
        val phone = Phone("one", this)
        runCurrent()
        phone.capture("asha".toByteArray())
        phone.recovery.setUp(USER, passphrase.toCharArray(), password)
        runCurrent()
        val new = Phone("new", this)
        runCurrent()
        new.recovery.restoreWith(passphrase)
        assertTrue(new.vault.itemsFor(OTHER_USER).isEmpty())
        assertTrue(new.vault.items.value.all { it.userId == USER })
        assertFalse(new.store.hasKey(OTHER_USER))
    }

    @Test
    fun `delete everywhere on one phone destroys the key on the restored phone too`() = runTest {
        val old = Phone("old", this)
        runCurrent()
        val item = old.capture("to be deleted".toByteArray())
        old.recovery.setUp(USER, passphrase.toCharArray(), password)
        runCurrent()
        val new = Phone("new", this)
        runCurrent()
        new.recovery.restoreWith(passphrase)
        val restoredKey = new.vault.itemsFor(USER).single().wrappedKey
        assertNotNull(restoredKey)

        assertEquals(VaultActionResult.Done, old.vault.deleteEverywhere(item.clientRef, password))
        assertTrue(server.recoveryWraps.isEmpty()) // the server's recovery copy is gone too
        assertTrue(new.vault.syncDeletions(USER))
        val shredded = new.vault.itemsFor(USER).single()
        assertTrue(shredded.isDeleted)
        assertNull(shredded.wrappedKey)
        assertFalse(new.files.indexBackupFile.readText().contains(restoredKey!!))
        assertFalse(new.files.indexFile.readText().contains(restoredKey))
    }

    @Test
    fun `restore reports items whose recovery copy is damaged instead of importing them`() = runTest {
        val old = Phone("old", this)
        runCurrent()
        old.capture("good".toByteArray())
        old.capture("damaged".toByteArray())
        old.recovery.setUp(USER, passphrase.toCharArray(), password)
        runCurrent()
        val damagedId = server.recoveryWraps.keys.last()
        server.recoveryWraps[damagedId] = 1 to ByteArray(RecoveryKeyCrypto.WRAPPED_BYTES)

        val new = Phone("new", this)
        runCurrent()
        assertEquals(RecoveryResult.Restored(1, 1), new.recovery.restoreWith(passphrase))
    }

    @Test
    fun `restore without a passphrase set up says so`() = runTest {
        val new = Phone("new", this)
        runCurrent()
        val fetched = new.recovery.fetchEnvelope(password)
        assertTrue(fetched is ApiResult.Failure && fetched.error == ApiError.NOT_FOUND)
    }

    private companion object {
        const val USER = 7L
        const val OTHER_USER = 8L
        const val EMERGENCY = 42L
    }
}
