package com.safesphere.app.core.location

import com.safesphere.app.feature.emergency.data.remote.LocationBatchRequestDto
import com.safesphere.app.feature.emergency.data.remote.LocationPointDto
import com.safesphere.app.feature.emergency.domain.EmergencyLocationPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers of core/location, the permission interpretation, and location redaction. */
class LocationCoreTest {

    private val fix = LocationFix(12.971599, 77.594563, 8.5f, LocationSource.FUSED, Instant.parse("2026-10-15T10:00:00Z"))

    @Test
    fun `distance between two known points`() {
        // Bengaluru MG Road -> Cubbon Park, roughly 1.5 km apart.
        val meters = GeoDistance.meters(12.9756, 77.6066, 12.9763, 77.5929)
        assertEquals(1_485.0, meters, 50.0)
        assertEquals(0.0, GeoDistance.meters(fix, fix), 0.001)
    }

    @Test
    fun `accuracy is classified for low-accuracy warnings`() {
        assertEquals(AccuracyLevel.GOOD, accuracyLevelOf(8f))
        assertEquals(AccuracyLevel.FAIR, accuracyLevelOf(75f))
        assertEquals(AccuracyLevel.POOR, accuracyLevelOf(400f))
        assertEquals(AccuracyLevel.UNKNOWN, accuracyLevelOf(null))
        assertEquals(AccuracyLevel.UNKNOWN, accuracyLevelOf(Float.NaN))
    }

    @Test
    fun `android provider names map to the backend sources`() {
        assertEquals(LocationSource.FUSED, locationSourceForProvider("fused"))
        assertEquals(LocationSource.GPS, locationSourceForProvider("gps"))
        assertEquals(LocationSource.NETWORK, locationSourceForProvider("network"))
        assertEquals(LocationSource.UNKNOWN, locationSourceForProvider(null))
        assertEquals(LocationSource.UNKNOWN, locationSourceForProvider("passive"))
    }

    @Test
    fun `permission results - precise, approximate, denied and permanently denied`() {
        assertEquals(LocationPermissionStatus.GRANTED_PRECISE, LocationPermissions.statusAfterRequest(true, true, false))
        assertEquals(LocationPermissionStatus.GRANTED_APPROXIMATE, LocationPermissions.statusAfterRequest(false, true, false))
        assertEquals(LocationPermissionStatus.DENIED, LocationPermissions.statusAfterRequest(false, false, true))
        assertEquals(LocationPermissionStatus.PERMANENTLY_DENIED, LocationPermissions.statusAfterRequest(false, false, false))
    }

    @Test
    fun `coordinates never appear in toString of models or DTOs`() {
        val point = EmergencyLocationPoint(fix, batteryPercent = 50)
        val dto = LocationPointDto.from(point)
        val texts = listOf(fix.toString(), point.toString(), dto.toString(), LocationBatchRequestDto(listOf(dto)).toString())

        texts.forEach { text ->
            assertFalse(text, text.contains("12.97"))
            assertFalse(text, text.contains("77.59"))
        }
        assertTrue(fix.toString().contains("redacted"))
    }

    @Test
    fun `the upload DTO carries everything the server expects`() {
        val dto = LocationPointDto.from(EmergencyLocationPoint(fix, batteryPercent = 50))

        assertEquals(12.971599, dto.latitude, 0.0)
        assertEquals(77.594563, dto.longitude, 0.0)
        assertEquals(8.5f, dto.accuracyMeters)
        assertEquals("FUSED", dto.source)
        assertEquals(50, dto.batteryPercent)
        assertEquals("2026-10-15T10:00:00Z", dto.recordedAt)
    }
}
