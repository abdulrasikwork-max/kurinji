package com.safesphere.app.feature.evidence

import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.data.RecoveryKeyStore
import com.safesphere.app.feature.evidence.domain.PassphraseGenerator
import com.safesphere.app.feature.evidence.domain.PassphrasePolicy
import com.safesphere.app.feature.evidence.domain.PassphraseProblem
import com.safesphere.app.feature.evidence.session.EvidenceRecovery
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.RecoveryResult
import com.safesphere.app.feature.evidence.session.VaultLock
import com.safesphere.app.feature.evidence.ui.EvidenceRecoveryViewModel
import com.safesphere.app.feature.evidence.ui.RecoveryEvent
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEvidenceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The vault screen's recovery section. Main is UNCONFINED here, like Android's Main.immediate: a refresh launched at
 * the end of an action runs eagerly, which is how the "buttons stay disabled" bug found on the emulator happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceRecoveryViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val folder = TemporaryFolder()

    private val server = FakeEvidenceRepository()
    private val session = MutableStateFlow<AuthSession?>(AuthSession("token", AuthUser(USER, "Asha", "asha@example.com")))

    private fun TestScope.setUp(): Pair<VaultLock, EvidenceRecoveryViewModel> {
        val keyWrapper = FakeKeyWrapper()
        val files = EvidenceFiles(folder.root.resolve("files"), folder.root.resolve("cache"))
        val store = RecoveryKeyStore(files, keyWrapper)
        val io = StandardTestDispatcher(testScheduler)
        val vault = EvidenceVault(
            scope = backgroundScope, io = io, files = files, keyWrapper = keyWrapper, repository = server,
            connectivityMonitor = FakeConnectivityMonitor(online = true), session = session,
            now = { Instant.parse("2026-10-20T09:00:00Z") }, recoveryKeys = store
        )
        val recovery = EvidenceRecovery(io, vault, server, store, keyWrapper, iterations = 1_000)
        val lock = VaultLock(backgroundScope, vault.currentUserId)
        val viewModel = EvidenceRecoveryViewModel(
            recovery, vault, lock, session.map { it?.user },
            policy = { PassphrasePolicy() },
            generator = { PassphraseGenerator((0 until 2000).map { "word$it" }) },
            io = io
        )
        return lock to viewModel
    }

    @Test
    fun `after a refused and then an accepted set-up the buttons are usable again`() = runTest {
        val (lock, viewModel) = setUp()
        runCurrent()
        lock.unlock(USER)
        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.state.value.canSetUp)

        viewModel.onSetUp(PHRASE.toCharArray(), PHRASE.toCharArray(), "wrong-password".toCharArray())
        runCurrent()
        val refused = (viewModel.event.value as RecoveryEvent.Result).result
        assertTrue(refused is RecoveryResult.Failed)
        assertFalse("not stuck busy", viewModel.state.value.busy)
        viewModel.onEventHandled()

        viewModel.onSetUp(PHRASE.toCharArray(), PHRASE.toCharArray(), server.password.toCharArray())
        runCurrent()
        assertEquals(RecoveryResult.Done, (viewModel.event.value as RecoveryEvent.Result).result)
        assertFalse(viewModel.state.value.busy)
        assertTrue(viewModel.state.value.configured)
        assertTrue(viewModel.state.value.canChange)
    }

    @Test
    fun `a weak passphrase is refused before anything reaches the server`() = runTest {
        val (lock, viewModel) = setUp()
        runCurrent()
        lock.unlock(USER)
        val before = server.calls.size
        viewModel.onSetUp("short".toCharArray(), "short".toCharArray(), server.password.toCharArray())
        runCurrent()
        assertEquals(RecoveryEvent.Problem(PassphraseProblem.TOO_SHORT), viewModel.event.value)
        assertEquals(before, server.calls.size)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `while the vault is locked nothing happens`() = runTest {
        val (_, viewModel) = setUp()
        runCurrent()
        val before = server.calls.size
        viewModel.refresh()
        viewModel.onSetUp(PHRASE.toCharArray(), PHRASE.toCharArray(), server.password.toCharArray())
        runCurrent()
        assertEquals(before, server.calls.size)
        assertEquals(null, viewModel.event.value)
    }

    private companion object {
        const val USER = 7L
        const val PHRASE = "velvet lantern orbit thicket meadow"
    }
}
