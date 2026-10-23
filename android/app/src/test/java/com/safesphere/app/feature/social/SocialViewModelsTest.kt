package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.social.domain.ConnectResult
import com.safesphere.app.feature.social.domain.ConnectStatus
import com.safesphere.app.feature.social.domain.InviteItem
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.ProfileInputPolicy
import com.safesphere.app.feature.social.domain.Relationship
import com.safesphere.app.feature.social.domain.RequestItem
import com.safesphere.app.feature.social.ui.BlockedMutedViewModel
import com.safesphere.app.feature.social.ui.ConnectionsViewModel
import com.safesphere.app.feature.social.ui.PersonProfileViewModel
import com.safesphere.app.feature.social.ui.ProfileSetupViewModel
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.FakeSocialRepository.Companion.BALA
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 13A: the four social screens' ViewModels against an in-memory server. */
class SocialViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSocialRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)

    // ---- profile set-up ---------------------------------------------------------------------------------------

    @Test
    fun `profile - an account from before 13A confirms an adult age first, then gets the form`() = runTest {
        repository.ageConfirmedResult = ApiResult.Success(false)
        val vm = ProfileSetupViewModel(repository, clock)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsAgeConfirmation)
        assertFalse(vm.uiState.value.formReady)

        vm.onConfirmAge()
        assertEquals(FieldError.REQUIRED, vm.uiState.value.dateOfBirthError)
        vm.onDateOfBirthChosen(LocalDate.of(2009, 1, 1))
        vm.onConfirmAge()
        assertEquals(FieldError.UNDER_AGE, vm.uiState.value.dateOfBirthError)
        assertNull(repository.confirmedAge) // the server is never asked with an under-age date

        vm.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        vm.onConfirmAge()
        advanceUntilIdle()
        assertEquals(LocalDate.of(1998, 5, 14), repository.confirmedAge)
        assertFalse(vm.uiState.value.needsAgeConfirmation)
        assertTrue(vm.uiState.value.formReady)
        assertNull(vm.uiState.value.profile)       // no profile yet: an empty form
        assertNull(vm.uiState.value.dateOfBirth)   // forgotten once confirmed
    }

    @Test
    fun `profile - loaded once into the form, local problems stop the save, a good form is saved`() = runTest {
        val vm = ProfileSetupViewModel(repository, clock)
        advanceUntilIdle()
        assertEquals(FakeSocialRepository.ME, vm.uiState.value.prefill)
        vm.onPrefilled()
        assertNull(vm.uiState.value.prefill)

        vm.onSave(ProfileInput("1x", "", "", emptySet(), emptySet(), ""))
        assertEquals(ProfileInputPolicy.Problem.HANDLE_FORMAT, vm.uiState.value.problems.handle)
        assertEquals(ProfileInputPolicy.Problem.NAME_REQUIRED, vm.uiState.value.problems.displayName)
        assertFalse(repository.calls.contains("saveProfile"))

        vm.onSave(ProfileInput("asha_new", "Asha D", "", emptySet(), emptySet(), ""))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saved)
        assertEquals("asha_new", vm.uiState.value.profile?.handle)
        assertTrue(vm.uiState.value.formReady)
    }

    @Test
    fun `profile - a server refusal (taken handle) is shown and the form stays`() = runTest {
        repository.saveResult = ApiResult.Failure(ApiError.CONFLICT, "That handle is not available")
        val vm = ProfileSetupViewModel(repository, clock)
        advanceUntilIdle()
        vm.onSave(ProfileInput("taken_one", "Asha", "", emptySet(), emptySet(), ""))
        advanceUntilIdle()
        assertEquals("That handle is not available", vm.uiState.value.failure?.message)
        assertTrue(vm.uiState.value.formReady)
        assertFalse(vm.uiState.value.saved)
    }

    // ---- connections ------------------------------------------------------------------------------------------

    @Test
    fun `connections - no profile yet shows the make-a-profile state and loads nothing else`() = runTest {
        repository.ownProfileResult = ApiResult.Success(null)
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasLoaded)
        assertNull(vm.uiState.value.me)
    }

    @Test
    fun `connections - accept moves a request into connections, decline and cancel remove it`() = runTest {
        repository.incoming += RequestItem("r1", BALA)
        repository.incoming += RequestItem("r2", BALA.copy(publicId = "c-id", handle = "chitra", displayName = "Chitra"))
        repository.outgoing += RequestItem("r3", BALA.copy(publicId = "d-id", handle = "devi", displayName = "Devi"))
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.incoming.size)

        vm.onAccept(vm.uiState.value.incoming.first { it.requestId == "r1" })
        advanceUntilIdle()
        assertEquals(listOf(BALA), vm.uiState.value.connections)

        vm.onDecline(vm.uiState.value.incoming.single())
        vm.onCancel(vm.uiState.value.outgoing.single())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.incoming.isEmpty())
        assertTrue(vm.uiState.value.outgoing.isEmpty())
        assertTrue(repository.calls.containsAll(listOf("accept:r1", "decline:r2", "cancel:r3")))
    }

    @Test
    fun `connections - a new invite code is handed to the screen once and then forgotten`() = runTest {
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        vm.onCreateInvite()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.newInvite)
        assertEquals(listOf("invite-1"), vm.uiState.value.invites.map(InviteItem::id))
        vm.onNewInviteShown()
        assertNull(vm.uiState.value.newInvite)
    }

    @Test
    fun `connections - redeeming checks the code length first and reports who you connected with`() = runTest {
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        vm.onRedeem("too short")
        assertTrue(vm.uiState.value.codeInvalid)
        assertFalse(repository.calls.contains("redeem"))

        vm.onRedeem("01234 56789 ABCDE FGHJK MNPQR S")
        advanceUntilIdle()
        assertEquals(BALA, vm.uiState.value.connectedWith)
        assertEquals("01234 56789 ABCDE FGHJK MNPQR S", repository.redeemedCode)
    }

    @Test
    fun `connections - a server refusal of a code is shown`() = runTest {
        repository.redeemResult = ApiResult.Failure(ApiError.NOT_FOUND)
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        vm.onRedeem("0123456789ABCDEFGHJKMNPQRS")
        advanceUntilIdle()
        assertEquals(ApiError.NOT_FOUND, vm.uiState.value.failure?.error)
        assertNull(vm.uiState.value.connectedWith)
    }

    @Test
    fun `connections - find accepts only a well-formed handle and opens it normalised`() = runTest {
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        vm.onFind("no")
        assertTrue(vm.uiState.value.handleInvalid)
        assertNull(vm.uiState.value.openHandle)
        vm.onFind(" @Bala_R ")
        assertEquals("bala_r", vm.uiState.value.openHandle)
        vm.onOpenHandled()
        assertNull(vm.uiState.value.openHandle)
    }

    @Test
    fun `connections - a session that ended is reported once`() = runTest {
        repository.ownProfileResult = ApiResult.Failure(ApiError.UNAUTHORIZED)
        val vm = ConnectionsViewModel(repository)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    // ---- someone's profile ------------------------------------------------------------------------------------

    @Test
    fun `person - connect, then remove, follow what the server says`() = runTest {
        val vm = PersonProfileViewModel(repository, handle = "bala_r", profileId = null)
        advanceUntilIdle()
        assertEquals("bala_r", repository.lookedUpHandle)
        assertEquals(Relationship.NONE, vm.uiState.value.person?.relationship)

        repository.connectResult = ApiResult.Success(ConnectResult(ConnectStatus.CONNECTED, BALA))
        vm.onConnect()
        advanceUntilIdle()
        assertEquals(Relationship.CONNECTED, vm.uiState.value.person?.relationship)

        vm.onRemoveConnectionConfirmed()
        advanceUntilIdle()
        assertEquals(Relationship.NONE, vm.uiState.value.person?.relationship)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `person - not found and not visible look the same`() = runTest {
        repository.personResult = ApiResult.Failure(ApiError.NOT_FOUND)
        val vm = PersonProfileViewModel(repository, handle = "nobody_here", profileId = null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notFound)
        assertNull(vm.uiState.value.person)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `person - blocking closes the screen`() = runTest {
        val vm = PersonProfileViewModel(repository, handle = null, profileId = "bala-id")
        advanceUntilIdle()
        vm.onBlockConfirmed()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.closed)
        assertTrue(repository.calls.contains("block:bala-id"))

        // Emulator finding: "closed" must be consumed once, or the next state closes a second screen.
        vm.onClosedHandled()
        vm.onMessageShown()
        assertFalse(vm.uiState.value.closed)
    }

    @Test
    fun `person - a failed action keeps the person and shows the failure`() = runTest {
        val vm = PersonProfileViewModel(repository, handle = null, profileId = "bala-id")
        advanceUntilIdle()
        repository.actionFailure = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onMute()
        advanceUntilIdle()
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.failure?.error)
        assertNotNull(vm.uiState.value.person)
        assertFalse(vm.uiState.value.isBusy)
    }

    // ---- blocked and muted ------------------------------------------------------------------------------------

    @Test
    fun `blocked and muted - unblock and unmute remove the person from the own lists`() = runTest {
        repository.blocked += BALA
        repository.muted += BALA
        val vm = BlockedMutedViewModel(repository)
        advanceUntilIdle()
        assertEquals(listOf(BALA), vm.uiState.value.blocked)

        vm.onUnblockConfirmed(BALA)
        vm.onUnmute(BALA)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.blocked.isEmpty())
        assertTrue(vm.uiState.value.muted.isEmpty())
    }
}
