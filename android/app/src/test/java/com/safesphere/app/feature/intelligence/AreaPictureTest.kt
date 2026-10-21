package com.safesphere.app.feature.intelligence

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.data.remote.AreaResponseDto
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.ui.AreaState
import com.safesphere.app.feature.community.ui.CommunityViewModel
import com.safesphere.app.feature.intelligence.data.remote.AreaCellPictureDto
import com.safesphere.app.feature.intelligence.data.remote.AreaPictureDto
import com.safesphere.app.feature.intelligence.domain.AreaCellPicture
import com.safesphere.app.feature.intelligence.domain.AreaOutlook
import com.safesphere.app.feature.intelligence.domain.AreaPicture
import com.safesphere.app.feature.intelligence.domain.ConcernLevel
import com.safesphere.app.feature.intelligence.domain.InformationLevel
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy.Compass
import com.safesphere.app.feature.intelligence.domain.PictureReason
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.testing.FakeCommunityOutbox
import com.safesphere.app.testing.FakeCommunityRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.ScriptedLocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 8B: mapping (fails closed), display rules, and the picture in the Community screen state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AreaPictureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val area = "tdr1v9"
    private val origin = GeoHash.centre(area)

    private fun dto(
        outlook: String? = "TAKE_CARE",
        concern: String? = "SOME",
        information: String? = "LITTLE",
        reasons: List<String>? = listOf("CONFIRMED_CONDITION", "SOMETHING_NEW", "LITTLE_INFORMATION"),
        categories: List<String>? = listOf("POOR_LIGHTING", "NOT_A_CATEGORY"),
        highest: String? = area,
        areas: List<AreaCellPictureDto>? = listOf(
            AreaCellPictureDto(area, "TAKE_CARE", "LITTLE"),
            AreaCellPictureDto("tdr1vc", "NO_INFORMATION", "NONE"),
            AreaCellPictureDto("TDR1VD", "NO_INFORMATION", "NONE"),
            AreaCellPictureDto("tdr1vf", "FUTURE_VALUE", "NONE")
        )
    ) = AreaPictureDto("rules-1", outlook, concern, information, reasons, categories, false, highest, areas)

    // ---- Mapping ----------------------------------------------------------------------------------------------

    @Test
    fun `a picture is mapped with unknown reasons, categories and bad cells dropped`() {
        val picture = dto().toDomain()!!
        assertEquals(AreaOutlook.TAKE_CARE, picture.outlook)
        assertEquals(listOf(PictureReason.CONFIRMED_CONDITION, PictureReason.LITTLE_INFORMATION), picture.reasons)
        assertEquals(listOf(SignalCategory.POOR_LIGHTING), picture.concernCategories)
        assertEquals(listOf(area, "tdr1vc"), picture.areas.map { it.cell })
        assertEquals(area, picture.highestConcernCell)
    }

    @Test
    fun `unknown or inconsistent headlines give no picture at all`() {
        assertNull(dto(outlook = "ALL_CLEAR").toDomain())
        assertNull(dto(outlook = null).toDomain())
        assertNull(dto(information = "LOTS").toDomain())
        assertNull("a headline must match its axes", dto(outlook = "NO_INFORMATION", concern = "ELEVATED").toDomain())
        assertNull(dto(outlook = "NO_CONFIRMED_CONCERNS", concern = "NONE_CONFIRMED", information = "NONE").toDomain())
        assertEquals(AreaOutlook.NO_INFORMATION,
            dto(outlook = "NO_INFORMATION", concern = "NONE_CONFIRMED", information = "NONE").toDomain()!!.outlook)
    }

    @Test
    fun `a highest-concern cell outside the listed cells is ignored`() {
        assertNull(dto(highest = "tdr1vz").toDomain()!!.highestConcernCell)
    }

    @Test
    fun `an older server without a picture still gives the signals`() {
        val view = AreaResponseDto(area, emptyList()).toView()
        assertTrue(view.signals.isEmpty())
        assertNull(view.picture)
    }

    @Test
    fun `cells never reach logs`() {
        assertFalse(dto().toString().contains(area))
        assertFalse(dto().toDomain()!!.toString().contains(area))
        assertFalse(AreaCellPictureDto(area, "TAKE_CARE", "LITTLE").toString().contains(area))
        assertFalse(GeoHash.bounds(area).toString().contains("12."))
    }

    // ---- Display rules ------------------------------------------------------------------------------------------

    @Test
    fun `geohash decoding matches encoding`() {
        for (cell in listOf("tdr1v9", "tdr1v9q", "u4pruyd", "dr5reg", "tdr1v")) {
            val centre = GeoHash.centre(cell)
            assertEquals(cell, GeoHash.encode(centre, cell.length))
            val b = GeoHash.bounds(cell)
            assertTrue(b.latMin < b.latMax && b.lngMin < b.lngMax)
        }
        assertTrue(GeoHash.isValid("tdr1v9", 6))
        assertFalse(GeoHash.isValid("tdr1va", 6))
        assertFalse(GeoHash.isValid("tdr1v9", 7))
    }

    @Test
    fun `night puts lighting and isolation first, by the phone's own clock`() {
        assertTrue(IntelligencePolicy.isNight(21))
        assertTrue(IntelligencePolicy.isNight(3))
        assertFalse(IntelligencePolicy.isNight(12))
        val categories = listOf(SignalCategory.BROKEN_PATH, SignalCategory.HARASSMENT, SignalCategory.POOR_LIGHTING)
        assertEquals(listOf(SignalCategory.POOR_LIGHTING, SignalCategory.BROKEN_PATH, SignalCategory.HARASSMENT),
            IntelligencePolicy.orderForTime(categories, night = true))
        assertEquals(categories, IntelligencePolicy.orderForTime(categories, night = false))
    }

    @Test
    fun `the strongest concern is placed by direction and rounded distance, or as in your area`() {
        assertEquals(IntelligencePolicy.Placement(0, null), IntelligencePolicy.place(origin, area))
        val north = GeoHash.encode(origin.latitude + 0.006, origin.longitude, 6)
        val placement = IntelligencePolicy.place(origin, north)!!
        assertEquals(Compass.N, placement.compass)
        assertEquals(0, placement.approximateMeters % 50)
        assertNull(IntelligencePolicy.place(origin, "nope"))
        assertEquals(Compass.NE, Compass.fromBearing(44.0))
        assertEquals(Compass.N, Compass.fromBearing(359.0))
        assertEquals(Compass.W, Compass.fromBearing(-90.0))
    }

    @Test
    fun `radar cells are the real cell rectangles around the person`() {
        val picture = AreaPicture(AreaOutlook.NO_INFORMATION, ConcernLevel.NONE_CONFIRMED, InformationLevel.NONE,
            emptyList(), emptyList(), false, null, listOf(AreaCellPicture(area, AreaOutlook.NO_INFORMATION, InformationLevel.NONE)))
        val cell = IntelligencePolicy.radarCells(origin, picture).single()
        assertTrue(cell.west < 0 && cell.east > 0 && cell.south < 0 && cell.north > 0)
        assertEquals(1190.0, cell.east - cell.west, 60.0)  // ~1.2 km wide at Bangalore
        assertEquals(610.0, cell.north - cell.south, 20.0) // ~0.6 km tall
    }

    // ---- Screen state -------------------------------------------------------------------------------------------

    private val repository = FakeCommunityRepository()
    private fun viewModel() = CommunityViewModel(repository, FakeCommunityOutbox(), ScriptedLocationProvider()) { 0L }

    @Test
    fun `the picture reaches the ready state, and only with a fresh answer`() = runTest {
        repository.picture = dto().toDomain()
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(AreaOutlook.TAKE_CARE, (vm.ui.value.area as AreaState.Ready).picture!!.outlook)

        repository.areaResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.refresh()
        advanceUntilIdle()
        assertTrue("offline: the old picture is not kept as if it were current", vm.ui.value.area is AreaState.Failed)
    }

    @Test
    fun `no picture from the server means no card, signals still shown`() = runTest {
        repository.picture = null
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertNull((vm.ui.value.area as AreaState.Ready).picture)
    }

    @Test
    fun `origin of the radar cells is the person's fix, which is never sent`() = runTest {
        repository.picture = dto().toDomain()
        val vm = viewModel()
        vm.onScreenVisible()
        advanceUntilIdle()
        assertEquals(listOf(GeoHash.encode(GeoPoint(12.9716, 77.5946), 6)), repository.areaCalls)
    }
}
