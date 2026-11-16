package com.safesphere.app.feature.membership

import com.safesphere.app.core.confirm.CodeRequest
import com.safesphere.app.core.confirm.Confirmation
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.MEMBERSHIP_NEEDED
import com.safesphere.app.core.network.needsMembership
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.ui.AccountViewModel
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.domain.SignUpTicket
import com.safesphere.app.feature.membership.data.remote.MeDto
import com.safesphere.app.feature.membership.data.remote.OutcomeDto
import com.safesphere.app.feature.membership.data.remote.ProgrammeDto
import com.safesphere.app.feature.membership.data.remote.RequestDto
import com.safesphere.app.feature.membership.data.remote.StaffDto
import com.safesphere.app.feature.membership.data.toDomain
import com.safesphere.app.feature.membership.domain.MembershipState
import com.safesphere.app.feature.membership.domain.OutcomeKind
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import com.safesphere.app.feature.membership.domain.ShortCode
import com.safesphere.app.feature.membership.domain.VerificationMethod
import com.safesphere.app.feature.membership.ui.MembershipDates
import com.safesphere.app.feature.membership.ui.MembershipNotice
import com.safesphere.app.feature.membership.ui.MembershipViewModel
import com.safesphere.app.feature.membership.ui.PhotoStep
import com.safesphere.app.feature.membership.ui.RelinkViewModel
import com.safesphere.app.testing.FakeIdPhotoSource
import com.safesphere.app.testing.FakeMembershipRepository
import com.safesphere.app.testing.FakeShot
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase N3 (A1): the applicant's side of the MIET community on the phone. */
class MembershipTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---- pure rules (the server's CommunityRulesTest cases) --------------------------------------------------------

    @Test
    fun `roll numbers are normalised like the server does and no format is assumed`() {
        assertEquals("E1236003", RollNumberPolicy.normalise(" e123-6003 "))
        assertEquals("E1236003", RollNumberPolicy.normalise("E 123/6003."))
        assertNull(RollNumberPolicy.normalise("   "))
        assertNull(RollNumberPolicy.normalise(null))
        for (ok in listOf("E1236003", "21IT045", "811521205001", "e 123-6003")) assertTrue(ok, RollNumberPolicy.accepts(ok))
        for (bad in listOf("ABCDEFG", "E12", "E1234567890123456", "E123#6003", "", null)) {
            assertFalse("$bad", RollNumberPolicy.accepts(bad))
        }
    }

    @Test
    fun `only well-formed short codes are shown`() {
        assertEquals("ABCD-2345", ShortCode.clean("ABCD-2345"))
        for (bad in listOf("ABCD2345", "ABC0-2345", "abcd-2345", "ABCD-234I", "", null)) assertNull("$bad", ShortCode.clean(bad))
    }

    @Test
    fun `the last day of a membership is 31 July in India whatever the phone's zone`() {
        assertEquals(LocalDate.of(2027, 7, 31), MembershipDates.lastDayOf(Instant.parse("2027-07-31T18:30:00Z")))
    }

    @Test
    fun `the gate code is recognised only on a 403`() {
        assertTrue(ApiResult.Failure(ApiError.FORBIDDEN, "x", MEMBERSHIP_NEEDED).needsMembership())
        assertFalse(ApiResult.Failure(ApiError.FORBIDDEN, "x", "PROFILE_NEEDED").needsMembership())
        assertFalse(ApiResult.Failure(ApiError.CONFLICT, "x", MEMBERSHIP_NEEDED).needsMembership())
        assertFalse((null as ApiResult.Failure?).needsMembership())
    }

    // ---- answers fail closed --------------------------------------------------------------------------------------

    @Test
    fun `an unknown state reads as NONE and staff flags default to false`() {
        val status = MeDto("SUPERMEMBER", null, null, null, null, null, null, StaffDto(null, null)).toDomain()
        assertEquals(MembershipState.NONE, status.state)
        assertFalse(status.verifier || status.admin || status.nameLocked)
    }

    @Test
    fun `a request with an unknown method is dropped and a code shows only on an in-person request`() {
        fun request(method: String?, code: String?) = MeDto("PENDING", null, null,
            RequestDto(method, "BTECH_IT", "B.Tech IT", 2027, code, true, 600, true, null), null, null, false, null)
        assertNull(request("CARRIER_PIGEON", "ABCD-2345").toDomain().request)
        val photo = request("ID_PHOTO", "ABCD-2345").toDomain().request!!
        assertNull(photo.code)
        assertFalse(photo.codeAlive)
        assertTrue(photo.photoUploaded)
        val inPerson = request("IN_PERSON", "ABCD-2345").toDomain().request!!
        assertEquals("ABCD-2345", inPerson.code)
        assertFalse("never 'uploaded' for in person", inPerson.photoUploaded)
        assertNull(request("IN_PERSON", "<script>").toDomain().request!!.code)
        assertFalse("the code is never printed", inPerson.toString().contains("ABCD"))
    }

    @Test
    fun `outcomes of an unknown kind are dropped and programmes need a label and years`() {
        assertNull(MeDto("NONE", null, null, null, OutcomeDto("PUNISHED", "X", null), null, false, null).toDomain().outcome)
        assertEquals(OutcomeKind.REFUSED,
            MeDto("NONE", null, null, null, OutcomeDto("REFUSED", "UNREADABLE", null), null, false, null).toDomain().outcome?.kind)
        assertNull(ProgrammeDto("MBA", null, "PG", 2026, 2028).toDomain())
        assertNull(ProgrammeDto("MBA", "MBA", "PG", 2028, 2026).toDomain())
        assertEquals(listOf(2026, 2027, 2028), ProgrammeDto("MBA", "MBA", "PG", 2026, 2028).toDomain()!!.years)
    }

    // ---- the screen -----------------------------------------------------------------------------------------------

    @Test
    fun `in person is the default and a bad form never reaches the server`() = runTest {
        val repository = FakeMembershipRepository()
        val viewModel = MembershipViewModel(repository, FakeIdPhotoSource())
        viewModel.load()
        advanceUntilIdle()
        assertEquals(VerificationMethod.IN_PERSON, viewModel.uiState.value.method)
        assertEquals(2, viewModel.uiState.value.programmes.size)

        viewModel.onAsk("E12")
        assertTrue(viewModel.uiState.value.rollProblem)
        assertTrue(viewModel.uiState.value.programmeProblem)
        viewModel.onProgramme(repository.programmeList[1])
        viewModel.onAsk("E1236003")
        assertTrue("a year is needed", viewModel.uiState.value.yearProblem)
        advanceUntilIdle()
        assertTrue(repository.starts.isEmpty())
    }

    @Test
    fun `asking shows the code once, keeps it only while alive, and a new code replaces it`() = runTest {
        val repository = FakeMembershipRepository()
        val viewModel = MembershipViewModel(repository, FakeIdPhotoSource())
        viewModel.load()
        advanceUntilIdle()
        viewModel.onProgramme(repository.programmeList[0])
        viewModel.onYear(2027)
        viewModel.onAsk("e123-6003")
        advanceUntilIdle()
        assertEquals(Triple(VerificationMethod.IN_PERSON, "e123-6003", "BTECH_IT" to 2027), repository.starts.single())
        assertEquals("ABCD-2345", viewModel.uiState.value.code)
        assertEquals(10L, viewModel.uiState.value.codeMinutes)
        assertFalse("the roll number is never in the state", viewModel.uiState.value.toString().contains("6003"))
        assertFalse(viewModel.uiState.value.toString().contains("ABCD"))

        // A plain read (the server never repeats the code) keeps it while alive...
        repository.statusResult = ApiResult.Success(FakeMembershipRepository.pending(null, alive = true))
        viewModel.load()
        advanceUntilIdle()
        assertEquals("ABCD-2345", viewModel.uiState.value.code)
        // ...and drops it once it ran out.
        repository.statusResult = ApiResult.Success(FakeMembershipRepository.pending(null, alive = false))
        viewModel.load()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.code)

        viewModel.onNewCode()
        advanceUntilIdle()
        assertEquals("WXYZ-6789", viewModel.uiState.value.code)
        assertEquals(MembershipNotice.CODE_RENEWED, viewModel.uiState.value.notice)
    }

    @Test
    fun `the ID photo is prepared, sent and its file deleted, also when it cannot be read or sent`() = runTest {
        val repository = FakeMembershipRepository()
        val photos = FakeIdPhotoSource()
        val viewModel = MembershipViewModel(repository, photos)
        repository.statusResult = ApiResult.Success(FakeMembershipRepository.photoPending(uploaded = false))
        viewModel.load()
        advanceUntilIdle()

        val sent = FakeShot()
        viewModel.onPhotoTaken(sent)
        advanceUntilIdle()
        assertEquals(1, repository.uploads.size)
        assertEquals(listOf<Any>(sent), photos.discarded)
        assertEquals(MembershipNotice.PHOTO_SENT, viewModel.uiState.value.notice)
        assertTrue(viewModel.uiState.value.status!!.request!!.photoUploaded)

        photos.prepared = null
        val unreadable = FakeShot()
        viewModel.onPhotoTaken(unreadable)
        advanceUntilIdle()
        assertEquals(PhotoStep.UNREADABLE, viewModel.uiState.value.photo)
        assertTrue(unreadable in photos.discarded)
        assertEquals("nothing sent", 1, repository.uploads.size)

        photos.prepared = byteArrayOf(9)
        repository.uploadResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val failed = FakeShot()
        viewModel.onPhotoTaken(failed)
        advanceUntilIdle()
        assertEquals(PhotoStep.FAILED, viewModel.uiState.value.photo)
        assertTrue(failed in photos.discarded)

        // The camera closed without a photo: the empty target is deleted too.
        val cancelled = FakeShot()
        viewModel.pendingShot = cancelled
        viewModel.onPhotoCancelled()
        assertTrue(cancelled in photos.discarded)
        assertNull(viewModel.pendingShot)
    }

    @Test
    fun `withdrawing forgets the code and reads the state again`() = runTest {
        val repository = FakeMembershipRepository()
        val viewModel = MembershipViewModel(repository, FakeIdPhotoSource())
        viewModel.onProgramme(repository.programmeList[0])
        viewModel.onYear(2027)
        viewModel.onAsk("E1236003")
        advanceUntilIdle()
        viewModel.onWithdraw()
        advanceUntilIdle()
        assertEquals(1, repository.withdrawCalls)
        assertNull(viewModel.uiState.value.code)
        assertEquals(MembershipState.NONE, viewModel.uiState.value.status!!.state)
        assertEquals(MembershipNotice.WITHDRAWN, viewModel.uiState.value.notice)
    }

    // ---- re-link (N-25) -------------------------------------------------------------------------------------------

    @Test
    fun `re-linking uses the sign-up ticket once and shows the admin's code`() = runTest {
        val repository = FakeMembershipRepository()
        val pending = PendingVerification().apply { signUpWith(SignUpTicket("ticket-1", "••••••3333", 1800)) }
        val viewModel = RelinkViewModel(repository, pending)
        viewModel.onGetCode("E1")
        assertTrue(viewModel.uiState.value.rollProblem)
        viewModel.onGetCode("E1236003")
        advanceUntilIdle()
        assertEquals("ticket-1" to "E1236003", repository.relinks.single())
        assertEquals("PQRS-2345", viewModel.uiState.value.code?.code)
        assertNull("the ticket is used up", pending.ticket.value)
    }

    @Test
    fun `re-linking without a ticket or with an ended one asks for the number again`() = runTest {
        val repository = FakeMembershipRepository()
        assertTrue(RelinkViewModel(repository, PendingVerification()).uiState.value.lost)

        val pending = PendingVerification().apply { signUpWith(SignUpTicket("old", null, 1800)) }
        repository.relinkResult = ApiResult.Failure(ApiError.VALIDATION)
        val viewModel = RelinkViewModel(repository, pending)
        viewModel.onGetCode("E1236003")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.lost)
        assertNull(pending.ticket.value)
    }

    // ---- the name lock (N-24) -------------------------------------------------------------------------------------

    @Test
    fun `a checked name is read-only on Account and a 409 NAME_LOCKED locks it too`() = runTest {
        val membership = FakeMembershipRepository()
        membership.statusResult = ApiResult.Success(FakeMembershipRepository.status(MembershipState.MEMBER, nameLocked = true))
        val account = NameOnlyAccountRepository()
        var refreshed = 0
        val viewModel = AccountViewModel(account, flowOf(AuthUser(1, "Nila Check")), membership = membership,
            refreshUser = { refreshed++ })
        viewModel.refreshNameLock()
        advanceUntilIdle()
        assertTrue(viewModel.nameLocked.value)
        assertEquals("the account is read again, so a corrected name shows", 1, refreshed)
        var seen = 0
        MembershipViewModel(membership, FakeIdPhotoSource(), refreshUser = { seen++ }).load(withProgrammes = false)
        advanceUntilIdle()
        assertEquals(1, seen)
        viewModel.onSaveName("Nila Changed")
        advanceUntilIdle()
        assertTrue("never sent", account.names.isEmpty())

        val other = NameOnlyAccountRepository(ApiResult.Failure(ApiError.CONFLICT, "locked", AccountViewModel.NAME_LOCKED))
        val unlocked = AccountViewModel(other, flowOf(AuthUser(2, "Meera Test")))
        unlocked.onSaveName("Meera Tested")
        advanceUntilIdle()
        assertEquals(listOf("Meera Tested"), other.names)
        assertTrue(unlocked.nameLocked.value)
    }

    private class NameOnlyAccountRepository(private val answer: ApiResult<Unit> = ApiResult.Success(Unit)) : AccountRepository {
        val names = mutableListOf<String>()
        override suspend fun request(): ApiResult<CodeRequest> = ApiResult.Failure(ApiError.UNKNOWN)
        override suspend fun signOutOtherPhones(confirmation: Confirmation): ApiResult<Unit> = ApiResult.Failure(ApiError.UNKNOWN)
        override suspend fun changeName(name: String): ApiResult<Unit> {
            names += name
            return answer
        }
        override suspend fun requestDeletion(confirmation: Confirmation): ApiResult<Instant> = ApiResult.Failure(ApiError.UNKNOWN)
        override suspend fun cancelDeletion(confirmation: Confirmation): ApiResult<Unit> = ApiResult.Failure(ApiError.UNKNOWN)
        override suspend fun renewToken(): ApiResult<Unit> = ApiResult.Success(Unit)
    }
}
