package com.safesphere.app.feature.community

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.CommunityPolicy.ReportReadiness
import com.safesphere.app.feature.community.domain.SignalPrecision
import com.safesphere.app.feature.community.ui.Locating
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        advanceUntilIdle() // the search keeps looking for a better position for its whole window, then gives up
        assertEquals(CommunityEvent.ReportNotReady(ReportReadiness.LOCATION_TOO_ROUGH, 400f), vm.ui.value.event)
        assertTrue(repository.reports.isEmpty())
        assertNull("the search is over", vm.ui.value.locating)
    }

    // ---- Phase 17D: a visible, cancellable search for a position good enough for the ~150 m block ------------------

    private fun TestScope.withFake(): Pair<CommunityViewModel, FakeLocationProvider> {
        val fake = FakeLocationProvider()
        return CommunityViewModel(repository, outbox, fake) { now } to fake
    }

    @Test
    fun `17D - a first rough position does not end the report, a good one a moment later places it`() = runTest {
        val (vm, fake) = withFake()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        runCurrent()
        assertEquals(Locating(null), vm.ui.value.locating)                    // searching, visibly
        fake.emitFix(FakeLocationProvider.fix(accuracy = 320f))               // Wi-Fi / cell first
        runCurrent()
        assertEquals(Locating(320f), vm.ui.value.locating)                    // progress: the best so far
        assertTrue(repository.reports.isEmpty())
        fake.emitFix(FakeLocationProvider.fix(accuracy = 18f))                // then GPS
        advanceUntilIdle()
        assertEquals(listOf(SignalCategory.POOR_LIGHTING to myBlock), repository.reports)
        assertNull(vm.ui.value.locating)
        assertEquals(CommunityEvent.Recorded(30), vm.ui.value.event)
    }

    @Test
    fun `17D - Cancel stops the search quietly, nothing is sent`() = runTest {
        val (vm, fake) = withFake()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 320f))
        runCurrent()
        vm.onCancelLocating()
        advanceUntilIdle()
        assertNull(vm.ui.value.locating)
        assertFalse(vm.ui.value.busy)
        assertNull(vm.ui.value.event)
        assertTrue(repository.reports.isEmpty())
        // The same action can be started again afterwards.
        vm.onReport(SignalCategory.POOR_LIGHTING)
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 10f))
        advanceUntilIdle()
        assertEquals(1, repository.reports.size)
    }

    @Test
    fun `17D - leaving the screen stops the search and the person is told when they come back`() = runTest {
        val (vm, fake) = withFake()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        runCurrent()
        vm.onScreenHidden()
        advanceUntilIdle()
        assertEquals(CommunityEvent.SearchStopped, vm.ui.value.event)
        assertNull(vm.ui.value.locating)
        assertFalse(vm.ui.value.busy)
        assertTrue(repository.reports.isEmpty())
        fake.emitFix(FakeLocationProvider.fix(accuracy = 5f)) // a late position after leaving changes nothing
        advanceUntilIdle()
        assertTrue(repository.reports.isEmpty())
    }

    @Test
    fun `17D - a weak signal for the whole window is a rough result with the number, never a report`() = runTest {
        val (vm, fake) = withFake()
        vm.onReport(SignalCategory.ISOLATED_STRETCH)
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 250f))
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 140f))
        advanceUntilIdle()
        assertEquals(CommunityEvent.ReportNotReady(ReportReadiness.LOCATION_TOO_ROUGH, 140f), vm.ui.value.event)
        assertTrue(repository.reports.isEmpty())
    }

    @Test
    fun `17D - location switched off or not found during a report is told as such`() = runTest {
        val (vm, fake) = withFake()
        fake.locationEnabled = false
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle()
        assertEquals(CommunityEvent.NoLocation(LocationUnavailableReason.LOCATION_DISABLED), vm.ui.value.event)
        fake.locationEnabled = true
        vm.onEventShown()
        vm.onReport(SignalCategory.POOR_LIGHTING)
        advanceUntilIdle() // nothing at all in the window
        assertEquals(CommunityEvent.NoLocation(LocationUnavailableReason.NO_FIX), vm.ui.value.event)
        assertTrue(repository.reports.isEmpty())
    }

    @Test
    fun `17D - answering a block signal needs the same precise position as a report, an area signal does not`() = runTest {
        location.access = LocationAccess.APPROXIMATE
        val vm = viewModel()
        vm.onAnswer(areaSignal(id = "block", precision = SignalPrecision.BLOCK), ContributionKind.CONFIRM)
        advanceUntilIdle()
        assertEquals(CommunityEvent.ReportNotReady(ReportReadiness.NEEDS_PRECISE_LOCATION), vm.ui.value.event)
        assertTrue(repository.contributions.isEmpty())

        vm.onEventShown()
        vm.onAnswer(areaSignal(id = "area", precision = SignalPrecision.AREA), ContributionKind.CONFIRM)
        advanceUntilIdle() // an area signal (~1 km) is judged at area level: an approximate position is enough
        assertEquals(listOf(Triple("area", ContributionKind.CONFIRM, myBlock)), repository.contributions)
    }

    @Test
    fun `17D - the area picture remembers how good its position was and when it was taken`() = runTest {
        location.next = LocationUpdate.Fix(FakeLocationProvider.fix(accuracy = 300f))
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        val ready = vm.ui.value.area as AreaState.Ready
        assertEquals(300f, ready.accuracyMeters)
        assertEquals(now, ready.loadedAtMillis)
        assertFalse("precise permission, but a rough position is told apart from approximate permission", ready.approximateLocation)
        assertTrue(CommunityPolicy.isRough(ready.accuracyMeters))
    }

    @Test
    fun `17D - the area is listed from an area-level position, so it works when GPS has nothing`() = runTest {
        val fake = FakeLocationProvider()
        val vm = CommunityViewModel(repository, outbox, fake) { now }
        vm.onScreenVisible()
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 450f)) // Wi-Fi / cell only, indoors
        advanceUntilIdle()
        assertTrue(vm.ui.value.area is AreaState.Ready)
        assertEquals(listOf(myArea), repository.areaCalls)
    }

    @Test
    fun `17D - leaving while still finding the area stops that too, and coming back starts again`() = runTest {
        val fake = FakeLocationProvider()
        val vm = CommunityViewModel(repository, outbox, fake) { now }
        vm.onScreenVisible()
        runCurrent()
        vm.onScreenHidden()
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 20f))
        advanceUntilIdle()
        assertTrue("no request went out after leaving", repository.areaCalls.isEmpty())
        vm.onScreenVisible()
        runCurrent()
        fake.emitFix(FakeLocationProvider.fix(accuracy = 20f))
        advanceUntilIdle()
        assertTrue(vm.ui.value.area is AreaState.Ready)
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
