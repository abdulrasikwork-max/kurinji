package com.safesphere.app.feature.community

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.apiErrorForHttpCode
import com.safesphere.app.feature.community.data.remote.PublicSignalDto
import com.safesphere.app.feature.community.data.remote.StandingDto
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalReason
import com.safesphere.app.feature.community.ui.CommunityEvent
import com.safesphere.app.feature.community.ui.CommunityViewModel
import com.safesphere.app.testing.FakeCommunityOutbox
import com.safesphere.app.testing.FakeCommunityRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.ScriptedLocationProvider
import com.safesphere.app.testing.areaSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 7 on the phone: confidence mapping, flags, paused contributions, appeals, 403 handling. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityTrustTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCommunityRepository()
    private val location = ScriptedLocationProvider()
    private fun viewModel() = CommunityViewModel(repository, FakeCommunityOutbox(), location) { 1_000_000_000L }
    private val myBlock = GeoHash.encode(12.9716, 77.5946, GeoHash.BLOCK)

    @Test
    fun `403 maps to FORBIDDEN`() {
        assertEquals(ApiError.FORBIDDEN, apiErrorForHttpCode(403))
    }

    @Test
    fun `confidence and reasons are read, unknown ones skipped, and an older server never claims certainty`() {
        val dto = PublicSignalDto("s1", "POOR_LIGHTING", "BLOCK", "tdr1v9q", 12.97, 77.59, "SEVERAL", "TODAY", false,
            null, "HIGH", listOf("CONFIRMED_BY_OTHERS", "SOMETHING_NEW"), true)
        val signal = dto.toDomain()!!
        assertEquals(SignalConfidence.HIGH, signal.confidence)
        assertEquals(listOf(SignalReason.CONFIRMED_BY_OTHERS), signal.reasons)
        assertTrue(signal.youFlagged)

        val old = PublicSignalDto("s2", "POOR_LIGHTING", "AREA", "tdr1v9", 12.97, 77.59, "SINGLE", "TODAY", false, null)
        assertEquals(SignalConfidence.LOW, old.toDomain()!!.confidence)
    }

    @Test
    fun `standing is parsed as UTC and carries no score`() {
        val standing = StandingDto(true, "2026-10-27T13:10:00.123456", "TARGETS_SOMEONE", "OPEN", false).toDomain()
        assertTrue(standing.contributionsPaused)
        assertEquals(1_793_106_600_123L, standing.pausedUntilMillis)
        assertEquals(ModerationReason.TARGETS_SOMEONE, standing.reason)
        assertEquals(AppealStatus.OPEN, standing.appeal)
        assertNull(StandingDto(false, "not a date", null, null, null).toDomain().pausedUntilMillis)
    }

    @Test
    fun `one flag or answer per person, never on your own signal`() {
        assertTrue(CommunityPolicy.canFlag(areaSignal()))
        assertFalse(CommunityPolicy.canFlag(areaSignal(yours = ContributionKind.REPORT)))
        assertFalse(CommunityPolicy.canFlag(areaSignal().copy(youFlagged = true)))
        assertFalse(CommunityPolicy.canAnswer(areaSignal().copy(youFlagged = true)))
    }

    @Test
    fun `a flag sends only the signal id, the reason and the current block cell`() = runTest {
        val vm = viewModel()
        vm.onFlag(areaSignal(id = "s9"), FlagReason.TARGETS_SOMEONE)
        advanceUntilIdle()
        assertEquals(listOf(Triple("s9", FlagReason.TARGETS_SOMEONE, myBlock)), repository.flags)
        assertTrue(vm.ui.value.event == CommunityEvent.Flagged || repository.areaCalls.isNotEmpty())
    }

    @Test
    fun `while paused nothing is sent, and an appeal can be asked for once`() = runTest {
        repository.standingResult = ApiResult.Success(CommunityStanding(true, null, ModerationReason.FALSE_INFORMATION, null, true))
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertTrue(vm.ui.value.standing.contributionsPaused)

        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        vm.onFlag(areaSignal(), FlagReason.FALSE_INFORMATION)
        advanceUntilIdle()
        assertTrue(repository.reports.isEmpty())
        assertTrue(repository.flags.isEmpty())

        repository.standingResult = ApiResult.Success(CommunityStanding(true, null, null, AppealStatus.OPEN, false))
        vm.onAppeal()
        advanceUntilIdle()
        assertEquals(1, repository.appeals)
        assertFalse(vm.ui.value.standing.canAppeal)
        vm.onAppeal()
        advanceUntilIdle()
        assertEquals(1, repository.appeals)
    }

    @Test
    fun `a 403 on a write reloads the standing so the pause banner appears`() = runTest {
        repository.reportResult = ApiResult.Failure(ApiError.FORBIDDEN, "Your community contributions are paused for now.")
        val vm = viewModel()
        repository.standingResult = ApiResult.Success(CommunityStanding(true, null, null, null, true))
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        assertTrue(vm.ui.value.standing.contributionsPaused)
    }
}
