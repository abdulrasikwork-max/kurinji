package com.safesphere.app.feature.account

import com.safesphere.app.core.confirm.CodeRequest
import com.safesphere.app.core.confirm.Confirmation
import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.account.data.RemoteAccountRepository
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.data.remote.CodeConfirmationDto
import com.safesphere.app.feature.account.data.remote.DeletionScheduledDto
import com.safesphere.app.feature.account.data.remote.NameChangeDto
import com.safesphere.app.feature.account.domain.AccountPolicy
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.domain.DeletionNotice
import com.safesphere.app.feature.account.domain.PendingDeletion
import com.safesphere.app.feature.account.domain.PendingDeletionStore
import com.safesphere.app.feature.account.session.AccountDataWiper
import com.safesphere.app.feature.account.ui.AccountMessage
import com.safesphere.app.feature.account.ui.AccountViewModel
import com.safesphere.app.feature.account.ui.CodeNeeded
import com.safesphere.app.feature.account.ui.ConfirmedAction
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.PhoneChallengeDto
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

/**
 * Phase 10A.2 / 10A.3 on the phone, reworked in N2: confirmation codes instead of the password, the real name,
 * signing out the other phones (token swap), deletion state, the local wipe and the Login notice.
 */
class AccountTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = AuthUser(7, "Asha", "••••••3210")
    private val now = Instant.parse("2026-10-22T10:00:00Z")
    private val request = CodeRequest("challenge-token", "••••••3210", codeSent = true, resendInSeconds = 60)
    private val confirmation = Confirmation("challenge-token", "123456")

    // ---- Confirmation (core) -----------------------------------------------------------------------------------

    @Test
    fun `a confirmation is exactly six digits, and never prints its secrets`() {
        assertEquals(confirmation, Confirmation.of(request, "123456"))
        assertEquals(confirmation, Confirmation.of(request, " 123 456 "))
        assertNull(Confirmation.of(request, "12345"))
        assertNull(Confirmation.of(request, "1234567"))
        assertNull(Confirmation.of(request, ""))
        assertNull(Confirmation.of(request, null))
        assertFalse(confirmation.toString().contains("123456"))
        assertFalse(confirmation.toString().contains("challenge-token"))
        assertFalse(request.toString().contains("challenge-token"))
        assertFalse(CodeConfirmationDto("challenge-token", "123456").toString().contains("123456"))
        assertFalse(NameChangeDto("Asha Devi").toString().contains("Asha"))
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
        val dto = UserDto(7, "Asha", "••••••3210", null, "2026-10-21T10:00:00Z", "2026-10-28T10:00:00Z")
        assertEquals(Instant.parse("2026-10-28T10:00:00Z"), dto.toDomain().deletionDueAt)
        assertTrue(dto.copy(deletionDueAt = "garbage").toDomain().deletionPending)
        assertFalse(dto.copy(deletionRequestedAt = null, deletionDueAt = null).toDomain().deletionPending)
        assertFalse("e-mail must not be in toString", dto.toDomain().toString().contains("example.com"))
    }

    // ---- RemoteAccountRepository -------------------------------------------------------------------------------

    @Test
    fun `asking for a code reads the challenge, and an answer without one fails closed`() = runTest {
        val api = FakeAccountApi()
        val repository = repository(FakeSessionStorage(AuthSession("t", user)), api)

        assertEquals(ApiResult.Success(CodeRequest("challenge-token", "••••••3210", true, 60)), repository.request())

        api.challengeAnswer = PhoneChallengeDto(challenge = " ", phoneHint = "••••••3210", codeSent = true)
        assertEquals(ApiResult.Failure(ApiError.UNKNOWN), repository.request())

        // A missing "codeSent" is "not sent" (the dialog then says so); a negative wait is no wait.
        api.challengeAnswer = PhoneChallengeDto(challenge = "c2", resendInSeconds = -5)
        assertEquals(ApiResult.Success(CodeRequest("c2", null, false, 0)), repository.request())
    }

    @Test
    fun `signing out the other phones saves the NEW token before returning (R8) and sends the code`() = runTest {
        val storage = FakeSessionStorage(AuthSession("old-token", user, refreshToken = "old-refresh"))
        val api = FakeAccountApi()
        val repository = repository(storage, api)

        assertEquals(ApiResult.Success(Unit), repository.signOutOtherPhones(confirmation))

        assertEquals("fresh-token", storage.savedSession?.token)
        // No new refresh secret in the answer: this phone keeps its own.
        assertEquals("old-refresh", storage.savedSession?.refreshToken)
        assertEquals(CodeConfirmationDto("challenge-token", "123456"), api.lastConfirmation)
    }

    @Test
    fun `a wrong code keeps the session, and a 401 for it asks for a sign-in without deleting anything`() = runTest {
        val storage = FakeSessionStorage(AuthSession("old-token", user))
        val api = FakeAccountApi().apply {
            signOutOthersOutcome = { throw httpError(409, """{"message":"That code is not right."}""") }
        }
        val repository = repository(storage, api)

        assertEquals(ApiResult.Failure(ApiError.CONFLICT, "That code is not right."),
            repository.signOutOtherPhones(confirmation))
        assertEquals("old-token", storage.savedSession?.token)

        api.signOutOthersOutcome = { throw httpError(401, "{}") }
        assertEquals(ApiResult.Failure(ApiError.UNAUTHORIZED), repository.signOutOtherPhones(confirmation))
        // Phase 11 C1: an expired token is not a sign-out; with no refresh secret the phone asks for a sign-in.
        assertEquals("old-token", storage.savedSession?.token)
        assertTrue(storage.savedSession!!.needsSignIn)
    }

    @Test
    fun `a new name is shown at once, and nothing else of the session changes`() = runTest {
        val storage = FakeSessionStorage(AuthSession("t", user, refreshToken = "r"))
        val api = FakeAccountApi()
        val repository = repository(storage, api)

        assertEquals(ApiResult.Success(Unit), repository.changeName("Asha Devi"))
        assertEquals(NameChangeDto("Asha Devi"), api.lastName)
        assertEquals("Asha Devi", storage.savedSession?.user?.name)
        assertEquals("t", storage.savedSession?.token)
        assertEquals("r", storage.savedSession?.refreshToken)
    }

    @Test
    fun `requesting and cancelling a deletion send the code and update the session at once (the banner)`() = runTest {
        val storage = FakeSessionStorage(AuthSession("t", user))
        val api = FakeAccountApi()
        val repository = repository(storage, api)

        assertEquals(ApiResult.Success(Instant.parse("2026-10-29T10:00:00Z")), repository.requestDeletion(confirmation))
        assertEquals(CodeConfirmationDto("challenge-token", "123456"), api.lastConfirmation)
        assertEquals(Instant.parse("2026-10-29T10:00:00Z"), storage.savedSession?.user?.deletionDueAt)
        assertEquals(Instant.parse("2026-10-22T10:00:00Z"), storage.savedSession?.user?.deletionRequestedAt)

        val second = Confirmation("second-challenge", "654321")
        assertEquals(ApiResult.Success(Unit), repository.cancelDeletion(second))
        assertEquals(CodeConfirmationDto("second-challenge", "654321"), api.lastConfirmation)
        assertFalse(storage.savedSession!!.user.deletionPending)
        assertEquals("t", storage.savedSession?.token)
    }

    // ---- AccountViewModel --------------------------------------------------------------------------------------

    @Test
    fun `the name is checked on the phone first, then saved once`() = runTest {
        val repository = FakeAccountRepository()
        val viewModel = AccountViewModel(repository, flowOf(user)) { now }

        assertEquals("Asha", viewModel.name.first())

        viewModel.onSaveName("   ")
        assertEquals(AccountMessage.NameInvalid(FieldError.REQUIRED), viewModel.message.value)
        viewModel.onSaveName("A")
        assertEquals(AccountMessage.NameInvalid(FieldError.NAME_TOO_SHORT), viewModel.message.value)
        viewModel.onSaveName("Asha 2")
        assertEquals(AccountMessage.NameInvalid(FieldError.NOT_A_NAME), viewModel.message.value)
        viewModel.onSaveName("A".repeat(61))
        assertEquals(AccountMessage.NameInvalid(FieldError.NAME_TOO_LONG), viewModel.message.value)
        assertEquals(0, repository.calls)

        viewModel.onMessageShown()
        viewModel.onSaveName("  Asha   Devi ")
        advanceUntilIdle()
        assertEquals(AccountMessage.NameSaved, viewModel.message.value)
        assertEquals(listOf("Asha Devi"), repository.names)
        assertFalse(viewModel.busy.value)
    }

    @Test
    fun `a confirmed action first texts a code, then sends the typed code with that action`() = runTest {
        val repository = FakeAccountRepository()
        val viewModel = AccountViewModel(repository, flowOf(user)) { now }

        for ((action, expected) in listOf(
            ConfirmedAction.SIGN_OUT_OTHERS to AccountMessage.OthersSignedOut,
            ConfirmedAction.DELETE to AccountMessage.DeletionScheduled(Instant.EPOCH),
            ConfirmedAction.CANCEL_DELETION to AccountMessage.DeletionCancelled
        )) {
            viewModel.onAskCode(action)
            advanceUntilIdle()
            assertEquals(CodeNeeded(action, request), viewModel.codeNeeded.value)
            assertNull("nothing happens before the code", viewModel.message.value)

            viewModel.onCodeDialogShown()
            assertNull(viewModel.codeNeeded.value)
            viewModel.onConfirm(action, confirmation)
            advanceUntilIdle()
            assertEquals(expected, viewModel.message.value)
            viewModel.onMessageShown()
        }
        assertEquals(3, repository.codeRequests)
        assertEquals(listOf("signOutOthers", "requestDeletion", "cancelDeletion"), repository.confirmed)
        assertEquals(List(3) { confirmation }, repository.confirmations)
    }

    @Test
    fun `a code that cannot be sent is shown, and no dialog opens`() = runTest {
        val repository = FakeAccountRepository().apply {
            requestResult = ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "Please wait a minute before asking again.")
        }
        val viewModel = AccountViewModel(repository, flowOf(user)) { now }

        viewModel.onAskCode(ConfirmedAction.DELETE)
        advanceUntilIdle()
        assertNull(viewModel.codeNeeded.value)
        assertEquals(AccountMessage.Failed(ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS,
            "Please wait a minute before asking again.")), viewModel.message.value)

        repository.requestResult = ApiResult.Failure(ApiError.UNAUTHORIZED)
        viewModel.onAskCode(ConfirmedAction.DELETE)
        advanceUntilIdle()
        assertTrue(viewModel.sessionExpired.value)
        assertEquals(0, repository.confirmed.size)
    }

    @Test
    fun `a refusal is shown with the server's words, and a 401 asks for a new sign-in`() = runTest {
        val repository = FakeAccountRepository().apply {
            result = ApiResult.Failure(ApiError.CONFLICT, "Your account cannot be deleted while an emergency is active.")
        }
        val viewModel = AccountViewModel(repository, flowOf(user)) { now }

        viewModel.onConfirm(ConfirmedAction.DELETE, confirmation)
        advanceUntilIdle()
        assertEquals(AccountMessage.Failed(ApiResult.Failure(ApiError.CONFLICT,
            "Your account cannot be deleted while an emergency is active.")), viewModel.message.value)

        repository.result = ApiResult.Failure(ApiError.UNAUTHORIZED)
        viewModel.onConfirm(ConfirmedAction.CANCEL_DELETION, confirmation)
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
        var failFirst = true
        val wiper = AccountDataWiper(
            listOf(
                { id -> ran += "options $id"; if (failFirst) error("disk") },
                { id -> ran += "other $id" }
            ),
            store
        )

        assertFalse(wiper.wipe(7))
        assertEquals(listOf("options 7", "other 7"), ran)
        assertEquals(PendingDeletion(7, now), store.pending.first())

        failFirst = false
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
        var challengeAnswer = PhoneChallengeDto(challenge = "challenge-token", phoneHint = "••••••3210", codeSent = true,
            resendInSeconds = 60, codeExpiresInSeconds = 300)
        var lastConfirmation: CodeConfirmationDto? = null
        var lastName: NameChangeDto? = null
        var signOutOthersOutcome: () -> AuthResponseDto = { FakeAuthApi.authResponse("fresh-token") }

        override suspend fun requestConfirmation(): PhoneChallengeDto = challengeAnswer

        override suspend fun signOutOtherPhones(body: CodeConfirmationDto): AuthResponseDto {
            lastConfirmation = body
            return signOutOthersOutcome()
        }

        override suspend fun changeName(body: NameChangeDto): UserDto {
            lastName = body
            return FakeAuthApi.userDto(name = body.name)
        }

        override suspend fun requestDeletion(body: CodeConfirmationDto): DeletionScheduledDto {
            lastConfirmation = body
            return DeletionScheduledDto("2026-10-22T10:00:00Z", "2026-10-29T10:00:00Z")
        }

        override suspend fun cancelDeletion(body: CodeConfirmationDto) {
            lastConfirmation = body
        }

        var renewOutcome: () -> AuthResponseDto = { FakeAuthApi.authResponse("renewed-token") }
        override suspend fun renewToken(): AuthResponseDto = renewOutcome()
    }

    private inner class FakeAccountRepository : AccountRepository {
        var calls = 0
        var codeRequests = 0
        var result: ApiResult<Unit>? = null
        var requestResult: ApiResult<CodeRequest> = ApiResult.Success(request)
        val names = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        val confirmations = mutableListOf<Confirmation>()

        override suspend fun request(): ApiResult<CodeRequest> {
            codeRequests++
            return requestResult
        }

        override suspend fun signOutOtherPhones(confirmation: Confirmation): ApiResult<Unit> {
            record("signOutOthers", confirmation)
            return result ?: ApiResult.Success(Unit)
        }

        override suspend fun changeName(name: String): ApiResult<Unit> {
            calls++
            names += name
            return result ?: ApiResult.Success(Unit)
        }

        override suspend fun requestDeletion(confirmation: Confirmation): ApiResult<Instant> {
            record("requestDeletion", confirmation)
            return when (val r = result) {
                is ApiResult.Failure -> r
                else -> ApiResult.Success(Instant.EPOCH)
            }
        }

        override suspend fun cancelDeletion(confirmation: Confirmation): ApiResult<Unit> {
            record("cancelDeletion", confirmation)
            return result ?: ApiResult.Success(Unit)
        }

        override suspend fun renewToken(): ApiResult<Unit> = ApiResult.Success(Unit)

        private fun record(what: String, confirmation: Confirmation) {
            calls++
            confirmed += what
            confirmations += confirmation
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
