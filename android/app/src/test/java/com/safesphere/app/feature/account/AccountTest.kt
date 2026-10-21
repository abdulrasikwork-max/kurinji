package com.safesphere.app.feature.account

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.feature.account.data.RemoteAccountRepository
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.data.remote.ChangePasswordRequestDto
import com.safesphere.app.feature.account.data.remote.DeletionScheduledDto
import com.safesphere.app.feature.account.data.remote.PasswordConfirmationDto
import com.safesphere.app.feature.account.domain.AccountPolicy
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.domain.DeletionNotice
import com.safesphere.app.feature.account.domain.PasswordProblem
import com.safesphere.app.feature.account.domain.PendingDeletion
import com.safesphere.app.feature.account.domain.PendingDeletionStore
import com.safesphere.app.feature.account.session.AccountDataWiper
import com.safesphere.app.feature.account.ui.AccountMessage
import com.safesphere.app.feature.account.ui.AccountViewModel
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.UserDto
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.ui.LoginViewModel
import com.safesphere.app.testing.FakeAuthApi
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Phase 10A.2 / 10A.3 on the phone: password rules, token swap, deletion state, the local wipe and the Login notice. */
class AccountTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = AuthUser(7, "Asha", "asha@example.com")
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    // ---- AccountPolicy -----------------------------------------------------------------------------------------

    @Test
    fun `new passwords are checked before anything is sent`() {
        assertEquals(PasswordProblem.CURRENT_MISSING, AccountPolicy.checkNewPassword("", "longenough", "longenough"))
        assertEquals(PasswordProblem.TOO_SHORT, AccountPolicy.checkNewPassword("old-password", "short", "short"))
        val tooLong = "é".repeat(40) // 40 characters but 80 bytes
        assertEquals(PasswordProblem.TOO_LONG, AccountPolicy.checkNewPassword("old-password", tooLong, tooLong))
        assertEquals(PasswordProblem.MISMATCH, AccountPolicy.checkNewPassword("old-password", "new-password", "new-passwort"))
        assertEquals(PasswordProblem.SAME_AS_CURRENT, AccountPolicy.checkNewPassword("same-password", "same-password", "same-password"))
        assertNull(AccountPolicy.checkNewPassword("old-password", "new-password", "new-password"))
    }

    @Test
    fun `a pending deletion is a dated banner, or 'soon' once the date passed`() {
        assertNull(AccountPolicy.deletionNotice(user, now))
        assertNull(AccountPolicy.deletionNotice(null, now))
        val due = now.plusSeconds(3_600)
        assertEquals(DeletionNotice.Scheduled(due, now), AccountPolicy.deletionNotice(user.copy(deletionRequestedAt = now, deletionDueAt = due), now))
        assertEquals(DeletionNotice.Soon, AccountPolicy.deletionNotice(user.copy(deletionDueAt = now.minusSeconds(1)), now))
    }

    @Test
    fun `the user DTO carries the deletion dates, and an unreadable date still shows a pending deletion`() {
        val dto = UserDto(7, "Asha", "a@example.com", null, "2026-10-21T10:00:00Z", "2026-10-28T10:00:00Z")
        assertEquals(Instant.parse("2026-10-28T10:00:00Z"), dto.toDomain().deletionDueAt)
        assertTrue(dto.copy(deletionDueAt = "garbage").toDomain().deletionPending)
        assertFalse(dto.copy(deletionRequestedAt = null, deletionDueAt = null).toDomain().deletionPending)
        assertFalse("e-mail must not be in toString", dto.toDomain().toString().contains("example.com"))
    }

    // ---- RemoteAccountRepository -------------------------------------------------------------------------------

    @Test
    fun `a password change saves the NEW token before returning (R8)`() = runTest {
        val storage = FakeSessionStorage(AuthSession("old-token", user))
        val api = FakeAccountApi()
        val repository = repository(storage, api)

        val result = repository.changePassword("old-password", "new-password")

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals("fresh-token", storage.savedSession?.token)
        assertEquals("old-password", api.lastChange?.currentPassword)
    }

    @Test
    fun `a refused password change keeps the session, and a 401 for it ends the session`() = runTest {
        val storage = FakeSessionStorage(AuthSession("old-token", user))
        val api = FakeAccountApi().apply { changeOutcome = { throw httpError(409, """{"message":"Password is incorrect"}""") } }
        val repository = repository(storage, api)

        assertEquals(ApiResult.Failure(ApiError.CONFLICT, "Password is incorrect"), repository.changePassword("x", "new-password"))
        assertEquals("old-token", storage.savedSession?.token)

        api.changeOutcome = { throw httpError(401, "{}") }
        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), repository.changePassword("x", "new-password"))
        assertNull(storage.savedSession)
    }

    @Test
    fun `requesting and cancelling a deletion update the session at once (the banner)`() = runTest {
        val storage = FakeSessionStorage(AuthSession("t", user))
        val repository = repository(storage, FakeAccountApi())

        assertEquals(ApiResult.Success(Instant.parse("2026-10-29T10:00:00Z")), repository.requestDeletion("pw"))
        assertEquals(Instant.parse("2026-10-29T10:00:00Z"), storage.savedSession?.user?.deletionDueAt)
        assertEquals(Instant.parse("2026-10-22T10:00:00Z"), storage.savedSession?.user?.deletionRequestedAt)

        assertEquals(ApiResult.Success(Unit), repository.cancelDeletion("pw"))
        assertFalse(storage.savedSession!!.user.deletionPending)
        assertEquals("t", storage.savedSession?.token)
    }

    // ---- AccountViewModel --------------------------------------------------------------------------------------

    @Test
    fun `the screen validates first, then reports one outcome`() = runTest {
        val repository = FakeAccountRepository()
        val viewModel = AccountViewModel(repository, flowOf(user)) { now }

        viewModel.onChangePassword("old-password", "short", "short")
        assertEquals(AccountMessage.Invalid(PasswordProblem.TOO_SHORT), viewModel.message.value)
        assertEquals(0, repository.calls)

        viewModel.onMessageShown()
        viewModel.onChangePassword("old-password", "new-password", "new-password")
        advanceUntilIdle()
        assertEquals(AccountMessage.PasswordChanged, viewModel.message.value)
        assertFalse(viewModel.busy.value)

        viewModel.onRequestDeletion("")
        assertEquals(AccountMessage.PasswordRequired, viewModel.message.value)
    }

    @Test
    fun `a refusal is shown with the server's words, and a 401 asks for a new sign-in`() = runTest {
        val repository = FakeAccountRepository().apply {
            result = ApiResult.Failure(ApiError.CONFLICT, "Your account cannot be deleted while an emergency is active.")
        }
        val viewModel = AccountViewModel(repository, flowOf(user)) { now }

        viewModel.onRequestDeletion("pw")
        advanceUntilIdle()
        assertEquals(AccountMessage.Failed(ApiResult.Failure(ApiError.CONFLICT,
            "Your account cannot be deleted while an emergency is active.")), viewModel.message.value)

        repository.result = ApiResult.Failure(ApiError.UNAUTHORIZED)
        viewModel.onCancelDeletion("pw")
        advanceUntilIdle()
        assertTrue(viewModel.sessionExpired.value)
    }

    @Test
    fun `the deletion banner follows the signed-in user`() = runTest {
        val current = MutableStateFlow<AuthUser?>(user)
        val viewModel = AccountViewModel(FakeAccountRepository(), current) { now }
        assertNull(viewModel.deletion.first())
        current.value = user.copy(deletionDueAt = now.plusSeconds(60))
        assertTrue(viewModel.deletion.first() is DeletionNotice.Scheduled)
    }

    // ---- AccountDataWiper and the Login notice -----------------------------------------------------------------

    @Test
    fun `the wipe runs every part even if one fails, and keeps the marker until all succeeded`() = runTest {
        val store = FakePendingDeletionStore(PendingDeletion(7, now))
        val ran = mutableListOf<String>()
        var failVault = true
        val wiper = AccountDataWiper(
            listOf(
                { id -> ran += "vault $id"; if (failVault) error("disk") },
                { id -> ran += "recovery $id" }
            ),
            store
        )

        assertFalse(wiper.wipe(7))
        assertEquals(listOf("vault 7", "recovery 7"), ran)
        assertEquals(PendingDeletion(7, now), store.pending.first())

        failVault = false
        assertTrue(wiper.wipe(7))
        assertNull(store.pending.first())
    }

    @Test
    fun `a marker for another account is never cleared by a wipe`() = runTest {
        val store = FakePendingDeletionStore(PendingDeletion(8, now))
        AccountDataWiper(emptyList(), store).wipe(7)
        assertEquals(PendingDeletion(8, now), store.pending.first())
    }

    @Test
    fun `Login offers the local clean-up only after the deletion date`() = runTest {
        val marker = MutableStateFlow<PendingDeletion?>(PendingDeletion(7, now.plusSeconds(60)))
        val wiped = mutableListOf<Long>()
        val viewModel = LoginViewModel(FakeAuthRepository(), marker, { wiped += it; true }) { now }

        assertFalse(viewModel.deletedAccountData.first())
        marker.value = PendingDeletion(7, now.minusSeconds(1))
        assertTrue(viewModel.deletedAccountData.first())
        viewModel.onRemoveDeletedAccountData()
        advanceUntilIdle()
        assertEquals(listOf(7L), wiped)
        marker.value = null
        assertFalse(viewModel.deletedAccountData.first())
    }

    // ---- fakes -------------------------------------------------------------------------------------------------

    private fun repository(storage: FakeSessionStorage, api: AccountApi): AccountRepository {
        val ender = SessionEnder(storage)
        return RemoteAccountRepository(api, ApiCallHandler(ender), storage, ender)
    }

    private fun httpError(code: Int, body: String) = HttpException(Response.error<Any>(code, body.toResponseBody(null)))

    private class FakeAccountApi : AccountApi {
        var lastChange: ChangePasswordRequestDto? = null
        var changeOutcome: () -> AuthResponseDto = { FakeAuthApi.authResponse("fresh-token") }

        override suspend fun changePassword(body: ChangePasswordRequestDto): AuthResponseDto {
            lastChange = body
            return changeOutcome()
        }

        override suspend fun requestDeletion(body: PasswordConfirmationDto) =
            DeletionScheduledDto("2026-10-22T10:00:00Z", "2026-10-29T10:00:00Z")

        override suspend fun cancelDeletion(body: PasswordConfirmationDto) = Unit
    }

    private class FakeAccountRepository : AccountRepository {
        var calls = 0
        var result: ApiResult<Unit>? = null

        override suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> {
            calls++
            return result ?: ApiResult.Success(Unit)
        }

        override suspend fun requestDeletion(password: String): ApiResult<Instant> {
            calls++
            return when (val r = result) {
                is ApiResult.Failure -> r
                else -> ApiResult.Success(Instant.EPOCH)
            }
        }

        override suspend fun cancelDeletion(password: String): ApiResult<Unit> {
            calls++
            return result ?: ApiResult.Success(Unit)
        }
    }

    private class FakePendingDeletionStore(initial: PendingDeletion?) : PendingDeletionStore {
        private val state = MutableStateFlow(initial)
        override val pending: Flow<PendingDeletion?> = state
        override suspend fun set(value: PendingDeletion) {
            state.value = value
        }

        override suspend fun clear(userId: Long?) {
            if (userId == null || state.value?.userId == userId) state.value = null
        }
    }
}
