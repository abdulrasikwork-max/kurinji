package com.safesphere.app.feature.evidence

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.evidence.data.EvidenceFiles
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.session.EvidenceVault
import com.safesphere.app.feature.evidence.session.VaultLock
import com.safesphere.app.feature.evidence.ui.EvidenceVaultViewModel
import com.safesphere.app.feature.evidence.ui.ScreenLockGate
import com.safesphere.app.feature.evidence.ui.VaultEvent
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeEvidenceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 5 hardening: the vault screen is locked behind the phone's screen lock. While locked, the screen state holds no
 * evidence and every action is refused; leaving the screen or changing account locks it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultLockTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()

    private val repository = FakeEvidenceRepository().apply {
        failNext = ApiResult.Failure(ApiError.NO_CONNECTION)
        failCount = 1_000 // evidence stays on the phone; the server plays no part here
    }
    private val asha = AuthSession("token", AuthUser(USER, "Asha", "asha@example.com"))
    private val session = MutableStateFlow<AuthSession?>(asha)

    private fun TestScope.setUp(): Triple<EvidenceVault, VaultLock, EvidenceVaultViewModel> {
        val vault = EvidenceVault(
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            files = EvidenceFiles(folder.root.resolve("files"), folder.root.resolve("cache")),
            keyWrapper = FakeKeyWrapper(),
            repository = repository,
            connectivityMonitor = FakeConnectivityMonitor(online = true),
            session = session,
            now = { Instant.parse("2026-10-20T09:00:00Z").plusMillis(testScheduler.currentTime) }
        )
        val lock = VaultLock(backgroundScope, vault.currentUserId)
        return Triple(vault, lock, EvidenceVaultViewModel(vault, lock, -1))
    }

    private suspend fun EvidenceVault.capture(): String {
        val (pending, file) = prepareCapture(USER, 42, EvidenceKind.PHOTO)
        file.writeBytes("a photo".toByteArray())
        return completeCapture(pending)!!.clientRef
    }

    @Test
    fun `nothing is shown or opened until the screen lock is confirmed`() = runTest {
        val (vault, _, viewModel) = setUp()
        runCurrent()
        val ref = vault.capture()
        runCurrent()

        assertTrue(viewModel.uiState.value.locked)
        assertTrue(viewModel.uiState.value.rows.isEmpty())
        viewModel.onOpen(ref)
        runCurrent()
        assertNull(viewModel.event.value)

        viewModel.onScreenLockResult(ScreenLockGate.Result.CANCELLED)
        runCurrent()
        assertTrue(viewModel.uiState.value.locked)

        viewModel.onScreenLockResult(ScreenLockGate.Result.CONFIRMED)
        runCurrent()
        assertFalse(viewModel.uiState.value.locked)
        assertFalse(viewModel.uiState.value.unprotected)
        assertEquals(listOf(ref), viewModel.uiState.value.rows.map { it.clientRef })
        viewModel.onOpen(ref)
        runCurrent()
        assertTrue(viewModel.event.value is VaultEvent.Open)
        vault.closeViewer(ref)
    }

    @Test
    fun `leaving the screen locks the vault again`() = runTest {
        val (vault, _, viewModel) = setUp()
        runCurrent()
        vault.capture()
        viewModel.onScreenLockResult(ScreenLockGate.Result.CONFIRMED)
        runCurrent()
        assertFalse(viewModel.uiState.value.locked)

        viewModel.onLeftScreen()
        runCurrent()
        assertTrue(viewModel.uiState.value.locked)
        assertTrue(viewModel.uiState.value.rows.isEmpty())
    }

    @Test
    fun `a change of account locks the vault`() = runTest {
        val (vault, lock, viewModel) = setUp()
        runCurrent()
        vault.capture()
        viewModel.onScreenLockResult(ScreenLockGate.Result.CONFIRMED)
        runCurrent()

        session.value = AuthSession("token-b", AuthUser(OTHER_USER, "Bina", "bina@example.com"))
        runCurrent()
        assertTrue(viewModel.uiState.value.locked)
        assertFalse(lock.isUnlocked(OTHER_USER))

        // Back to the first person: still locked until the screen lock is confirmed again.
        session.value = asha
        runCurrent()
        assertTrue(viewModel.uiState.value.locked)
    }

    @Test
    fun `a phone without a screen lock opens the vault but says it is unprotected`() = runTest {
        val (vault, _, viewModel) = setUp()
        runCurrent()
        vault.capture()

        viewModel.onScreenLockResult(ScreenLockGate.Result.NO_SCREEN_LOCK)
        runCurrent()

        assertFalse(viewModel.uiState.value.locked)
        assertTrue(viewModel.uiState.value.unprotected)
    }

    private companion object {
        const val USER = 7L
        const val OTHER_USER = 8L
    }
}
