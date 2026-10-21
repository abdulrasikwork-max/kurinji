package com.safesphere.app.feature.emergency

import com.safesphere.app.feature.emergency.location.LocationSamplingPolicy
import com.safesphere.app.testing.FakeLocationProvider.Companion.fix
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSamplingPolicyTest {

    private val policy = LocationSamplingPolicy()
    private val startedAt = Instant.parse("2026-10-15T10:00:00Z")
    private val first = fix(latitude = 12.9716, longitude = 77.5946, accuracy = 8f, at = startedAt.plusSeconds(5))

    @Test
    fun `the first fix is always saved`() {
        assertTrue(policy.shouldRecord(null, first, startedAt))
    }

    @Test
    fun `a fix cached long before the emergency began is not saved`() {
        assertFalse(policy.shouldRecord(null, first.copy(recordedAt = startedAt.minusSeconds(301)), startedAt))
        // Small phone clock differences are tolerated (same 5 minutes as the server).
        assertTrue(policy.shouldRecord(null, first.copy(recordedAt = startedAt.minusSeconds(299)), startedAt))
    }

    @Test
    fun `standing still is saved once a minute`() {
        assertFalse(policy.shouldRecord(first, first.copy(recordedAt = first.recordedAt.plusSeconds(30)), startedAt))
        assertTrue(policy.shouldRecord(first, first.copy(recordedAt = first.recordedAt.plusSeconds(60)), startedAt))
    }

    @Test
    fun `moving further than the minimum distance is saved at once`() {
        // About 22 m north.
        val moved = first.copy(latitude = first.latitude + 0.0002, recordedAt = first.recordedAt.plusSeconds(10))
        assertTrue(policy.shouldRecord(first, moved, startedAt))
    }

    @Test
    fun `movement smaller than the fix's own uncertainty is treated as noise`() {
        val noisy = first.copy(latitude = first.latitude + 0.0002, accuracyMeters = 80f, recordedAt = first.recordedAt.plusSeconds(10))
        assertFalse(policy.shouldRecord(first, noisy, startedAt))
    }

    @Test
    fun `the same or an older fix is never saved twice`() {
        assertFalse(policy.shouldRecord(first, first, startedAt))
        assertFalse(policy.shouldRecord(first, first.copy(latitude = 13.5, recordedAt = first.recordedAt.minusSeconds(1)), startedAt))
    }
}
