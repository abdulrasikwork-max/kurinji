package com.safesphere.app.feature.intelligence

import com.safesphere.app.core.location.DestinationLookup
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalFreshness
import com.safesphere.app.feature.intelligence.data.remote.RegionAreaDto
import com.safesphere.app.feature.intelligence.data.remote.RegionPackDto
import com.safesphere.app.feature.intelligence.data.remote.RegionSignalDto
import com.safesphere.app.feature.intelligence.domain.AreaOutlook
import com.safesphere.app.feature.intelligence.domain.ConcernLevel
import com.safesphere.app.feature.intelligence.domain.CorridorBriefPolicy
import com.safesphere.app.feature.intelligence.domain.CorridorBriefPolicy.Coverage
import com.safesphere.app.feature.intelligence.domain.CorridorBriefPolicy.Havens
import com.safesphere.app.feature.intelligence.domain.InformationLevel
import com.safesphere.app.feature.intelligence.domain.IntelligenceRepository
import com.safesphere.app.feature.intelligence.domain.PictureReason
import com.safesphere.app.feature.intelligence.domain.RegionArea
import com.safesphere.app.feature.intelligence.domain.RegionPack
import com.safesphere.app.feature.intelligence.domain.RegionSignal
import com.safesphere.app.feature.intelligence.ui.WayCheckState
import com.safesphere.app.feature.intelligence.ui.WayCheckViewModel
import com.safesphere.app.feature.safewalk.domain.MovementPolicy
import com.safesphere.app.testing.FakeDestinationResolver
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.ScriptedLocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 8D: the corridor, the brief's two-channel rules, fail-safe pack mapping, and the screen flow. */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckTheWayTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val start = GeoPoint(12.9716, 77.5946)
    private val destination = GeoPoint(12.9851, 77.5946) // ~1.5 km north
    private val corridor = CorridorBriefPolicy.corridor(start, destination,
        MovementPolicy().corridorHalfWidthMeters(start, destination))
    private val middle = GeoPoint(12.9783, 77.5946)
    private val middleBlock = GeoHash.encode(middle, GeoHash.BLOCK)
    private val farBlock = GeoHash.encode(12.9783, 77.6246, GeoHash.BLOCK) // ~3 km east

    private fun signal(category: SignalCategory, cell: String = middleBlock,
                       confidence: SignalConfidence = SignalConfidence.MODERATE,
                       freshness: SignalFreshness = SignalFreshness.THIS_WEEK, disputed: Boolean = false) =
        RegionSignal(category, cell, confidence, freshness, disputed)

    private fun area(cell: String, information: InformationLevel = InformationLevel.SOME,
                     reasons: List<PictureReason> = emptyList()) =
        RegionArea(cell, AreaOutlook.NO_CONFIRMED_CONCERNS, ConcernLevel.NONE_CONFIRMED, information, reasons, emptyList(), false)

    private fun lineAreas(): List<String> =
        (0..15).map { i -> GeoHash.encode(start.latitude + (destination.latitude - start.latitude) * i / 15, start.longitude, 6) }.distinct()

    private fun briefOf(signals: List<RegionSignal>, areas: List<RegionArea> = emptyList(), night: Boolean = false):
        CorridorBriefPolicy.CorridorBrief {
        val result = CorridorBriefPolicy.brief(corridor, listOf(RegionPack("tdr1v", true, areas, signals)), night)
        return (result as CorridorBriefPolicy.Result.Ready).brief
    }

    // ---- Corridor and regions -----------------------------------------------------------------------------------

    @Test
    fun `a short walk needs one to a few 5-character regions, a very long one is not checked`() {
        assertEquals(CorridorBriefPolicy.MAX_HALF_WIDTH_METERS, corridor.halfWidthMeters, 0.0)
        val plan = CorridorBriefPolicy.regionsFor(corridor) as CorridorBriefPolicy.Plan.Regions
        assertTrue(plan.cells.size in 1..CorridorBriefPolicy.MAX_REGIONS)
        assertTrue(plan.cells.all { GeoHash.isValid(it, GeoHash.REGION) })
        assertTrue(GeoHash.encode(start, GeoHash.REGION) in plan.cells)
        assertTrue(GeoHash.encode(destination, GeoHash.REGION) in plan.cells)

        val far = CorridorBriefPolicy.corridor(start, GeoPoint(13.25, 77.5946), 2000.0)
        assertEquals(CorridorBriefPolicy.Plan.TooLong, CorridorBriefPolicy.regionsFor(far))
    }

    @Test
    fun `only signals around the way are in the brief`() {
        val brief = briefOf(listOf(signal(SignalCategory.POOR_LIGHTING), signal(SignalCategory.HARASSMENT, farBlock)))
        assertEquals(listOf(SignalCategory.POOR_LIGHTING), brief.concernCategories)
        assertTrue(CorridorBriefPolicy.inCorridor(corridor, middle))
        assertFalse(CorridorBriefPolicy.inCorridor(corridor, GeoHash.centre(farBlock)))
    }

    // ---- Two channels (§16.1) -----------------------------------------------------------------------------------

    @Test
    fun `places to go for help never cancel a concern, and a contradiction is shown as disagreement`() {
        val brief = briefOf(listOf(signal(SignalCategory.POOR_LIGHTING), signal(SignalCategory.WELL_LIT_BUSY),
            signal(SignalCategory.HELP_POINT, GeoHash.encode(12.9750, 77.5950, 7))))
        assertEquals(listOf(SignalCategory.POOR_LIGHTING), brief.concernCategories)
        assertTrue(brief.hasConfirmedConcerns)
        assertTrue(brief.conflicting)
        assertEquals(Havens.SEVERAL, brief.havens)
    }

    @Test
    fun `unconfirmed and recurring are uncertainty notes, never concerns`() {
        val areas = listOf(area(GeoHash.encode(middle, 6), InformationLevel.LITTLE,
            listOf(PictureReason.UNCONFIRMED_REPORTS, PictureReason.RECURRING_CONCERNS)))
        val brief = briefOf(emptyList(), areas)
        assertFalse(brief.hasConfirmedConcerns)
        assertTrue(brief.unconfirmedNearby)
        assertTrue(brief.recurringNearby)
    }

    @Test
    fun `how much is known is reported, unknown is never treated as fine`() {
        assertEquals(Coverage.MOSTLY_UNKNOWN, briefOf(emptyList()).coverage)
        assertEquals(Coverage.MOSTLY_KNOWN, briefOf(emptyList(), lineAreas().map { area(it) }).coverage)
        assertEquals(Coverage.MOSTLY_UNKNOWN, briefOf(emptyList(), lineAreas().map { area(it, InformationLevel.NONE) }).coverage)
    }

    @Test
    fun `strong, today and night order are carried`() {
        val brief = briefOf(listOf(signal(SignalCategory.BROKEN_PATH), signal(SignalCategory.POOR_LIGHTING),
            signal(SignalCategory.FOLLOWING, confidence = SignalConfidence.HIGH, freshness = SignalFreshness.TODAY)), night = true)
        assertEquals(SignalCategory.POOR_LIGHTING, brief.concernCategories.first())
        assertTrue(brief.strongConcern)
        assertTrue(brief.incidentToday)
    }

    @Test
    fun `an incomplete or missing pack gives no brief at all`() {
        assertEquals(CorridorBriefPolicy.Result.Incomplete,
            CorridorBriefPolicy.brief(corridor, listOf(RegionPack("tdr1v", false, emptyList(), emptyList())), false))
        assertEquals(CorridorBriefPolicy.Result.Incomplete, CorridorBriefPolicy.brief(corridor, emptyList(), false))
    }

    // ---- Mapping fails safe -------------------------------------------------------------------------------------

    @Test
    fun `anything the app does not understand makes the pack incomplete`() {
        val ok = RegionSignalDto("POOR_LIGHTING", "CONDITION", middleBlock, "MODERATE", "TODAY", false)
        val okArea = RegionAreaDto("tdr1v9", "TAKE_CARE", "SOME", "LITTLE", listOf("CONFIRMED_CONDITION", "NEW_REASON"),
            listOf("POOR_LIGHTING"), false)
        val good = RegionPackDto("tdr1v", "rules-1", true, listOf(okArea), listOf(ok)).toDomain("tdr1v")
        assertTrue(good.complete)
        assertEquals(listOf(PictureReason.CONFIRMED_CONDITION), good.areas.single().reasons)

        val unknownCategory = ok.copy(category = "SOMETHING_NEW")
        assertFalse(RegionPackDto("tdr1v", "rules-1", true, listOf(okArea), listOf(unknownCategory)).toDomain("tdr1v").complete)
        assertFalse(RegionPackDto("tdr1v", "rules-1", true, listOf(okArea), listOf(ok.copy(confidence = "LOW"))).toDomain("tdr1v").complete)
        assertFalse(RegionPackDto("tdr1v", "rules-1", true, listOf(okArea.copy(outlook = "ALL_CLEAR")), listOf(ok)).toDomain("tdr1v").complete)
        assertFalse(RegionPackDto("tdr1w", "rules-1", true, listOf(okArea), listOf(ok)).toDomain("tdr1v").complete)
        assertFalse(RegionPackDto("tdr1v", "rules-1", false, listOf(okArea), listOf(ok)).toDomain("tdr1v").complete)
        assertFalse(RegionPackDto("tdr1v", "rules-1", true, null, listOf(ok)).toDomain("tdr1v").complete)
        assertFalse(good.toString().contains("tdr1v"))
    }

    // ---- Screen flow --------------------------------------------------------------------------------------------

    private class FakeIntelligenceRepository : IntelligenceRepository {
        val asked = mutableListOf<String>()
        var result: (String) -> ApiResult<RegionPack> = { ApiResult.Success(RegionPack(it, true, emptyList(), emptyList())) }
        override suspend fun region(regionCell: String): ApiResult<RegionPack> = result(regionCell).also { asked += regionCell }
    }

    private val repository = FakeIntelligenceRepository()
    private val resolver = FakeDestinationResolver(DestinationLookup.Found(destination, "MG Road"))
    private val location = ScriptedLocationProvider()
    private fun viewModel() = WayCheckViewModel(location, resolver, repository, localHour = { 12 })

    @Test
    fun `only 5-character region cells are sent, and the brief is computed on the phone`() = runTest {
        val vm = viewModel()
        vm.onCheck("MG Road")
        advanceUntilIdle()
        val ready = vm.state.value as WayCheckState.Ready
        assertEquals("MG Road", ready.destinationDescription)
        assertEquals(Coverage.MOSTLY_UNKNOWN, ready.brief.coverage)
        assertTrue(repository.asked.isNotEmpty())
        assertTrue(repository.asked.all { GeoHash.isValid(it, GeoHash.REGION) })
        assertEquals((CorridorBriefPolicy.regionsFor(corridor) as CorridorBriefPolicy.Plan.Regions).cells, repository.asked)
        assertEquals(listOf("MG Road"), resolver.queries.map { it.first })
    }

    @Test
    fun `nothing is located or sent without a destination or without location`() = runTest {
        val vm = viewModel()
        vm.onCheck("  ")
        advanceUntilIdle()
        assertEquals(WayCheckState.NeedsDestination, vm.state.value)

        location.access = LocationAccess.NONE
        vm.onCheck("MG Road")
        advanceUntilIdle()
        assertEquals(WayCheckState.NoLocation(LocationUnavailableReason.PERMISSION_DENIED), vm.state.value)
        assertTrue(resolver.queries.isEmpty())
        assertTrue(repository.asked.isEmpty())
    }

    @Test
    fun `a place that cannot be found, a failure and an incomplete pack are each said honestly`() = runTest {
        val vm = viewModel()
        resolver.result = DestinationLookup.NotFound
        vm.onCheck("Nowhere")
        advanceUntilIdle()
        assertEquals(WayCheckState.DestinationNotFound, vm.state.value)
        assertTrue(repository.asked.isEmpty())

        resolver.result = DestinationLookup.Found(destination, null)
        repository.result = { ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "You have checked many different areas today.") }
        vm.onCheck("MG Road")
        advanceUntilIdle()
        assertTrue(vm.state.value is WayCheckState.Failed)

        repository.result = { ApiResult.Success(RegionPack(it, false, emptyList(), emptyList())) }
        vm.onCheck("MG Road")
        advanceUntilIdle()
        assertEquals(WayCheckState.Incomplete, vm.state.value)

        vm.onDestinationChanged()
        assertEquals(WayCheckState.Idle, vm.state.value)
    }
}
