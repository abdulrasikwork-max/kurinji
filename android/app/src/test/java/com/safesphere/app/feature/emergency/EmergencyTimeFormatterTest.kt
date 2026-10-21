package com.safesphere.app.feature.emergency

import com.safesphere.app.feature.emergency.ui.EmergencyTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyTimeFormatterTest {

    private val instant = Instant.parse("2026-10-15T22:30:00Z")

    @Test
    fun `the same instant is shown in the phone's own time zone`() {
        val inIndia = EmergencyTimeFormatter.format(instant, ZoneId.of("Asia/Kolkata"), Locale.US)
        val inUtc = EmergencyTimeFormatter.format(instant, ZoneId.of("UTC"), Locale.US)

        assertNotEquals(inIndia, inUtc)
        // 22:30 UTC is already the next morning in India (UTC+5:30), so the date differs.
        assertTrue(inIndia.contains("16"))
        assertTrue(inUtc.contains("15"))
    }
}
