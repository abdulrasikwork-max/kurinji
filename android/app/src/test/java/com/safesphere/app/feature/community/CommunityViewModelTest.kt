package com.safesphere.app.feature.community

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.CommunityPolicy.ReportReadiness
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.MyContributionStatus
import com.safesphere.app.feature.community.domain.PendingReport
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.ui.AreaState
import com.safesphere.app.feature.community.ui.CommunityEvent
import com.safesphere.app.feature.community.ui.CommunityViewModel
import com.safesphere.app.testing.FakeCommunityOutbox
import com.safesphere.app.testing.FakeCommunityRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.ScriptedLocationProvider
import com.safesphere.app.testing.areaSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCommunityRepository()
    private val outbox = FakeCommunityOutbox()
    private val location = ScriptedLocationProvider()
    private var now = 1_000_000_000L

    private fun viewModel() = CommunityViewModel(repository, outbox, location) { now }

    // Fix used by FakeLocationProvider.fix(): 12.9716, 77.5946, accuracy 8 m.
    private val myBlock = GeoHash.encode(12.9716, 77.5946, GeoHash.BLOCK)
    private val myArea = myBlock.take(6)

    @Test
    fun `without permission nothing is located or sent, and the screen explains why`() = runTest {
        location.access = LocationAccess.NONE
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(AreaState.NeedsPermission, vm.ui.value.area)
        assertEquals(0, location.subscriptions)
        assertTrue(repository.areaCalls.isEmpty())
    }

    @Test
    fun `the area is asked for by its 6-character cell only, and signals are placed around the person`() = runTest {
        repository.areaResult = ApiResult.Success(listOf(areaSignal(category = SignalCategory.HELP_POINT)))
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(listOf(myArea), repository.areaCalls)
        val ready = vm.ui.value.area as AreaState.Ready
        assertEquals(1, ready.brief.havens)
        assertFalse(ready.approximateLocation)
        assertEquals(1, location.subscriptions) // one fix, then location stops
    }

    @Test
    fun `location off and no fix are shown as such`() = runTest {
        location.next = LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED)
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(AreaState.LocationProblem(LocationUnavailableReason.LOCATION_DISABLED), vm.ui.value.area)

        location.next = null // never answers
        vm.refresh()
        advanceTimeBy(CommunityViewModel.FIX_TIMEOUT_MILLIS + 1)
        advanceUntilIdle()
        assertEquals(AreaState.LocationProblem(LocationUnavailableReason.NO_FIX), vm.ui.value.area)
    }

    @Test
    fun `network failure while loading the area is shown, not hidden`() = runTest {
        repository.areaResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(AreaState.Failed(ApiResult.Failure(ApiError.NO_CONNECTION)), vm.ui.value.area)
    }

    @Test
    fun `a report sends only the 7-character block cell`() = runTest {
        val vm = viewModel()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        assertEquals(listOf(SignalCategory.POOR_LIGHTING to myBlock), repository.reports)
        assertEquals(CommunityEvent.Recorded(30), vm.ui.value.event)
    }

    @Test
    fun `reporting with approximate location or a rough fix is refused on the phone`() = runTest {
        location.access = LocationAccess.APPROXIMATE
        val vm = viewModel()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        assertEquals(CommunityEvent.ReportNotReady(ReportReadiness.NEEDS_PRECISE_LOCATION), vm.ui.value.event)

        location.access = LocationAccess.PRECISE
        location.next = LocationUpdate.Fix(FakeLocationProvider.fix(accuracy = 400f))
        vm.onEventShown()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        assertEquals(CommunityEvent.ReportNotReady(ReportReadiness.LOCATION_TOO_ROUGH), vm.ui.value.event)
        assertTrue(repository.reports.isEmpty())
    }

    @Test
    fun `offline report waits in the outbox and is sent on the next visit`() = runTest {
        repository.reportResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val vm = viewModel()
        vm.onReport(SignalCategory.ISOLATED_STRETCH)
        advanceUntilIdle()
        assertEquals(CommunityEvent.SavedOffline, vm.ui.value.event)
        assertEquals(listOf(PendingReport(SignalCategory.ISOLATED_STRETCH, myBlock, now)), outbox.stored)
        assertEquals(1, vm.ui.value.pendingReports)

        repository.reportResult = ApiResult.Success(com.safesphere.app.feature.community.domain.ContributionAccepted(20))
        vm.onEventShown()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertTrue(outbox.stored.isEmpty())
        assertEquals(0, vm.ui.value.pendingReports)
        assertEquals(CommunityEvent.OutboxSent(1), vm.ui.value.event)
    }

    @Test
    fun `a refused outbox report is dropped, a stale one is never sent`() = runTest {
        outbox.stored = listOf(
            PendingReport(SignalCategory.HELP_POINT, "tdr1v9q", now - 1000),
            PendingReport(SignalCategory.HELP_POINT, "tdr1v9r", now - 25L * 60 * 60 * 1000)
        )
        repository.reportResult = ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "limit")
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(listOf(SignalCategory.HELP_POINT to "tdr1v9q"), repository.reports)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `answering sends the person's current block as nearness, and is refused for own signals`() = runTest {
        val vm = viewModel()
        vm.onAnswer(areaSignal(id = "x"), ContributionKind.CONFIRM)
        advanceUntilIdle()
        assertEquals(listOf(Triple("x", ContributionKind.CONFIRM, myBlock)), repository.contributions)

        vm.onAnswer(areaSignal(id = "mine", yours = ContributionKind.REPORT), ContributionKind.CONFIRM)
        vm.onAnswer(areaSignal(id = "y"), ContributionKind.REPORT)
        advanceUntilIdle()
        assertEquals(1, repository.contributions.size)
    }

    @Test
    fun `the server's limit message is shown`() = runTest {
        repository.reportResult = ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "You have reached today's limit")
        val vm = viewModel()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        assertEquals(CommunityEvent.Failed(ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "You have reached today's limit")), vm.ui.value.event)
    }

    @Test
    fun `withdraw removes the person's own contribution and reloads`() = runTest {
        val mine = MyContribution("s9", SignalCategory.BROKEN_PATH, ContributionKind.REPORT, MyContributionStatus.LIVE)
        repository.mineResult = ApiResult.Success(listOf(mine))
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(listOf(mine), vm.ui.value.mine)

        repository.mineResult = ApiResult.Success(emptyList())
        vm.onWithdrawConfirmed(mine)
        advanceUntilIdle()
        assertEquals(listOf("s9"), repository.withdrawn)
        assertTrue(vm.ui.value.mine.isEmpty())
        assertEquals(CommunityEvent.Withdrawn, vm.ui.value.event)
    }

    @Test
    fun `an expired session is reported`() = runTest {
        repository.areaResult = ApiResult.Failure(ApiError.UNAUTHORIZED)
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertTrue(vm.ui.value.sessionExpired)
    }
}
