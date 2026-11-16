package com.safesphere.app.feature.membership

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.membership.data.RemoteStaffRepository
import com.safesphere.app.feature.membership.data.remote.BanDto
import com.safesphere.app.feature.membership.data.remote.BanRequestDto
import com.safesphere.app.feature.membership.data.remote.DecisionDto
import com.safesphere.app.feature.membership.data.remote.DecisionResultDto
import com.safesphere.app.feature.membership.data.remote.LogDto
import com.safesphere.app.feature.membership.data.remote.LookupDto
import com.safesphere.app.feature.membership.data.remote.NameChangeDto
import com.safesphere.app.feature.membership.data.remote.PhoneDto
import com.safesphere.app.feature.membership.data.remote.RelinkDecisionDto
import com.safesphere.app.feature.membership.data.remote.RequestDetailDto
import com.safesphere.app.feature.membership.data.remote.StaffApi
import com.safesphere.app.feature.membership.data.remote.VerifierChangeDto
import com.safesphere.app.feature.membership.data.remote.VerifierDto
import com.safesphere.app.feature.membership.data.toDomain
import com.safesphere.app.feature.membership.domain.BanReason
import com.safesphere.app.feature.membership.domain.Decision
import com.safesphere.app.feature.membership.domain.DecisionOutcome
import com.safesphere.app.feature.membership.domain.LogRow
import com.safesphere.app.feature.membership.domain.RefusalReason
import com.safesphere.app.feature.membership.ui.staff.DecideStep
import com.safesphere.app.feature.membership.ui.staff.DecisionViewModel
import com.safesphere.app.feature.membership.ui.staff.StaffAdminViewModel
import com.safesphere.app.feature.membership.ui.staff.StaffDone
import com.safesphere.app.feature.membership.ui.staff.StaffField
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.FakeStaffRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase N3 (A2): the verifiers' and admins' side on the phone. */
class StaffTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---- the repository: checked on the phone first, answers fail closed ------------------------------------------

    @Test
    fun `codes are accepted as shown or typed loosely, and only the server's alphabet`() {
        assertEquals("ABCD2345", RemoteStaffRepository.cleanCode("abcd-2345"))
        assertEquals("ABCD2345", RemoteStaffRepository.cleanCode(" ABCD 2345 "))
        for (bad in listOf("ABCD-234", "ABC0-2345", "ABCD-2345-X", "", null)) assertNull("$bad", RemoteStaffRepository.cleanCode(bad))
    }

    @Test
    fun `nothing malformed is sent and numbers leave the phone normalised`() = runTest {
        val api = RecordingStaffApi()
        val repository = RemoteStaffRepository(api, ApiCallHandler(FakeSessionStorage()))
        assertEquals(ApiError.VALIDATION, (repository.lookup("nope") as ApiResult.Failure).error)
        assertEquals(ApiError.VALIDATION, (repository.decideInPerson("r", true, "E1", null, null) as ApiResult.Failure).error)
        assertEquals(ApiError.VALIDATION, (repository.decideInPerson("r", false, null, null, null) as ApiResult.Failure).error)
        assertEquals(ApiError.VALIDATION, (repository.setVerifier("12345", true) as ApiResult.Failure).error)
        assertEquals(ApiError.VALIDATION, (repository.ban(null, " ", BanReason.HATE, null) as ApiResult.Failure).error)
        assertEquals(ApiError.VALIDATION, (repository.relink("ABCD2345", "E1") as ApiResult.Failure).error)
        assertTrue("nothing reached the server", api.sent.isEmpty())

        repository.setVerifier("98765 43210", true)
        repository.decideInPerson("ref-1", true, "e 123-6003", "  Nila Chek ", RefusalReason.OTHER)
        repository.ban(null, "e1236003", BanReason.FALSE_ID, "  " + "x".repeat(300))
        assertEquals("verifier:+919876543210:true", api.sent[0])
        assertEquals("decide:ref-1:true:E1236003:Nila Chek:null", api.sent[1])
        assertEquals("ban:null:E1236003:FALSE_ID:" + "x".repeat(RemoteStaffRepository.NOTE_MAX), api.sent[2])
    }

    @Test
    fun `an unknown outcome is a failure, never an approval, and unreadable rows are dropped`() = runTest {
        val api = RecordingStaffApi(outcome = "MAYBE")
        val repository = RemoteStaffRepository(api, ApiCallHandler(FakeSessionStorage()))
        assertEquals(ApiError.UNKNOWN, (repository.decideByPhoto("r", false, null, null, RefusalReason.UNREADABLE) as ApiResult.Failure).error)
        assertNull(RequestDetailDto("r", "CARRIER_PIGEON", "N", null, null, "MBA", 2027, null, null).toDomain())
        assertNull(RequestDetailDto("r", "ID_PHOTO", " ", null, null, "MBA", 2027, null, null).toDomain())
        assertFalse(RequestDetailDto("r", "ID_PHOTO", "Nila", null, null, "MBA", 2027, null, null).toDomain().toString().contains("Nila"))
        assertNull(BanDto(null, "HATE", null, true, false, null, null, null, null).toDomain())
        val photo = repository.photo("r") as ApiResult.Success
        assertArrayEquals(byteArrayOf(1, 2), photo.data)
    }

    // ---- one decision ---------------------------------------------------------------------------------------------

    @Test
    fun `in person - code, details, roll from the card, approve with a corrected name, then the next person`() = runTest {
        val staff = FakeStaffRepository()
        val viewModel = DecisionViewModel(staff)
        viewModel.start(null, byPhoto = false)
        viewModel.onLookup("12")
        assertTrue(viewModel.uiState.value.codeProblem)
        viewModel.onLookup("abcd-2345")
        advanceUntilIdle()
        assertEquals(DecideStep.DETAILS, viewModel.uiState.value.step)

        viewModel.onApprove("E1", "")
        assertTrue(viewModel.uiState.value.rollProblem)
        viewModel.onApprove("E1236003", "Nila Check") // same as shown: no correction sent
        advanceUntilIdle()
        assertEquals("person:ref-1:true:E1236003:null:null", staff.decisions.last())
        assertEquals(DecideStep.DONE, viewModel.uiState.value.step)

        viewModel.onNext()
        assertEquals(DecideStep.CODE, viewModel.uiState.value.step)
        assertNull(viewModel.uiState.value.detail)
        viewModel.onLookup("ABCD2345")
        advanceUntilIdle()
        viewModel.onApprove("E1236003", "Nila Chek")
        advanceUntilIdle()
        assertEquals("person:ref-1:true:E1236003:Nila Chek:null", staff.decisions.last())
    }

    @Test
    fun `a wrong roll number (409) keeps the details so it can be retyped or refused with a reason`() = runTest {
        val staff = FakeStaffRepository()
        val viewModel = DecisionViewModel(staff)
        viewModel.onLookup("ABCD2345")
        advanceUntilIdle()
        staff.decisionResult = ApiResult.Failure(ApiError.CONFLICT, "The roll number on the card does not match")
        viewModel.onApprove("E9999999", "")
        advanceUntilIdle()
        assertEquals(DecideStep.DETAILS, viewModel.uiState.value.step)
        assertEquals(ApiError.CONFLICT, viewModel.uiState.value.failure?.error)
        staff.decisionResult = ApiResult.Success(Decision(DecisionOutcome.REFUSED, "ROLL_MISMATCH"))
        viewModel.onRefuse(RefusalReason.ROLL_MISMATCH)
        advanceUntilIdle()
        assertEquals("person:ref-1:false:null:null:ROLL_MISMATCH", staff.decisions.last())
        assertEquals(DecisionOutcome.REFUSED, viewModel.uiState.value.decision?.outcome)
    }

    @Test
    fun `by photo - the photo is held in memory until the decision and then dropped`() = runTest {
        val staff = FakeStaffRepository()
        val viewModel = DecisionViewModel(staff)
        viewModel.start("ref-2", byPhoto = true)
        advanceUntilIdle()
        assertEquals(DecideStep.DETAILS, viewModel.uiState.value.step)
        assertArrayEquals(byteArrayOf(7, 7, 7), viewModel.uiState.value.photo)
        viewModel.onApprove("E1236003", "")
        advanceUntilIdle()
        assertEquals("photo:ref-2:true:E1236003:null:null", staff.decisions.last())
        assertNull("the photo leaves memory", viewModel.uiState.value.photo)

        val gone = DecisionViewModel(FakeStaffRepository().apply { queueResult = ApiResult.Success(emptyList()) })
        gone.start("ref-2", byPhoto = true)
        advanceUntilIdle()
        assertEquals("decided or withdrawn meanwhile", ApiError.NOT_FOUND, gone.uiState.value.failure?.error)
    }

    // ---- admin lists and actions ----------------------------------------------------------------------------------

    @Test
    fun `admin actions are checked on the phone first and a ban needs a number or a roll number`() = runTest {
        val staff = FakeStaffRepository()
        val viewModel = StaffAdminViewModel(staff)
        viewModel.setVerifier("123", true)
        assertEquals(setOf(StaffField.PHONE), viewModel.uiState.value.problems)
        viewModel.ban("", "", BanReason.HATE, "")
        assertEquals(setOf(StaffField.TARGET), viewModel.uiState.value.problems)
        viewModel.relink("x", "E1")
        assertEquals(setOf(StaffField.CODE, StaffField.ROLL), viewModel.uiState.value.problems)
        viewModel.correctName("9876543210", "x")
        assertEquals(setOf(StaffField.NAME), viewModel.uiState.value.problems)
        advanceUntilIdle()
        assertTrue(staff.actions.isEmpty())

        viewModel.ban("9876543210", "", BanReason.THREATS, "")
        advanceUntilIdle()
        assertEquals("ban:9876543210:null:THREATS:", staff.actions.single())
        assertEquals(StaffDone.BANNED, viewModel.uiState.value.done)
        assertEquals("the list is read again", 1, staff.bansCalls)
    }

    @Test
    fun `the log pages until a short page`() = runTest {
        val staff = FakeStaffRepository()
        val line = LogRow("VERIFIED", "IN_PERSON", null, "Admin Check", "Nila Check", null, null)
        staff.logPages = mapOf(0 to List(StaffAdminViewModel.LOG_PAGE) { line }, 1 to List(3) { line })
        val viewModel = StaffAdminViewModel(staff)
        viewModel.loadLog()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.logEnded)
        viewModel.loadLog(more = true)
        advanceUntilIdle()
        assertEquals(53, viewModel.uiState.value.log.size)
        assertTrue(viewModel.uiState.value.logEnded)
    }

    /** A StaffApi that records what would be sent. */
    private class RecordingStaffApi(private val outcome: String = "VERIFIED") : StaffApi {
        val sent = mutableListOf<String>()
        override suspend fun lookup(body: LookupDto) =
            RequestDetailDto("r", "IN_PERSON", "N", null, null, "MBA", 2027, null, null).also { sent += "lookup:${body.code}" }
        override suspend fun decideInPerson(ref: String, body: DecisionDto): DecisionResultDto {
            sent += "decide:$ref:${body.approve}:${body.rollNumber}:${body.name}:${body.reason}"
            return DecisionResultDto(outcome, null)
        }
        override suspend fun photoQueue() = emptyList<RequestDetailDto>()
        override suspend fun photo(ref: String): ResponseBody = byteArrayOf(1, 2).toResponseBody(null)
        override suspend fun decideByPhoto(ref: String, body: DecisionDto) = DecisionResultDto(outcome, body.reason)
        override suspend fun verifiers() = emptyList<VerifierDto>()
        override suspend fun setVerifier(body: VerifierChangeDto) {
            sent += "verifier:${body.phone}:${body.verifier}"
        }
        override suspend fun correctName(body: NameChangeDto) {
            sent += "name:${body.phone}:${body.name}"
        }
        override suspend fun bans() = emptyList<BanDto>()
        override suspend fun ban(body: BanRequestDto): BanDto {
            sent += "ban:${body.phone}:${body.rollNumber}:${body.reason}:${body.note}"
            return BanDto(1, body.reason, body.note, true, false, null, null, null, null)
        }
        override suspend fun lift(id: Long) = BanDto(id, "HATE", null, true, false, null, null, null, null)
        override suspend fun endMembership(body: PhoneDto) {
            sent += "end:${body.phone}"
        }
        override suspend fun relink(body: RelinkDecisionDto): DecisionResultDto {
            sent += "relink:${body.code}:${body.rollNumber}"
            return DecisionResultDto("RELINKED", null)
        }
        override suspend fun log(page: Int) = emptyList<LogDto>()
    }
}
