package com.safesphere.app.feature.community

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.feature.community.data.remote.AreaResponseDto
import com.safesphere.app.feature.community.data.remote.ContributionRequestDto
import com.safesphere.app.feature.community.data.remote.PublicSignalDto
import com.safesphere.app.feature.community.data.remote.ReportRequestDto
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.CommunityPolicy.ReportReadiness
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.PendingReport
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.testing.areaSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityPolicyTest {

    @Test
    fun `reporting needs precise location and a good fix`() {
        assertEquals(ReportReadiness.READY, CommunityPolicy.reportReadiness(LocationAccess.PRECISE, 20f))
        assertEquals(ReportReadiness.NEEDS_PRECISE_LOCATION, CommunityPolicy.reportReadiness(LocationAccess.APPROXIMATE, 20f))
        assertEquals(ReportReadiness.NEEDS_PRECISE_LOCATION, CommunityPolicy.reportReadiness(LocationAccess.NONE, 5f))
        assertEquals(ReportReadiness.LOCATION_TOO_ROUGH, CommunityPolicy.reportReadiness(LocationAccess.PRECISE, 150f))
        assertEquals(ReportReadiness.LOCATION_TOO_ROUGH, CommunityPolicy.reportReadiness(LocationAccess.PRECISE, null))
    }

    @Test
    fun `outbox keeps one per place and category, at most ten, and drops stale ones`() {
        var outbox = emptyList<PendingReport>()
        outbox = CommunityPolicy.addToOutbox(outbox, PendingReport(SignalCategory.POOR_LIGHTING, "tdr1v9q", 1))
        outbox = CommunityPolicy.addToOutbox(outbox, PendingReport(SignalCategory.POOR_LIGHTING, "tdr1v9q", 2))
        assertEquals(1, outbox.size)
        repeat(15) { i -> outbox = CommunityPolicy.addToOutbox(outbox, PendingReport(SignalCategory.HELP_POINT, "tdr1v9$i", 10L + i)) }
        assertEquals(CommunityPolicy.OUTBOX_MAX, outbox.size)

        val now = 100_000_000L
        val fresh = PendingReport(SignalCategory.HELP_POINT, "a", now - 1000)
        val stale = PendingReport(SignalCategory.HELP_POINT, "b", now - CommunityPolicy.OUTBOX_MAX_AGE_MILLIS - 1)
        assertEquals(listOf(fresh), CommunityPolicy.sendable(listOf(fresh, stale), now))
    }

    @Test
    fun `signals are placed by distance and compass bearing, nearest first`() {
        val me = GeoPoint(12.9716, 77.5946)
        val north = areaSignal(id = "n", centre = GeoPoint(12.9806, 77.5946)) // ~1 km north
        val east = areaSignal(id = "e", centre = GeoPoint(12.9716, 77.5976))  // ~325 m east
        val placed = CommunityPolicy.place(listOf(north, east), me)
        assertEquals(listOf("e", "n"), placed.map { it.signal.id })
        assertEquals(90.0, placed[0].bearingDegrees, 1.0)
        assertEquals(0.0, placed[1].bearingDegrees, 1.0)
        assertEquals(1000.0, placed[1].distanceMeters, 15.0)
    }

    @Test
    fun `brief counts kinds and names the nearest safe place`() {
        val me = GeoPoint(12.9716, 77.5946)
        val placed = CommunityPolicy.place(listOf(
            areaSignal(id = "1", category = SignalCategory.HARASSMENT),
            areaSignal(id = "2", category = SignalCategory.POOR_LIGHTING),
            areaSignal(id = "3", category = SignalCategory.HELP_POINT, centre = GeoPoint(12.9746, 77.5946)),
            areaSignal(id = "4", category = SignalCategory.OPEN_LATE_STAFFED, centre = GeoPoint(12.9900, 77.5946))
        ), me)
        val brief = CommunityPolicy.brief(placed)
        assertEquals(1, brief.incidents)
        assertEquals(1, brief.conditions)
        assertEquals(2, brief.havens)
        assertEquals(350, brief.nearestHavenMeters) // ~334 m, shown in 50 m steps
        assertTrue(CommunityPolicy.brief(emptyList()).isEmpty)
        assertNull(CommunityPolicy.brief(emptyList()).nearestHavenMeters)
    }

    @Test
    fun `distances are rounded, never more precise than the cells`() {
        assertEquals(50, CommunityPolicy.approximateMeters(3.0))
        assertEquals(300, CommunityPolicy.approximateMeters(312.0))
        assertEquals(1200, CommunityPolicy.approximateMeters(1234.0))
    }

    @Test
    fun `a signal you already answered cannot be answered again`() {
        assertTrue(CommunityPolicy.canAnswer(areaSignal()))
        assertFalse(CommunityPolicy.canAnswer(areaSignal(yours = ContributionKind.REPORT)))
    }

    @Test
    fun `server answers are mapped defensively and request bodies redact cells`() {
        val dto = AreaResponseDto("tdr1v9", listOf(
            PublicSignalDto("a", "POOR_LIGHTING", "BLOCK", "tdr1v9q", 12.97, 77.59, "SEVERAL", "TODAY", true, "CONFIRM"),
            PublicSignalDto("b", "SOMETHING_NEW", "AREA", "tdr1v9", 12.97, 77.59, "SINGLE", "TODAY", false, null),
            PublicSignalDto("c", "HELP_POINT", "AREA", "tdr1v9", null, 77.59, "SINGLE", "TODAY", false, null)
        ))
        val signals = dto.toDomain()
        assertEquals(listOf("a"), signals.map { it.id })
        assertEquals(ContributionKind.CONFIRM, signals[0].yourContribution)
        assertFalse(ReportRequestDto("POOR_LIGHTING", "tdr1v9q").toString().contains("tdr1v9q"))
        assertFalse(ContributionRequestDto("CONFIRM", "tdr1v9q").toString().contains("tdr1v9q"))
    }
}
